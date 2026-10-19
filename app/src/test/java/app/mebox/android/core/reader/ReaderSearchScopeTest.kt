package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderSourceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSearchScopeTest {

    private fun source(
        id: String,
        name: String = id,
        group: String = "",
        enabled: Boolean = true,
    ) = ReaderSourceDto(id = id, name = name, group = group, enabled = enabled)

    private val sources = listOf(
        source("a", name = "Alpha", group = "小说"),
        source("b", name = "Beta", group = "小说"),
        source("c", name = "Gamma", group = "漫画"),
        source("d", name = "Disabled", group = "漫画", enabled = false),
    )

    @Test
    fun `empty scope means all enabled sources`() {
        assertTrue(ReaderSearchScope.isAll(emptySet(), sources))
        assertEquals("全部书源", ReaderSearchScope.label(emptySet(), sources))
        assertNull(ReaderSearchScope.requestIds(emptySet(), sources))
        assertNull(ReaderSearchScope.initialSelection(emptySet(), sources))
    }

    @Test
    fun `subset keeps only enabled sources and is sent to the backend`() {
        val saved = setOf("a", "d", "gone")
        assertEquals(listOf("a"), ReaderSearchScope.effectiveIds(saved, sources))
        assertEquals(setOf("a"), ReaderSearchScope.initialSelection(saved, sources))
        assertEquals(listOf("a"), ReaderSearchScope.requestIds(saved, sources))
        assertEquals("已选 1 个书源", ReaderSearchScope.label(saved, sources))
    }

    @Test
    fun `scope with every saved source disabled falls back to all`() {
        val saved = setOf("d", "gone")
        assertTrue(ReaderSearchScope.isAll(saved, sources))
        assertNull(ReaderSearchScope.requestIds(saved, sources))
        assertNull(ReaderSearchScope.initialSelection(saved, sources))
    }

    @Test
    fun `saved ids survive before sources are loaded`() {
        // 书源列表还没到：不能把已保存的子集误判成「全部」，否则第一次搜索会搜全源。
        val saved = setOf("a", "b")
        assertEquals(saved, ReaderSearchScope.initialSelection(saved, emptyList()))
        assertEquals(saved.toList(), ReaderSearchScope.requestIds(saved, emptyList()))
        assertEquals("已选 2 个书源", ReaderSearchScope.label(saved, emptyList()))
    }

    @Test
    fun `confirming a full selection stores an empty scope`() {
        val allEnabled = setOf("a", "b", "c")
        assertEquals(emptySet<String>(), ReaderSearchScope.confirmSelection(allEnabled, sources))
        assertEquals(setOf("a", "c"), ReaderSearchScope.confirmSelection(setOf("a", "c"), sources))
        // 停用的书源不会进入保存结果。
        assertEquals(setOf("a"), ReaderSearchScope.confirmSelection(setOf("a", "d"), sources))
    }

    @Test
    fun `groups keep source order and split by group name`() {
        val groups = ReaderSearchScope.groups(sources)
        assertEquals(listOf("小说", "漫画"), groups.map { it.name })
        assertEquals(listOf("a", "b"), groups[0].sources.map { it.id })
        assertEquals(listOf("c", "d"), groups[1].sources.map { it.id })
    }

    @Test
    fun `group filter matches name or group`() {
        assertEquals(listOf("a"), ReaderSearchScope.groups(sources, "alpha").flatMap { it.sources }.map { it.id })
        assertEquals(
            listOf("c", "d"),
            ReaderSearchScope.groups(sources, "漫画").flatMap { it.sources }.map { it.id },
        )
        assertEquals(emptyList<ReaderSearchScope.Group>(), ReaderSearchScope.groups(sources, "zzz"))
    }

    @Test
    fun `ungrouped sources get a fallback group name`() {
        val groups = ReaderSearchScope.groups(listOf(source("x", group = "  ")))
        assertEquals("未分组", groups.single().name)
    }

    @Test
    fun `group toggle ignores disabled sources and can clear the group`() {
        val group = ReaderSearchScope.groups(sources).first { it.name == "漫画" }
        val selected = ReaderSearchScope.toggleGroup(emptySet(), group)
        assertEquals(setOf("c"), selected)

        val cleared = ReaderSearchScope.toggleGroup(setOf("c", "a"), group)
        assertEquals(setOf("a"), cleared)
    }

    @Test
    fun `visible toggle only affects the filtered sources`() {
        val visible = listOf(source("a"), source("b", enabled = false))
        assertEquals(setOf("a", "x"), ReaderSearchScope.toggleVisible(setOf("x"), visible, true))
        assertEquals(setOf("x"), ReaderSearchScope.toggleVisible(setOf("a", "x"), visible, false))
    }
}
