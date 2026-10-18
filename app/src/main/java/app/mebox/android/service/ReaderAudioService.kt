package app.mebox.android.service

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.mebox.android.MeBoxApplication
import app.mebox.android.core.network.APP_USER_AGENT
import app.mebox.android.core.reader.ReaderAudioEngine
import app.mebox.android.core.reader.createReaderAudioHttpClient
import app.mebox.android.core.storage.AppPreferences

/**
 * 听书后台播放服务。
 *
 * 只负责持有 ExoPlayer 与媒体会话：通知栏、锁屏、耳机按键由
 * MediaSessionService 统一处理；音频焦点策略由「与其他应用同时播放」
 * 偏好决定。章节切换、进度上报与定时关闭由
 * [app.mebox.android.core.reader.ReaderAudioEngine] 控制。
 */
@OptIn(UnstableApi::class)
class ReaderAudioService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var mixWithOtherApps = true

    override fun onCreate() {
        super.onCreate()
        val sharedHttpClient = (application as MeBoxApplication).container.httpClient
        mixWithOtherApps = AppPreferences(this).audioMixWithOtherApps
        val httpDataSource = OkHttpDataSource.Factory(createReaderAudioHttpClient(sharedHttpClient))
            .setUserAgent(APP_USER_AGENT)
        // API 与音轨通常同源，复用连接避免起播时再次 DNS/TCP/TLS 握手。
        // 外层 DefaultDataSource 保留 file:// 和 content:// 离线音轨支持。
        val dataSource = DefaultDataSource.Factory(this, httpDataSource)
        val loadControl = DefaultLoadControl.Builder()
            // 首次 750ms 即可起播，卡顿后多缓冲 2 秒，仍保留 50 秒后台缓冲窗口。
            .setBufferDurationsMs(50_000, 50_000, 750, 2_000)
            .build()
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .build().apply {
            setAudioAttributes(readerAudioAttributes(), !mixWithOtherApps)
            // 拔耳机时自动暂停，避免外放继续播放。
            setHandleAudioBecomingNoisy(true)
            // 播放期间持有 CPU / Wi-Fi 锁，避免熄屏或后台时被系统冻结；
            // 这是前台媒体服务之外的必要补充（需 WAKE_LOCK 权限）。
            setWakeMode(C.WAKE_MODE_NETWORK)
        }
        player = exoPlayer
        // 播放器里始终只装着当前章的音轨，媒体会话因此认为「没有下一项」，
        // 系统媒体卡片和锁屏就不显示下一章按钮。这里包一层播放器，把
        // 「本书还有上一章/下一章」翻译成播放命令，并把系统、耳机、通知栏
        // 发出的跳章命令交回声音引擎，而不是让 ExoPlayer 在播放列表里找。
        val sessionPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                val builder = Player.Commands.Builder().addAll(super.getAvailableCommands())
                val engine = readerAudio()
                if (engine?.hasNextChapter() == true) {
                    builder.addAll(
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    )
                }
                if (engine?.hasPreviousChapter() == true) {
                    builder.addAll(
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    )
                }
                return builder.build()
            }

            override fun seekToNext() {
                if (!jumpToChapter(next = true)) super.seekToNext()
            }

            override fun seekToNextMediaItem() {
                if (!jumpToChapter(next = true)) super.seekToNextMediaItem()
            }

            override fun seekToPrevious() {
                if (!jumpToChapter(next = false)) super.seekToPrevious()
            }

            override fun seekToPreviousMediaItem() {
                if (!jumpToChapter(next = false)) super.seekToPreviousMediaItem()
            }
        }
        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setId(SESSION_ID)
            .build()
    }

    /** 系统跳章命令转交声音引擎；返回 false 表示没有可跳的章节，交回播放器默认行为。 */
    private fun jumpToChapter(next: Boolean): Boolean {
        val engine = readerAudio() ?: return false
        return if (next) {
            if (!engine.hasNextChapter()) {
                false
            } else {
                engine.next()
                true
            }
        } else {
            if (!engine.hasPreviousChapter()) {
                false
            } else {
                engine.previous()
                true
            }
        }
    }

    private fun readerAudio(): ReaderAudioEngine? =
        (application as? MeBoxApplication)?.container?.readerAudio

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_APPLY_AUDIO_MIX) {
            mixWithOtherApps = AppPreferences(this).audioMixWithOtherApps
            player?.setAudioAttributes(readerAudioAttributes(), !mixWithOtherApps)
        }
        // 交回 MediaSessionService 维持 START_STICKY：系统回收后仍有机会重建服务。
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 划掉任务前先落一次本地进度，之后即使服务被回收也能续播。
        persistCheckpoint()
        val current = player ?: return
        // 用户从最近任务划掉 App 时：正在播放就继续保持后台播放；
        // 已暂停则释放服务，避免留下空的通知。
        if (!current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistCheckpoint()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    private fun readerAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

    private fun persistCheckpoint() {
        runCatching {
            (application as? MeBoxApplication)
                ?.container
                ?.readerAudio
                ?.persistCheckpoint(synchronous = true)
        }
    }

    companion object {
        const val SESSION_ID = "mebox-reader-audio"

        /** 由 [app.mebox.android.core.reader.ReaderAudioEngine] 发送，切换音频焦点策略。 */
        const val ACTION_APPLY_AUDIO_MIX = "app.mebox.android.action.APPLY_AUDIO_MIX"
    }
}
