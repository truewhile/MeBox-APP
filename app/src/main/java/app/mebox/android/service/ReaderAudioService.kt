package app.mebox.android.service

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 听书后台播放服务。
 *
 * 只负责持有 ExoPlayer 与媒体会话：通知栏、锁屏、耳机按键和音频焦点由
 * MediaSessionService 统一处理。章节切换、进度上报与定时关闭由
 * [app.mebox.android.core.reader.ReaderAudioEngine] 控制。
 */
class ReaderAudioService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val exoPlayer = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            // 拔耳机时自动暂停，避免外放继续播放。
            setHandleAudioBecomingNoisy(true)
        }
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setId(SESSION_ID)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player ?: return
        // 用户从最近任务划掉 App 时：正在播放就继续保持后台播放；
        // 已暂停则释放服务，避免留下空的通知。
        if (!current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    companion object {
        const val SESSION_ID = "mebox-reader-audio"
    }
}
