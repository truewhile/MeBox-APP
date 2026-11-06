package app.mebox.android.feature.reader

import app.mebox.android.core.network.ReaderBrowserPageDto

/** 已关闭的待办可能仍留在服务端或在途响应中，不能据此再次弹窗。生命周期跟随 ViewModel。 */
internal class ReaderBrowserPages {
    private val finishedIds = mutableSetOf<String>()

    fun pending(pages: List<ReaderBrowserPageDto>, sourceId: String): List<ReaderBrowserPageDto> =
        pages.filter {
            it.id.isNotBlank() && it.id !in finishedIds &&
                (it.sourceId.isBlank() || it.sourceId == sourceId)
        }

    fun newest(
        active: ReaderBrowserPageDto?,
        pending: List<ReaderBrowserPageDto>,
    ): ReaderBrowserPageDto? {
        val newest = pending.maxByOrNull { it.seq } ?: return active
        return when {
            active == null -> newest
            newest.id == active.id -> active
            newest.seq > active.seq -> newest
            else -> active
        }
    }

    /** 已采用最新页后，关闭时也淘汰被它取代的旧页，避免轮询又退回旧页面。 */
    fun finish(page: ReaderBrowserPageDto, pending: List<ReaderBrowserPageDto>) {
        finishedIds += page.id
        finishedIds += pending.filter { it.seq <= page.seq }.map { it.id }
    }
}
