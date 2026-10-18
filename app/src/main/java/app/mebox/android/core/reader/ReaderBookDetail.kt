package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderTocChapterDto

/**
 * 书籍详情页（仿 legado BookInfoActivity）的纯逻辑。
 *
 * 详情页展示的目录是现抓的，卷标题和没有地址的条目不能进入正文，
 * 「开始阅读」是否可用、点目录时能跳哪一章都由这里判断。
 */
object ReaderBookDetail {

    /** 目录里第一个可读章节（跳过卷标题与没有地址的条目）。 */
    fun firstReadable(chapters: List<ReaderTocChapterDto>): ReaderTocChapterDto? =
        chapters.firstOrNull { !it.isVolume && it.url.isNotBlank() }
}
