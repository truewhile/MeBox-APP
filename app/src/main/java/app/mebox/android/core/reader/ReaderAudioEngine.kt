package app.mebox.android.core.reader

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.mebox.android.core.log.DiagnosticsLog
import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderChapterContentDto
import app.mebox.android.core.network.ReaderChapterDto
import app.mebox.android.service.ReaderAudioService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReaderAudioState(
    val connected: Boolean = false,
    val bookId: String? = null,
    val bookName: String = "",
    val author: String = "",
    val coverUrl: String = "",
    val chapterIndex: Int = -1,
    val chapterTitle: String = "",
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val timerMinutes: Int = 0,
    val timerEndsAtMs: Long = 0,
    /** 定时模式二：播完当前章后暂停（与分钟定时互斥）。 */
    val stopAtChapterEnd: Boolean = false,
    /** 本书的片头/片尾跳过秒数，来自服务端书籍配置。 */
    val openCreditsSec: Int = 0,
    val closeCreditsSec: Int = 0,
    val error: String? = null,
    val notice: String? = null,
) {
    val active: Boolean get() = bookId != null

    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * 听书播放引擎（应用级单例）。
 *
 * 自己持有 MediaController 连接 [ReaderAudioService]，负责：
 * - 按章节拉取音轨、维护播放列表与自动下一章；
 * - 每 5 秒上报服务端进度（单位秒），暂停/切章时立即上报；
 * - 自由倍速、分钟定时与「播完本章暂停」两种定时，以及片头/片尾跳过。
 *
 * 引擎不依赖阅读页面：离开正文或退到后台后仍由前台媒体服务继续播放。
 */
class ReaderAudioEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val cache: ReaderCacheStore,
) {
    private val _state = MutableStateFlow(ReaderAudioState())
    val state: StateFlow<ReaderAudioState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var connecting = false
    private val connectWaiters = mutableListOf<(MediaController) -> Unit>()
    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var timerJob: Job? = null
    private var endingChapter = false
    private var startedPlayback = false
    private var stopAtChapterEnd = false

    /** 用户拖动进度条后的保护期，避免刚拖到结尾就被片尾跳过切走。 */
    private var seekGuardUntilMs = 0L

    private var repo: ReaderRepository? = null
    private var accountScope: AccountScope? = null
    private var origin: String = ""
    private var book: ReaderBookDto? = null
    private var chapters: List<ReaderChapterDto> = emptyList()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying) }
            if (!isPlaying && startedPlayback) {
                scope.launch { reportProgress() }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    loading = playbackState == Player.STATE_BUFFERING,
                    playing = controller?.isPlaying == true,
                )
            }
            if (playbackState == Player.STATE_ENDED) {
                onChapterFinished()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            DiagnosticsLog.e(
                "ReaderAudio",
                "音频播放失败 ${error.errorCodeName} (${error.errorCode})",
                error,
            )
            _state.update {
                it.copy(
                    loading = false,
                    playing = false,
                    error = error.message?.takeIf { message -> message.isNotBlank() }
                        ?: "音频播放失败",
                )
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                seekGuardUntilMs = System.currentTimeMillis() + SEEK_GUARD_MS
            }
        }
    }

    fun updateChapters(chapters: List<ReaderChapterDto>) {
        this.chapters = chapters
    }

    /**
     * 打开某一章的音频。若引擎已经在播放本书，则保持继续播放；
     * 否则等用户点击播放后才开始。
     */
    fun startChapter(
        book: ReaderBookDto,
        chapters: List<ReaderChapterDto>,
        chapterIndex: Int,
        startPositionSec: Int,
        repository: ReaderRepository,
        scope: AccountScope,
        origin: String,
        autoPlay: Boolean,
        speed: Float,
        content: ReaderChapterContentDto? = null,
    ) {
        this.book = book
        this.chapters = chapters
        this.repo = repository
        this.accountScope = scope
        this.origin = origin
        _state.update {
            it.copy(
                bookId = book.id,
                bookName = book.name,
                author = book.author,
                coverUrl = book.coverUrl,
                speed = ReaderAudioTuning.snapSpeed(speed),
                openCreditsSec = ReaderAudioTuning.clampCreditsSeconds(book.openCredits),
                closeCreditsSec = ReaderAudioTuning.clampCreditsSeconds(book.closeCredits),
                error = null,
                notice = null,
            )
        }
        controller?.setPlaybackSpeed(ReaderAudioTuning.snapSpeed(speed))
        loadChapter(chapterIndex, startPositionSec, autoPlay, content)
    }

    /** 保存片头/片尾配置后同步到引擎；正在片头里时立即前跳。 */
    fun updateCredits(openCreditsSeconds: Int, closeCreditsSeconds: Int) {
        val open = ReaderAudioTuning.clampCreditsSeconds(openCreditsSeconds)
        val close = ReaderAudioTuning.clampCreditsSeconds(closeCreditsSeconds)
        book = book?.copy(openCredits = open, closeCredits = close)
        _state.update { it.copy(openCreditsSec = open, closeCreditsSec = close) }
        val player = controller ?: return
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val target = ReaderAudioTuning.startPositionMs(open, player.currentPosition, duration)
        if (target > player.currentPosition) seekTo(target)
    }

    fun play() {
        ensureController { controller ->
            if (controller.mediaItemCount == 0) {
                _state.update { it.copy(error = "还没有可播放的章节") }
                return@ensureController
            }
            startedPlayback = true
            if (controller.playbackState == Player.STATE_ENDED) {
                resumeAfterEnd(controller)
            } else {
                controller.play()
            }
        }
    }

    fun pause() {
        controller?.pause()
        if (startedPlayback) scope.launch { reportProgress() }
    }

    fun toggle() {
        val current = controller
        if (current == null) {
            play()
            return
        }
        if (current.isPlaying) {
            current.pause()
            if (startedPlayback) scope.launch { reportProgress() }
        } else {
            if (current.mediaItemCount == 0) {
                _state.update { it.copy(error = "还没有可播放的章节") }
            } else if (current.playbackState == Player.STATE_ENDED) {
                startedPlayback = true
                resumeAfterEnd(current)
            } else {
                startedPlayback = true
                current.play()
            }
        }
    }

    /**
     * 已播到最后一帧时再次点击播放：有下一章就续到下一章，
     * 否则从头重播本章。否则「播完本章暂停」后播放键会失效。
     */
    private fun resumeAfterEnd(player: MediaController) {
        val nextIndex = nextReadable(chapters, _state.value.chapterIndex)
        if (nextIndex != null) {
            loadChapter(nextIndex, 0, autoPlay = true)
        } else {
            player.seekTo(0)
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0)
        controller?.seekTo(target)
        _state.update { it.copy(positionMs = target) }
        if (startedPlayback) scope.launch { reportProgress(target) }
    }

    fun seekBy(deltaMs: Long) {
        val current = controller ?: return
        val target = (current.currentPosition + deltaMs).coerceAtLeast(0)
        seekTo(target)
    }

    fun next() {
        val currentIndex = _state.value.chapterIndex
        val nextIndex = nextReadable(chapters, currentIndex)
        if (nextIndex == null) {
            _state.update { it.copy(notice = "已经是最后一章") }
            return
        }
        if (startedPlayback) scope.launch { reportProgress() }
        loadChapter(nextIndex, 0, autoPlay = true)
    }

    fun previous() {
        val currentIndex = _state.value.chapterIndex
        val previousIndex = previousReadable(chapters, currentIndex)
        if (previousIndex == null) {
            _state.update { it.copy(notice = "已经是第一章") }
            return
        }
        if (startedPlayback) scope.launch { reportProgress() }
        loadChapter(previousIndex, 0, autoPlay = true)
    }

    fun jumpToChapter(index: Int) {
        if (chapters.getOrNull(index)?.isVolume != false) return
        if (startedPlayback) scope.launch { reportProgress() }
        loadChapter(index, 0, autoPlay = true)
    }

    fun setSpeed(speed: Float) {
        val safe = ReaderAudioTuning.snapSpeed(speed)
        _state.update { it.copy(speed = safe) }
        controller?.setPlaybackSpeed(safe)
    }

    fun setTimer(minutes: Int) {
        val safe = ReaderAudioTuning.clampTimerMinutes(minutes)
        timerJob?.cancel()
        stopAtChapterEnd = false
        if (safe <= 0) {
            _state.update { it.copy(timerMinutes = 0, timerEndsAtMs = 0, stopAtChapterEnd = false) }
            return
        }
        val endsAt = System.currentTimeMillis() + safe * 60_000L
        _state.update {
            it.copy(timerMinutes = safe, timerEndsAtMs = endsAt, stopAtChapterEnd = false)
        }
        timerJob = scope.launch {
            delay(safe * 60_000L)
            controller?.pause()
            _state.update {
                it.copy(timerMinutes = 0, timerEndsAtMs = 0, notice = "定时关闭已生效")
            }
        }
    }

    /** 定时模式二：播完当前章后暂停，不再自动进入下一章。 */
    fun setStopAtChapterEnd(enabled: Boolean) {
        timerJob?.cancel()
        stopAtChapterEnd = enabled
        _state.update {
            it.copy(
                stopAtChapterEnd = enabled,
                timerMinutes = 0,
                timerEndsAtMs = 0,
                notice = if (enabled) "本章播完后自动暂停" else null,
            )
        }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    /** 停止播放并清空当前书籍（退出账号、移出书架或用户主动停止时调用）。 */
    fun stop() {
        loadJob?.cancel()
        tickerJob?.cancel()
        timerJob?.cancel()
        endingChapter = false
        startedPlayback = false
        stopAtChapterEnd = false
        seekGuardUntilMs = 0L
        controller?.stop()
        controller?.clearMediaItems()
        book = null
        chapters = emptyList()
        _state.value = ReaderAudioState(connected = controller != null)
    }

    fun shutdown() {
        stop()
        controller?.release()
        controller = null
    }

    fun currentPositionMs(): Long = controller?.currentPosition ?: _state.value.positionMs

    // ── 内部实现 ──

    private fun loadChapter(
        index: Int,
        startSec: Int,
        autoPlay: Boolean,
        providedContent: ReaderChapterContentDto? = null,
    ) {
        val currentBook = book ?: return
        val repository = repo ?: return
        if (chapters.getOrNull(index) == null) return
        loadJob?.cancel()
        endingChapter = false
        _state.update {
            it.copy(
                chapterIndex = index,
                chapterTitle = chapters.getOrNull(index)?.title.orEmpty(),
                loading = true,
                error = null,
                notice = null,
                positionMs = 0,
                durationMs = 0,
            )
        }
        loadJob = scope.launch {
            val content = providedContent ?: run {
                val cached = withContext(Dispatchers.IO) {
                    accountScope?.let { cache.read(it, currentBook.id, index) }
                }
                when (val result = repository.content(currentBook.id, index)) {
                    is ApiResult.Success -> result.value
                    is ApiResult.Failure -> {
                        if (cached != null) {
                            _state.update { it.copy(notice = "已加载离线缓存") }
                            cached
                        } else {
                            DiagnosticsLog.w(
                                "ReaderAudio",
                                "加载音轨失败 book=${currentBook.id} index=$index " +
                                    "HTTP ${result.status} ${result.code}",
                            )
                            _state.update {
                                it.copy(
                                    loading = false,
                                    error = result.message.ifBlank { "加载音轨失败" },
                                )
                            }
                            return@launch
                        }
                    }
                    is ApiResult.NetworkError -> {
                        if (cached != null) {
                            _state.update { it.copy(notice = "网络不可用，正在播放离线缓存") }
                            cached
                        } else {
                            _state.update {
                                it.copy(loading = false, error = "网络不可用，请稍后重试")
                            }
                            return@launch
                        }
                    }
                    ApiResult.Stale -> {
                        _state.update { it.copy(loading = false) }
                        return@launch
                    }
                }
            }
            if (content.type != "audio") {
                _state.update {
                    it.copy(loading = false, error = "本章不是音频内容")
                }
                return@launch
            }
            val urls = content.tracks.orEmpty()
                .mapNotNull { ReaderUrls.resolve(origin, it) }
            if (urls.isEmpty()) {
                _state.update {
                    it.copy(loading = false, error = "本章没有可播放的音轨")
                }
                return@launch
            }
            val title = chapters.getOrNull(index)?.title.orEmpty()
            val openCredits = ReaderAudioTuning.clampCreditsSeconds(currentBook.openCredits)
            val startMs = ReaderAudioTuning.startPositionMs(
                openCreditsSeconds = openCredits,
                requestedMs = startSec.coerceAtLeast(0) * 1_000L,
                durationMs = 0L,
            )
            ensureController { controller ->
                val metadata = MediaMetadata.Builder()
                    .setTitle(currentBook.name)
                    .setArtist(title.ifBlank { currentBook.name })
                    .build()
                val items = urls.mapIndexed { trackIndex, url ->
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaId("reader:${currentBook.id}:$index:$trackIndex")
                        .setMediaMetadata(metadata)
                        .build()
                }
                seekGuardUntilMs = System.currentTimeMillis() + SEEK_GUARD_MS
                controller.setMediaItems(items, 0, startMs)
                controller.setPlaybackSpeed(_state.value.speed)
                controller.prepare()
                controller.playWhenReady = autoPlay
                if (autoPlay) startedPlayback = true
                _state.update {
                    it.copy(
                        loading = false,
                        playing = autoPlay,
                        error = null,
                    )
                }
                startTicker()
            }
        }
    }

    private fun onChapterFinished() {
        if (endingChapter) return
        endingChapter = true
        if (startedPlayback) scope.launch { reportProgress() }
        if (stopAtChapterEnd) {
            stopAtChapterEnd = false
            controller?.pause()
            tickerJob?.cancel()
            _state.update {
                it.copy(
                    playing = false,
                    stopAtChapterEnd = false,
                    notice = "本章已播完，定时暂停",
                )
            }
            return
        }
        val nextIndex = nextReadable(chapters, _state.value.chapterIndex)
        if (nextIndex == null) {
            tickerJob?.cancel()
            _state.update { it.copy(playing = false, notice = "已播放完最后一章") }
            return
        }
        loadChapter(nextIndex, 0, autoPlay = true)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(1_000)
                val player = controller ?: continue
                val position = player.currentPosition.coerceAtLeast(0)
                val duration = player.duration.takeIf { it > 0 } ?: 0
                _state.update {
                    it.copy(
                        positionMs = position,
                        durationMs = duration,
                        playing = player.isPlaying,
                    )
                }
                ticks += 1
                if (ticks % 5 == 0 && player.isPlaying) {
                    reportProgress(position)
                }
                val openCredits = book?.openCredits ?: 0
                val closeCredits = book?.closeCredits ?: 0
                if (player.isPlaying &&
                    System.currentTimeMillis() >= seekGuardUntilMs &&
                    ReaderAudioTuning.isEndCreditsReached(openCredits, closeCredits, position, duration)
                ) {
                    onChapterFinished()
                }
            }
        }
    }

    private suspend fun reportProgress(positionMs: Long = currentPositionMs()) {
        val currentBook = book ?: return
        val repository = repo ?: return
        val current = _state.value
        if (current.chapterIndex < 0) return
        repository.saveProgress(
            bookId = currentBook.id,
            chapterIndex = current.chapterIndex,
            pos = (positionMs / 1000).toInt().coerceAtLeast(0),
            chapterTitle = current.chapterTitle,
        )
    }

    private fun ensureController(onReady: (MediaController) -> Unit) {
        controller?.let {
            onReady(it)
            return
        }
        if (connecting) {
            connectWaiters += onReady
            return
        }
        connecting = true
        val token = SessionToken(
            context,
            ComponentName(context, ReaderAudioService::class.java),
        )
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull()
            connecting = false
            if (connected == null) {
                _state.update { it.copy(error = "无法连接后台播放服务") }
                return@addListener
            }
            connected.addListener(listener)
            controller = connected
            _state.update { it.copy(connected = true, error = null) }
            val waiters = connectWaiters.toList()
            connectWaiters.clear()
            waiters.forEach { waiter -> waiter(connected) }
            onReady(connected)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun nextReadable(chapters: List<ReaderChapterDto>, from: Int): Int? {
        if (chapters.isEmpty()) return null
        for (index in (from + 1) until chapters.size) {
            if (!chapters[index].isVolume) return index
        }
        return null
    }

    private fun previousReadable(chapters: List<ReaderChapterDto>, from: Int): Int? {
        if (chapters.isEmpty()) return null
        for (index in (from - 1) downTo 0) {
            if (!chapters[index].isVolume) return index
        }
        return null
    }

    private companion object {
        /** 拖动进度条后的保护窗口，让用户能听清自己选中的位置。 */
        const val SEEK_GUARD_MS = 3_000L
    }
}
