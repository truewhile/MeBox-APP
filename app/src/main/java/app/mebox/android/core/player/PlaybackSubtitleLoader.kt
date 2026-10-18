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

    /**
     * 播放页正在恢复用户的手动字幕选择（或用户已明确关闭字幕）。
     *
     * LibVLC 的外挂字幕在 Playing 后才下载；恢复期间不能让晚到的自动选择
     * 覆盖用户意图。与 [onUserSelection] 不同，这个标记跨内部媒体重载保持。
     */
    private var holdAutoSelection = false
    private val files = mutableListOf<File>()

    /** 必须先停止旧媒体，再清理其仍可能在使用的字幕文件。 */
    fun prepare(media: EngineMedia) {
        close()
        this.media = media
        userSelected = holdAutoSelection
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

    /**
     * 恢复用户字幕选择期间禁止自动选中，直到内核实例释放。
     *
     * 调用后 [prepare] 也会重置为“已选择”，确保换地址、换软解等内部重载
     * 不会把用户选的字幕换回偏好默认项。
     */
    fun holdAutoSelection() {
        holdAutoSelection = true
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
