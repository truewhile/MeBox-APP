package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderChapterDto

/**
 * 听书章节跳转的边界规则。
 *
 * 听书引擎的章节表与章节号理论上总是同一本书，但打开漫画、文本或切换书籍时，
 * 两者可能短暂来自不同的书。这里把「章节号越界」统一当作没有可跳转的章节，
 * 避免用旧书的章节号去索引新书的目录。
 */
internal object ReaderChapterNavigation {

    /** 从 [from] 之后找第一个非卷章节；[from] 越界或已在末尾时返回 null。 */
    fun nextReadable(chapters: List<ReaderChapterDto>, from: Int): Int? {
        if (chapters.isEmpty() || from >= chapters.lastIndex) return null
        for (index in (from + 1).coerceAtLeast(0)..chapters.lastIndex) {
            if (!chapters[index].isVolume) return index
        }
        return null
    }

    /** 从 [from] 之前找第一个非卷章节；[from] 越界时返回 null。 */
    fun previousReadable(chapters: List<ReaderChapterDto>, from: Int): Int? {
        if (chapters.isEmpty() || from > chapters.lastIndex) return null
        for (index in (from - 1).coerceAtMost(chapters.lastIndex) downTo 0) {
            if (!chapters[index].isVolume) return index
        }
        return null
    }
}
