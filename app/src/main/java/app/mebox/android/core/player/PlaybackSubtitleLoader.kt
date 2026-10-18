package app.mebox.android.core.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 外挂字幕与视频启动分离：Playing 后才下载，VLC 只接收下载好的本地文件。
 * 调用及回调使用传入作用域的同一线程（播放器传主线程）。换集会取消请求、清理文件。
 */
internal class PlaybackSubtitleLoader(
    private val scope: CoroutineScope,
    private val download: suspend (EngineSubtitle) -> File,
    private val attach: (EngineSubtitle, File, select: Boolean) -> Boolean,
    private val onFailure: (EngineSubtitle, Exception) -> Unit = { _, _ -> },
) {
    private var media: EngineMedia? = null
    private var job: Job? = null
    private var userSelected = false
    private val files = mutableListOf<File>()

    /** 必须先停止旧媒体，再清理其仍可能在使用的字幕文件。 */
    fun prepare(media: EngineMedia) {
        close()
        this.media = media
        userSelected = false
    }

    fun onPlaying() {
        val current = media ?: return
        if (job != null) return
        val preference = current.subtitlePreference
        val preferred = when {
            !preference.autoLoad -> null
            preference.priority == SubtitlePriority.FOLLOW_SOURCE ->
                current.subtitles.firstOrNull { it.assUrl != null } ?: current.subtitles.firstOrNull()
            else -> SubtitleSelector.choose(current.subtitles, preference)
        }
        // 优先字幕先下载；大弹幕 ASS 等其它字幕不会挡住正常对白字幕。
        val ordered = listOfNotNull(preferred) + current.subtitles.filter { it != preferred }
        job = scope.launch {
            for (subtitle in ordered) {
                try {
                    val file = download(subtitle)
                    files += file
                    if (!attach(subtitle, file, subtitle == preferred && !userSelected)) {
                        files.remove(file)
                        file.delete()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onFailure(subtitle, error)
                }
            }
        }
    }

    /** 下载期间用户切换/关闭字幕后，迟到的自动选择不能覆盖用户操作。 */
    fun onUserSelection() {
        userSelected = true
    }

    fun close() {
        job?.cancel()
        job = null
        media = null
        files.forEach { it.delete() }
        files.clear()
    }
}
