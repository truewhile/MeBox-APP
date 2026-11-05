package app.mebox.android.core.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.mebox.android.core.log.DiagnosticsLog
import app.mebox.android.core.network.APP_USER_AGENT
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * 保留同一个 Media3 会话：原始音频/HLS 先由 ExoPlayer 直接播放，确实不支持的
 * 容器/编码才启用 App 内的 LibVLC/FFmpeg 软解。不编码另一份文件、不调用服务端转码。
 * 普通远端音频经 OkHttp 下载到私有文件再交 VLC，避免丢失书源 Cookie/Referer。
 */
@OptIn(UnstableApi::class)
internal class ReaderAudioNativePlayer(
    private val context: Context,
    private val exo: ExoPlayer,
    private val localSource: ReaderAudioLocalSource,
    initialMixWithOtherApps: Boolean,
) : ForwardingSimpleBasePlayer(exo) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lib: LibVLC? = null
    private var vlc: MediaPlayer? = null
    private var localMode = false
    private var fallbackScheduled = false
    private var fallbackArmed = false
    private var released = false
    private var version = 0L
    private var prepareJob: Job? = null
    private var watchdog: Job? = null
    private var items = emptyList<MediaItem>()
    private var itemIndex = 0
    private var position = 0L
    private var duration = C.TIME_UNSET
    private var state = Player.STATE_IDLE
    private var error: PlaybackException? = null
    private var wanted = false
    private var speed = PlaybackParameters.DEFAULT
    private var attributes = AudioAttributes.DEFAULT
    private var volume = 1f
    private var opened = false
    private var mix = initialMixWithOtherApps
    private var resumeOnFocus = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeBox:ReaderLocalAudio").apply { setReferenceCounted(false) }
    @Suppress("DEPRECATION")
    private val wifi = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MeBox:ReaderLocalAudio").apply { setReferenceCounted(false) }
    private var focusHeld = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener({ change ->
            if (!localMode || released) return@setOnAudioFocusChangeListener
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeOnFocus = wanted && change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    wanted = false
                    vlc?.pause()
                    updateLocks()
                    invalidateState()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (resumeOnFocus) { resumeOnFocus = false; wanted = true; vlc?.play() }
                    updateLocks()
                    invalidateState()
                }
            }
        }, main).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (localMode && intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                wanted = false
                resumeOnFocus = false
                vlc?.pause()
                updateLocks()
                invalidateState()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun getState(): State {
        val base = super.getState()
        if (!localMode) {
            val failure = base.playerError
            if (!released && fallbackArmed && items.isNotEmpty() && !base.timeline.isEmpty && failure != null &&
                ReaderAudioDecodePolicy.shouldUseLocalDecoder(failure.errorCode)) {
                if (!fallbackScheduled) {
                    fallbackScheduled = true
                    val token = version
                    // 不在 getState 里更改底层播放器；先隐藏可本地恢复的错误，再异步切换。
                    main.post { if (!released && token == version) beginLocalDecode() }
                }
                return base.buildUpon().setPlayerError(null).setPlaybackState(Player.STATE_BUFFERING).setIsLoading(true).build()
            }
            return base
        }
        val playlist = items.mapIndexed { index, item ->
            MediaItemData.Builder("local:$version:$index").setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata).setIsSeekable(true)
                .setDurationUs(if (index == itemIndex && duration > 0) duration * 1_000 else C.TIME_UNSET).build()
        }
        return base.buildUpon()
            .setPlaylist(playlist)
            .setAvailableCommands(Player.Commands.Builder().addAll(base.availableCommands).apply {
                if (items.isNotEmpty()) addAll(
                    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SET_SPEED_AND_PITCH,
                )
            }.build())
            .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else itemIndex)
            .setPlayerError(error)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else state)
            .setIsLoading(state == Player.STATE_BUFFERING)
            .setPlayWhenReady(wanted, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
            .setPlaybackParameters(speed).setAudioAttributes(attributes).setVolume(volume)
            .setContentPositionMs(PositionSupplier { currentLocalPosition() })
            .setContentBufferedPositionMs(PositionSupplier { currentLocalPosition() })
            .setTotalBufferedDurationMs(PositionSupplier.ZERO)
            .build()
    }

    private fun currentLocalPosition(): Long =
        if (opened) vlc?.time?.coerceAtLeast(0) ?: position else position

    private fun beginLocalDecode() {
        if (items.isEmpty()) return
        // 最后一次从 Exo 快照列表，避免其自动切轨/列表编辑后仍引用旧索引。
        items = (0 until exo.mediaItemCount).map(exo::getMediaItemAt)
        if (items.isEmpty()) return
        wanted = exo.playWhenReady
        speed = exo.playbackParameters
        attributes = exo.audioAttributes
        volume = exo.volume
        itemIndex = exo.currentMediaItemIndex.coerceIn(items.indices)
        position = exo.currentPosition.takeIf { it > 0 } ?: position
        localMode = true
        exo.stop() // 释放系统解码器/焦点，但保留原始播放列表作为会话命令来源。
        DiagnosticsLog.i("ReaderAudio", "系统播放器不支持该音频，切换 App 本地 FFmpeg 解码")
        loadLocalItem()
    }

    private fun loadLocalItem() {
        prepareJob?.cancel()
        watchdog?.cancel()
        val token = ++version
        opened = false
        error = null
        duration = C.TIME_UNSET
        state = Player.STATE_BUFFERING
        vlc?.setEventListener(null)
        vlc?.stop()
        updateLocks()
        invalidateState()
        val uri = items.getOrNull(itemIndex)?.localConfiguration?.uri ?: return failLocal("本章没有可播放地址")
        prepareJob = scope.launch {
            try {
                val source = localSource.prepare(uri.toString())
                if (token != version || !localMode) return@launch
                installVlc(source, token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: UnsupportedOperationException) {
                // HLS 必须流式读取，不能把清单下载成一段音频。带书源凭据时不交给 VLC 网络栈。
                if (token == version && ReaderMediaHeaders.headersFor(uri.toString()).isEmpty()) {
                    installVlc(uri.toString(), token)
                } else if (token == version) failLocal("此 HLS 无法在本机解码，请切换书源的媒体直连设置后重试")
            } catch (failure: Exception) {
                if (token == version) failLocal("本机解码准备失败，请检查网络后重试")
            }
        }
    }

    private fun installVlc(source: String, token: Long) {
        val library = lib ?: LibVLC(context.applicationContext,
            arrayListOf("--no-video", "--audio-time-stretch", "--network-caching=2000", "--ipv4")).also { lib = it }
        val player = vlc ?: MediaPlayer(library).also { vlc = it }
        player.setEventListener { event ->
            main.post {
                if (released || !localMode || token != version) return@post
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        opened = true
                        watchdog?.cancel()
                        duration = player.length.takeIf { it > 0 } ?: C.TIME_UNSET
                        state = Player.STATE_READY
                        player.setRate(speed.speed)
                        if (position > 0) { player.setTime(position); position = 0 }
                        if (!wanted) { player.setVolume(0); player.pause() }
                        else player.setVolume((volume * 100).toInt())
                    }
                    MediaPlayer.Event.Paused -> state = Player.STATE_READY
                    MediaPlayer.Event.Buffering -> state = if (event.buffering < 100) Player.STATE_BUFFERING else Player.STATE_READY
                    MediaPlayer.Event.LengthChanged -> duration = player.length.takeIf { it > 0 } ?: C.TIME_UNSET
                    MediaPlayer.Event.EndReached -> {
                        if (!opened || (duration <= 0 && player.audioTracksCount == 0)) {
                            failLocal("此音源没有可解码的音频内容")
                            return@post
                        }
                        if (itemIndex < items.lastIndex) { itemIndex++; position = 0; loadLocalItem(); return@post }
                        state = Player.STATE_ENDED
                        wanted = false
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        val original = items.getOrNull(itemIndex)?.localConfiguration?.uri?.toString()
                        if (original != null) scope.launch(Dispatchers.IO) { runCatching { localSource.invalidate(original) } }
                        failLocal("App 本地解码也无法播放此音频，请更换音源")
                        return@post
                    }
                }
                updateLocks()
                invalidateState()
            }
        }
        val parsed = Uri.parse(source)
        // java.io.File.toURI() 产出 file:/，LibVLC 只接受规范的 file:/// URL。
        val normalized = if (parsed.scheme == "file") Uri.fromFile(java.io.File(checkNotNull(parsed.path))) else parsed
        val media = Media(library, normalized)
        try {
            media.setHWDecoderEnabled(false, false)
            media.addOption(":no-video")
            media.addOption(":http-user-agent=$APP_USER_AGENT")
            // 使用同一个用户意图：暂停中准备软解时不允许泄漏声音。
            player.setVolume(0)
            player.media = media
            player.play()
        } finally { media.release() }
        watchdog = scope.launch {
            delay(30_000)
            if (token == version && !opened) failLocal("本机解码启动超时，请重试或更换音源")
        }
    }

    private fun failLocal(message: String) {
        position = currentLocalPosition()
        version++
        prepareJob?.cancel()
        watchdog?.cancel()
        vlc?.setEventListener(null)
        vlc?.stop()
        opened = false
        state = Player.STATE_IDLE
        wanted = false
        resumeOnFocus = false
        error = PlaybackException(message, null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        updateLocks()
        invalidateState()
    }

    private fun updateLocks() {
        val keep = localMode && wanted && state != Player.STATE_IDLE && state != Player.STATE_ENDED
        if (keep && !wake.isHeld) wake.acquire(60 * 60 * 1_000L)
        if (!keep && wake.isHeld) wake.release()
        if (keep && !wifi.isHeld) wifi.acquire()
        if (!keep && wifi.isHeld) wifi.release()
        if (keep && !mix && !focusHeld) {
            focusHeld = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!focusHeld) { wanted = false; vlc?.pause(); if (wake.isHeld) wake.release(); if (wifi.isHeld) wifi.release() }
        }
        if (((!keep && !resumeOnFocus) || mix) && focusHeld) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            focusHeld = false
        }
    }

    private fun resetLocal() {
        version++
        prepareJob?.cancel()
        watchdog?.cancel()
        localMode = false
        resumeOnFocus = false
        fallbackScheduled = false
        fallbackArmed = false
        vlc?.setEventListener(null)
        vlc?.stop()
        error = null
        updateLocks()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        resetLocal()
        items = mediaItems.toList()
        itemIndex = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        position = startPositionMs.coerceAtLeast(0)
        return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }

    private fun editPlaylist(updated: List<MediaItem>, startIndex: Int): ListenableFuture<*> {
        val wasLocal = localMode
        if (!wasLocal) return handleSetMediaItems(updated.toMutableList(), startIndex, C.TIME_UNSET)
        val savedPosition = currentLocalPosition()
        val savedIntent = wanted
        resetLocal()
        items = updated
        itemIndex = if (items.isEmpty()) 0 else startIndex.coerceIn(items.indices)
        exo.setMediaItems(items, itemIndex, savedPosition)
        localMode = true
        wanted = savedIntent
        position = savedPosition
        if (items.isEmpty()) { state = Player.STATE_IDLE; updateLocks(); invalidateState() }
        else loadLocalItem()
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        if (!localMode) return super.handleAddMediaItems(index, mediaItems)
        val updated = items.toMutableList().apply { addAll(index, mediaItems) }
        return editPlaylist(updated, if (index <= itemIndex) itemIndex + mediaItems.size else itemIndex)
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        if (!localMode) return super.handleMoveMediaItems(fromIndex, toIndex, newIndex)
        val tagged = items.mapIndexed { index, item -> index to item }.toMutableList()
        val moved = tagged.subList(fromIndex, toIndex).toList()
        tagged.subList(fromIndex, toIndex).clear()
        tagged.addAll(newIndex.coerceIn(0, tagged.size), moved)
        return editPlaylist(tagged.map { it.second }, tagged.indexOfFirst { it.first == itemIndex }.coerceAtLeast(0))
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        if (!localMode) return super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
        val updated = items.toMutableList().apply { subList(fromIndex, toIndex).clear(); addAll(fromIndex, mediaItems) }
        val nextIndex = when {
            itemIndex < fromIndex -> itemIndex
            itemIndex >= toIndex -> itemIndex + mediaItems.size - (toIndex - fromIndex)
            else -> fromIndex
        }
        return editPlaylist(updated, nextIndex)
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        if (localMode && (fromIndex != 0 || toIndex != items.size)) {
            val updated = items.filterIndexed { index, _ -> index !in fromIndex until toIndex }
            val nextIndex = when {
                itemIndex < fromIndex -> itemIndex
                itemIndex >= toIndex -> itemIndex - (toIndex - fromIndex)
                else -> fromIndex
            }
            return editPlaylist(updated, nextIndex)
        }
        resetLocal()
        items = items.filterIndexed { index, _ -> index !in fromIndex until toIndex }
        return super.handleRemoveMediaItems(fromIndex, toIndex)
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (!localMode) {
            val result = super.handlePrepare()
            fallbackArmed = true
            return result
        }
        loadLocalItem()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!localMode) return super.handleSetPlayWhenReady(playWhenReady)
        wanted = playWhenReady
        resumeOnFocus = false
        updateLocks()
        if (wanted && state == Player.STATE_ENDED) { position = 0; loadLocalItem() }
        else if (opened) { if (wanted) vlc?.play() else vlc?.pause() }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (!localMode) return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        if (items.isEmpty()) return Futures.immediateVoidFuture()
        val nextIndex = mediaItemIndex.coerceIn(items.indices)
        position = positionMs.coerceAtLeast(0)
        if (itemIndex != nextIndex || state == Player.STATE_ENDED) { itemIndex = nextIndex; loadLocalItem() }
        else if (opened) { vlc?.setTime(position); position = 0 }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters
        if (!localMode) return super.handleSetPlaybackParameters(playbackParameters)
        vlc?.setRate(speed.speed)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        this.volume = volume
        if (!localMode) return super.handleSetVolume(volume)
        vlc?.setVolume((volume * 100).toInt())
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean): ListenableFuture<*> {
        attributes = audioAttributes
        mix = !handleAudioFocus
        exo.setAudioAttributes(audioAttributes, handleAudioFocus)
        updateLocks()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        if (!localMode) return super.handleStop()
        position = currentLocalPosition()
        version++
        prepareJob?.cancel()
        watchdog?.cancel()
        vlc?.setEventListener(null)
        vlc?.stop()
        resumeOnFocus = false
        opened = false
        state = Player.STATE_IDLE
        updateLocks()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        resetLocal()
        scope.cancel()
        context.unregisterReceiver(noisy)
        vlc?.release()
        lib?.release()
        vlc = null
        lib = null
        return super.handleRelease()
    }
}
