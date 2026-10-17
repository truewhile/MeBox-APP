package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderBookGroupDto
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderShelfTest {

    private fun book(
        id: String,
        name: String,
        author: String = "作者",
        durChapterTime: Long = 0,
        latestChapterTime: Long = 0,
        durChapterIndex: Int = 0,
        total: Int = 10,
        order: Int = 0,
    ) = ReaderBookDto(
        id = id,
        name = name,
        author = author,
        durChapterTime = durChapterTime,
        latestChapterTime = latestChapterTime,
        durChapterIndex = durChapterIndex,
        totalChapterNum = total,
        order = order,
    )

    private val group = ReaderBookGroupDto(name = "科幻", bookIds = listOf("c", "a"))

    @Test
    fun `recent sort puts the last read book first`() {
        val books = listOf(
            book("a", "A", durChapterTime = 100, latestChapterTime = 300),
            book("b", "B", durChapterTime = 400, latestChapterTime = 100),
            book("c", "C", durChapterTime = 0, latestChapterTime = 200),
        )

        val sorted = ReaderShelf.visible(books, emptyList(), null, "recent")

        assertEquals(listOf("b", "a", "c"), sorted.map { it.id })
    }

    @Test
    fun `update sort uses latest chapter time`() {
        val books = listOf(
            book("a", "A", durChapterTime = 100, latestChapterTime = 300),
            book("b", "B", durChapterTime = 400, latestChapterTime = 100),
            book("c", "C", durChapterTime = 0, latestChapterTime = 200),
        )

        val sorted = ReaderShelf.visible(books, emptyList(), null, "update")

        assertEquals(listOf("a", "c", "b"), sorted.map { it.id })
    }

    @Test
    fun `group filter keeps group order and drops unknown books`() {
        val books = listOf(
            book("a", "A"),
            book("b", "B"),
            book("c", "C"),
        )
        val groups = listOf(group, ReaderBookGroupDto("其他", listOf("missing")))

        val sorted = ReaderShelf.visible(books, groups, "科幻", "manual")

        assertEquals(listOf("c", "a"), sorted.map { it.id })
        assertEquals(emptyList<String>(), ReaderShelf.visible(books, groups, "其他", "recent").map { it.id })
        assertEquals(emptyList<String>(), ReaderShelf.visible(books, groups, "不存在", "recent").map { it.id })
    }

    @Test
    fun `manual sort uses order`() {
        val books = listOf(
            book("a", "A", order = 2),
            book("b", "B", order = 1),
        )
        assertEquals(listOf("b", "a"), ReaderShelf.visible(books, emptyList(), null, "manual").map { it.id })
    }

    @Test
    fun `progress label distinguishes unread and reading`() {
        assertEquals("目录待更新", ReaderShelf.progressLabel(book("a", "A", total = 0)))
        assertEquals("未开始 · 共 10 章", ReaderShelf.progressLabel(book("a", "A")))
        assertEquals(
            "读到第 4 章 · 共 10 章",
            ReaderShelf.progressLabel(book("a", "A", durChapterIndex = 3)),
        )
    }

    @Test
    fun `sort labels fall back to recent`() {
        assertEquals("最近阅读", ReaderShelf.sortLabel("unknown"))
        assertEquals("最近更新", ReaderShelf.sortLabel("update"))
    }
}
