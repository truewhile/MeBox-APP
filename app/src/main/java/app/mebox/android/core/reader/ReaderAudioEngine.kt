package app.mebox.android.core.reader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import app.mebox.android.core.storage.AppPreferences
import app.mebox.android.service.ReaderAudioService
import kotlinx.coroutines.CancellationException
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
    /** 用户的播放意图，与播放器是否真正发声分开。 */
    val playWhenReady: Boolean = false,
    /** 音轨解析/后台服务连接阶段；结束后可能仍需要缓冲。 */
    val preparing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val timerMinutes: Int = 0,
    val timerEndsAtMs: Long = 0,
    /** 定时模式二：播完当前章后暂停（与分钟定时互斥）。 */
    val stopAtChapterEnd: Boolean = false,
    /** 与其他应用同时出声：不抢占音频焦点。 */
    val mixWithOtherApps: Boolean = true,
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
    private val preferences: AppPreferences,
    private val checkpoints: ReaderAudioCheckpointStore,
) {
    private val _state = MutableStateFlow(
        ReaderAudioState(mixWithOtherApps = preferences.audioMixWithOtherApps),
    )
    val state: StateFlow<ReaderAudioState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var connecting = false
    private var connectionVersion = 0L
    private val connectWaiters = mutableListOf<(MediaController) -> Unit>()
    private val playback = ReaderAudioPlayback()
    private var startupStartedAtNs = 0L
    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var timerJob: Job? = null

    /**
     * 换章守卫。片尾跳过与「播放结束」都会触发换章，而加载新章音轨要好几秒，
     * 这段时间里播放器仍停在旧章片尾，必须锁住，否则每秒轮询都会再跳一章。
     */
    private val advanceGuard = ChapterAdvanceGuard()

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
            syncPlaybackState()
            if (!isPlaying && startedPlayback && !playback.preparing) {
                persistCheckpoint(synchronous = true)
                scope.launch { reportProgress() }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            syncPlaybackState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncPlaybackState()
            if (playbackState == Player.STATE_ENDED && controller?.playbackState == Player.STATE_ENDED) {
                onChapterFinished()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // 新章解析期间的错误属于旧音轨，不能终止新的请求。
            if (playback.preparing) return
            DiagnosticsLog.e(
                "ReaderAudio",
                "音频播放失败 ${error.errorCodeName} (${error.errorCode})",
                error,
            )
            failChapterLoad(
                playback.requestId,
                _state.value.chapterIndex,
                error.message?.takeIf { it.isNotBlank() } ?: "音频播放失败",
            )
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

    /**
     * 后台服务被系统回收或主动释放时，MediaController 会断开。
     * 保留当前书籍/章节信息，下一次播放时重新连接并按最后位置重新装载，
     * 不能因为控制器失效就把用户丢回章节开头。
     */
    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (this@ReaderAudioEngine.controller !== controller) return
            persistCheckpoint(synchronous = true)
            runCatching { controller.removeListener(listener) }
            this@ReaderAudioEngine.controller = null
            connecting = false
            loadJob?.cancel()
            tickerJob?.cancel()
            playback.reset()
            advanceGuard.reset()
            connectWaiters.clear()
            startedPlayback = false
            seekGuardUntilMs = 0L
            _state.update {
                it.copy(
                    connected = false,
                    preparing = false,
                    loading = false,
                    playing = false,
                    playWhenReady = false,
                )
            }
        }
    }

    fun updateChapters(chapters: List<ReaderChapterDto>) {
        this.chapters = chapters
    }

    /** 只返回仍连接会话的控制器；服务被回收后旧控制器不能再发命令。 */
    private fun liveController(): MediaController? =
        controller?.takeIf { it.isConnected }

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
        liveController()?.setPlaybackSpeed(ReaderAudioTuning.snapSpeed(speed))
        loadChapter(chapterIndex, startPositionSec, autoPlay, content)
    }

    /** 保存片头/片尾配置后同步到引擎；正在片头里时立即前跳。 */
    fun updateCredits(openCreditsSeconds: Int, closeCreditsSeconds: Int) {
        val open = ReaderAudioTuning.clampCreditsSeconds(openCreditsSeconds)
        val close = ReaderAudioTuning.clampCreditsSeconds(closeCreditsSeconds)
        book = book?.copy(openCredits = open, closeCredits = close)
        _state.update { it.copy(openCreditsSec = open, closeCreditsSec = close) }
        val player = liveController() ?: return
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val target = ReaderAudioTuning.startPositionMs(open, player.currentPosition, duration)
        if (target > player.currentPosition) seekTo(target)
    }

    fun play() {
        if (playback.failed) {
            // 解析失败、过期签名或播放器错误都重新获取音轨，不播放残留的旧章。
            reloadCurrent(autoPlay = true)
            return
        }
        playback.setPlayWhenReady(true)
        publishPlaybackState()
        // 服务连接/章节加载完成后使用最新意图，不另外排队一个过早的 play()。
        if (playback.preparing) return
        if (controller?.isConnected != true) {
            // 后台服务被系统回收后控制器会断开；保留的书籍信息仍可用于重新装载。
            reloadCurrent(autoPlay = true)
            return
        }
        val request = playback.requestId
        ensureController { controller ->
            if (!playback.isCurrent(request)) return@ensureController
            if (controller.mediaItemCount == 0) {
                if (book != null) {
                    reloadCurrent(autoPlay = true)
                } else {
                    failChapterLoad(request, _state.value.chapterIndex, "还没有可播放的章节")
                }
                return@ensureController
            }
            startedPlayback = true
            if (controller.playbackState == Player.STATE_ENDED) {
                resumeAfterEnd(controller)
            } else {
                if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                controller.playWhenReady = playback.playWhenReady
                syncPlaybackState()
            }
        }
    }

    /** 控制器断连或音轨丢失后，按最后位置重新连接并装载当前章节。 */
    private fun reloadCurrent(autoPlay: Boolean) {
        if (book == null || chapters.isEmpty()) {
            _state.update { it.copy(error = "还没有可播放的章节") }
            return
        }
        val index = _state.value.chapterIndex.takeIf { it >= 0 } ?: 0
        loadChapter(
            index = index,
            startSec = (currentPositionMs() / 1_000L).toInt().coerceAtLeast(0),
            autoPlay = autoPlay,
        )
    }

    fun pause() {
        playback.setPlayWhenReady(false)
        publishPlaybackState()
        liveController()?.pause()
        persistCheckpoint(synchronous = true)
        if (startedPlayback && !playback.preparing) scope.launch { reportProgress() }
    }

    fun toggle() {
        // 缓冲时 isPlaying=false，仍然应该允许用户暂停待播请求。
        if (playback.playWhenReady || liveController()?.isPlaying == true) pause() else play()
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
        if (playback.preparing || playback.failed) return
        val target = positionMs.coerceAtLeast(0)
        liveController()?.seekTo(target)
        _state.update { it.copy(positionMs = target) }
        persistCheckpoint()
        if (startedPlayback) scope.launch { reportProgress(target) }
    }

    fun seekBy(deltaMs: Long) {
        val current = liveController() ?: return
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
        persistCheckpoint(synchronous = true)
        loadChapter(nextIndex, 0, autoPlay = true)
    }

    /**
     * 是否存在可跳转的下一章。
     *
     * 系统媒体卡片只能用播放器的命令判断按钮是否显示，而播放器里只有当前章的
     * 音轨，所以由声音引擎把「本书还有下一章」这个事实告诉服务层的播放器包装。
     */
    fun hasNextChapter(): Boolean =
        nextReadable(chapters, _state.value.chapterIndex) != null

    /** 是否存在可跳转的上一章，供系统媒体卡片显示「上一章」。 */
    fun hasPreviousChapter(): Boolean =
        previousReadable(chapters, _state.value.chapterIndex) != null

    fun previous() {
        val currentIndex = _state.value.chapterIndex
        val previousIndex = previousReadable(chapters, currentIndex)
        if (previousIndex == null) {
            _state.update { it.copy(notice = "已经是第一章") }
            return
        }
        if (startedPlayback) scope.launch { reportProgress() }
        persistCheckpoint(synchronous = true)
        loadChapter(previousIndex, 0, autoPlay = true)
    }

    fun jumpToChapter(index: Int) {
        if (chapters.getOrNull(index)?.isVolume != false) return
        if (startedPlayback) scope.launch { reportProgress() }
        persistCheckpoint(synchronous = true)
        loadChapter(index, 0, autoPlay = true)
    }

    fun setSpeed(speed: Float) {
        val safe = ReaderAudioTuning.snapSpeed(speed)
        _state.update { it.copy(speed = safe) }
        liveController()?.setPlaybackSpeed(safe)
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
            pause()
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

    /**
     * 切换「与其他应用同时播放」。
     *
     * 开启后播放器不申请音频焦点，其它应用的语音/音乐不会被本应用打断；
     * 关闭后恢复 Media3 默认的焦点抢占与来电暂停行为。
     */
    fun setMixWithOtherApps(enabled: Boolean) {
        if (preferences.audioMixWithOtherApps == enabled) {
            _state.update { it.copy(mixWithOtherApps = enabled) }
            return
        }
        preferences.audioMixWithOtherApps = enabled
        _state.update {
            it.copy(
                mixWithOtherApps = enabled,
                notice = if (enabled) {
                    "已开启与其他应用同时播放"
                } else {
                    "已关闭与其他应用同时播放"
                },
            )
        }
        // 服务没在运行时不为了一个设置项把它拉起来；下次创建播放器会读偏好。
        if (controller != null) {
            runCatching {
                context.startService(
                    Intent(context, ReaderAudioService::class.java)
                        .setAction(ReaderAudioService.ACTION_APPLY_AUDIO_MIX),
                )
            }
        }
    }

    /** 把当前章节与位置写入本地检查点；退后台/服务销毁前用同步写入。 */
    fun persistCheckpoint(synchronous: Boolean = false) {
        val currentBook = book ?: return
        val currentScope = accountScope ?: return
        val index = _state.value.chapterIndex
        if (index < 0) return
        val title = _state.value.chapterTitle.ifBlank {
            chapters.getOrNull(index)?.title.orEmpty()
        }
        checkpoints.save(
            scope = currentScope,
            checkpoint = ReaderAudioCheckpoint(
                bookId = currentBook.id,
                chapterIndex = index,
                chapterTitle = title,
                positionMs = currentPositionMs().coerceAtLeast(0L),
                durationMs = _state.value.durationMs.coerceAtLeast(0L),
                updatedAtMs = System.currentTimeMillis(),
                playing = _state.value.playing,
            ),
            synchronous = synchronous,
        )
    }

    /** 停止播放并清空当前书籍（退出账号、移出书架或用户主动停止时调用）。 */
    fun stop() {
        persistCheckpoint(synchronous = true)
        loadJob?.cancel()
        playback.reset()
        connectWaiters.clear()
        startupStartedAtNs = 0L
        tickerJob?.cancel()
        timerJob?.cancel()
        advanceGuard.reset()
        startedPlayback = false
        stopAtChapterEnd = false
        seekGuardUntilMs = 0L
        runCatching { controller?.stop() }
        runCatching { controller?.clearMediaItems() }
        book = null
        chapters = emptyList()
        _state.value = ReaderAudioState(
            connected = controller != null,
            mixWithOtherApps = preferences.audioMixWithOtherApps,
        )
    }

    fun shutdown() {
        stop()
        connectionVersion += 1
        connecting = false
        val current = controller
        controller = null
        current?.release()
        _state.update { it.copy(connected = false) }
    }

    fun currentPositionMs(): Long {
        if (playback.preparing || playback.failed) return _state.value.positionMs
        val player = controller?.takeIf { it.isConnected } ?: return _state.value.positionMs
        return runCatching { player.currentPosition }.getOrDefault(_state.value.positionMs)
    }

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
        tickerJob?.cancel()
        val request = playback.begin(autoPlay)
        val currentScope = accountScope
        val currentOrigin = origin
        startupStartedAtNs = System.nanoTime()
        advanceGuard.beginLoad()
        _state.update {
            it.copy(
                chapterIndex = index,
                chapterTitle = chapters.getOrNull(index)?.title.orEmpty(),
                loading = true,
                preparing = true,
                playing = false,
                playWhenReady = autoPlay,
                error = null,
                notice = null,
                positionMs = startSec.coerceAtLeast(0) * 1_000L,
                durationMs = 0,
            )
        }
        // 章节切换后先把「新章起点」落盘：即使随后进程被回收，也不会回到上一章。
        persistCheckpoint()
        // 新章准备期间停止旧音轨，避免旧位置和结束事件污染新章。
        liveController()?.pause()
        DiagnosticsLog.i("ReaderAudio", "准备音轨 book=${currentBook.id} index=$index")
        loadJob = scope.launch {
            try {
                val content = providedContent ?: run {
                    val result = repository.content(currentBook.id, index)
                    if (!playback.isCurrent(request)) return@launch
                    when (result) {
                        is ApiResult.Success -> result.value
                        else -> {
                            val cached = withContext(Dispatchers.IO) {
                                currentScope?.let { cache.read(it, currentBook.id, index) }
                            }
                            if (!playback.isCurrent(request)) return@launch
                            if (cached != null && result != ApiResult.Stale) {
                                _state.update { it.copy(notice = "已加载离线缓存") }
                                cached
                            } else {
                                val message = when (result) {
                                    is ApiResult.Failure -> result.message.ifBlank { "加载音轨失败" }
                                    is ApiResult.NetworkError -> "网络不可用，请稍后重试"
                                    else -> "请求已失效，请重试"
                                }
                                failChapterLoad(request, index, message)
                                return@launch
                            }
                        }
                    }
                }
                if (!playback.isCurrent(request)) return@launch
                if (content.type != "audio") {
                    failChapterLoad(request, index, "本章不是音频内容")
                    return@launch
                }
                val urls = content.tracks.orEmpty()
                    .mapNotNull { ReaderUrls.resolve(currentOrigin, it) }
                if (urls.isEmpty()) {
                    failChapterLoad(request, index, "本章没有可播放的音轨")
                    return@launch
                }
                logStartupTiming("音轨就绪")
                val title = chapters.getOrNull(index)?.title.orEmpty()
                val startMs = ReaderAudioTuning.startPositionMs(
                    openCreditsSeconds = ReaderAudioTuning.clampCreditsSeconds(currentBook.openCredits),
                    requestedMs = startSec.coerceAtLeast(0) * 1_000L,
                    durationMs = 0L,
                )
                ensureController { controller ->
                    // 请求编号同时保护同章重试、换书和停止，不只比较章节号。
                    if (!playback.isCurrent(request) || !playback.preparing) return@ensureController
                    val metadata = MediaMetadata.Builder()
                        .setTitle(currentBook.name)
                        .setArtist(title.ifBlank { currentBook.name })
                        // 系统媒体卡片与锁屏的封面；没有可读地址时保持占位图。
                        .setArtworkUri(artworkUri(currentOrigin, currentBook.coverUrl))
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
                    controller.playWhenReady = playback.playWhenReady
                    if (playback.playWhenReady) startedPlayback = true
                    playback.mediaInstalled(request)
                    advanceGuard.mediaInstalled()
                    syncPlaybackState()
                    logStartupTiming("媒体已装载")
                    startTicker()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagnosticsLog.e("ReaderAudio", "音轨准备失败 book=${currentBook.id} index=$index", error)
                failChapterLoad(request, index, "音频准备失败，请点击播放重试")
            }
        }
    }

    /**
     * 音轨加载失败：解除换章锁，并把这一章记为已处理。
     * 否则播放器仍停在旧章片尾，轮询会每秒再次判定「本章结束」继续往后跳。
     */
    private fun failChapterLoad(request: Long, index: Int, message: String) {
        if (!playback.fail(request)) return
        advanceGuard.loadFailed(index)
        tickerJob?.cancel()
        startupStartedAtNs = 0L
        liveController()?.pause()
        _state.update {
            it.copy(
                loading = false,
                preparing = false,
                playing = false,
                playWhenReady = false,
                error = message,
            )
        }
    }

    private fun publishPlaybackState() {
        _state.update {
            it.copy(
                preparing = playback.preparing,
                loading = playback.loading,
                playing = playback.playing,
                playWhenReady = playback.playWhenReady,
            )
        }
    }

    private fun syncPlaybackState() {
        val player = controller?.takeIf { it.isConnected }
        if (player != null) {
            playback.playerChanged(
                buffering = player.playbackState == Player.STATE_BUFFERING ||
                    (player.playbackState == Player.STATE_IDLE && _state.value.active),
                playing = player.isPlaying,
                playWhenReady = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            )
        }
        publishPlaybackState()
        if (playback.playing) {
            startedPlayback = true
            if (startupStartedAtNs != 0L) {
                logStartupTiming("实际起播")
                startupStartedAtNs = 0L
            }
        }
    }

    private fun logStartupTiming(stage: String) {
        if (startupStartedAtNs == 0L) return
        val elapsedMs = (System.nanoTime() - startupStartedAtNs) / 1_000_000
        val current = _state.value
        DiagnosticsLog.i(
            "ReaderAudio",
            "$stage book=${current.bookId} index=${current.chapterIndex} elapsed=${elapsedMs}ms",
        )
    }

    private fun onChapterFinished() {
        val finishedIndex = _state.value.chapterIndex
        // 加载下一章期间或同一章重复触发时直接忽略。
        if (!advanceGuard.tryFinish(finishedIndex)) return
        DiagnosticsLog.i("ReaderAudio", "本章结束 index=$finishedIndex")
        persistCheckpoint(synchronous = true)
        if (startedPlayback) {
            val title = chapters.getOrNull(finishedIndex)?.title.orEmpty()
            val position = currentPositionMs()
            scope.launch { reportProgressFor(finishedIndex, title, position) }
        }
        if (stopAtChapterEnd) {
            stopAtChapterEnd = false
            liveController()?.pause()
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
        val nextIndex = nextReadable(chapters, finishedIndex)
        if (nextIndex == null) {
            tickerJob?.cancel()
            _state.update { it.copy(playing = false, notice = "已播放完最后一章") }
            return
        }
        DiagnosticsLog.i("ReaderAudio", "自动播放下一章 index=$finishedIndex → $nextIndex")
        loadChapter(nextIndex, 0, autoPlay = true)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(1_000)
                val player = controller ?: continue
                if (playback.preparing || playback.failed) continue
                syncPlaybackState()
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
                    // 先落本地再上报服务端：网络失败时本地位置仍然可用。
                    persistCheckpoint()
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
        val current = _state.value
        reportProgressFor(current.chapterIndex, current.chapterTitle, positionMs)
    }

    /**
     * 按指定章节上报进度。换章瞬间 [state] 里的章节号已经变成新章，
     * 这里显式带上旧章信息，避免把没播过的新章写成 0 秒。
     */
    private suspend fun reportProgressFor(
        chapterIndex: Int,
        chapterTitle: String,
        positionMs: Long,
    ) {
        val currentBook = book ?: return
        val repository = repo ?: return
        if (chapterIndex < 0) return
        repository.saveProgress(
            bookId = currentBook.id,
            chapterIndex = chapterIndex,
            pos = (positionMs / 1000).toInt().coerceAtLeast(0),
            chapterTitle = chapterTitle,
        )
    }

    private fun ensureController(onReady: (MediaController) -> Unit) {
        controller?.let {
            if (it.isConnected) {
                onReady(it)
                return
            }
            // 断连的控制器不能继续接收命令，释放后重建。
            controller = null
            runCatching { it.release() }
        }
        connectWaiters += onReady
        if (connecting) return
        connecting = true
        val version = ++connectionVersion
        val token = SessionToken(
            context,
            ComponentName(context, ReaderAudioService::class.java),
        )
        val future = MediaController.Builder(context, token)
            .setListener(controllerListener)
            .buildAsync()
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull()
            if (version != connectionVersion) {
                connected?.release()
                return@addListener
            }
            connecting = false
            if (connected == null) {
                connectWaiters.clear()
                failChapterLoad(playback.requestId, _state.value.chapterIndex, "无法连接后台播放服务，请重试")
                return@addListener
            }
            connected.addListener(listener)
            controller = connected
            _state.update { it.copy(connected = true) }
            val waiters = connectWaiters.toList()
            connectWaiters.clear()
            waiters.forEach { waiter ->
                try {
                    waiter(connected)
                } catch (error: Exception) {
                    DiagnosticsLog.e("ReaderAudio", "安装音轨失败", error)
                    failChapterLoad(playback.requestId, _state.value.chapterIndex, "音频准备失败，请重试")
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** 相对封面路径先补上服务器来源；只取媒体库位图加载器能读取的协议。 */
    private fun artworkUri(origin: String, coverUrl: String?): Uri? {
        val resolved = ReaderUrls.resolve(origin, coverUrl) ?: return null
        val lower = resolved.lowercase()
        return if (lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("file://") ||
            lower.startsWith("content://")
        ) {
            Uri.parse(resolved)
        } else {
            null
        }
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

/**
 * 听书换章守卫。
 *
 * 「本章播完」有两个来源：播放器真正走到结尾，以及定时轮询里的片尾跳过判断。
 * 判定之后要异步去拉下一章音轨，这期间播放器还停在旧章片尾，轮询每秒都会再判一次结束。
 * 守卫负责把「加载期间」和「同一章」这两种重复都挡掉，避免一次结束连跳好几章。
 */
internal class ChapterAdvanceGuard {

    /** 正在加载下一章音轨；加载完成或失败前不允许再次换章。 */
    var loading: Boolean = false
        private set

    /** 已判定播完的章节号，-1 表示当前章还没结束过。 */
    var finishedIndex: Int = -1
        private set

    /** 开始加载某一章的音轨。 */
    fun beginLoad() {
        loading = true
        finishedIndex = -1
    }

    /** 新音轨已经换到播放器上，可以重新接受「本章结束」。 */
    fun mediaInstalled() {
        loading = false
        finishedIndex = -1
    }

    /**
     * 音轨加载失败。解除加载锁，同时把这一章记为已处理：
     * 播放器还停在旧章片尾，若不标记会继续往后跳，把后面的章节一起跳掉。
     */
    fun loadFailed(index: Int) {
        loading = false
        finishedIndex = index
    }

    /** 停止播放时复位。 */
    fun reset() {
        loading = false
        finishedIndex = -1
    }

    /** 某一章是否可以按「播完」处理；返回 true 表示已记录，调用方可以换章。 */
    fun tryFinish(index: Int): Boolean {
        if (loading) return false
        if (finishedIndex == index) return false
        finishedIndex = index
        return true
    }
}
