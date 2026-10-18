package app.mebox.android.core.media

import app.mebox.android.core.network.LibraryDto
import app.mebox.android.core.network.LibraryTagDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LibraryGroupingTest {

    @Test
    fun `pinned libraries move to the front`() {
        val libraries = listOf(library("a"), library("b"), library("c"))

        val ordered = LibraryGrouping.orderByPinned(libraries, listOf("c"))

        assertEquals(listOf("c", "a", "b"), ordered.map { it.id })
    }

    @Test
    fun `pinned group keeps server order and the rest keeps its order`() {
        val libraries = listOf(library("a"), library("b"), library("c"), library("d"))

        val ordered = LibraryGrouping.orderByPinned(libraries, listOf("d", "b"))

        assertEquals(listOf("b", "d", "a", "c"), ordered.map { it.id })
    }

    @Test
    fun `ordering is untouched when nothing is pinned`() {
        val libraries = listOf(library("a"), library("b"), library("c"))

        assertSame(libraries, LibraryGrouping.orderByPinned(libraries, emptyList()))
    }

    @Test
    fun `tag tabs start with all and count only available libraries`() {
        val libraries = listOf(library("lib-1"), library("lib-2"))
        val tags = listOf(
            LibraryTagDto("动画", listOf("lib-1", "gone")),
            LibraryTagDto("电影", listOf("lib-2")),
        )

        val tabs = LibraryGrouping.tabs(libraries, tags)

        assertEquals(3, tabs.size)
        assertNull(tabs[0].name)
        assertEquals(2, tabs[0].count)
        assertEquals("动画", tabs[1].name)
        assertEquals(1, tabs[1].count)
        assertEquals("电影", tabs[2].name)
        assertEquals(1, tabs[2].count)
    }

    @Test
    fun `filter by tag keeps library order and drops stale ids`() {
        val libraries = listOf(library("lib-1"), library("lib-2"), library("lib-3"))
        val tags = listOf(LibraryTagDto("动画", listOf("lib-3", "lib-1", "gone")))

        val filtered = LibraryGrouping.filterByTag(libraries, tags, "动画")

        assertEquals(listOf("lib-1", "lib-3"), filtered.map { it.id })
    }

    @Test
    fun `all tag returns the original list`() {
        val libraries = listOf(library("lib-1"))
        val tags = listOf(LibraryTagDto("动画", listOf("lib-1")))

        assertSame(libraries, LibraryGrouping.filterByTag(libraries, tags, null))
        assertSame(libraries, LibraryGrouping.filterByTag(libraries, tags, ""))
    }

    @Test
    fun `unknown or empty tag yields no libraries`() {
        val libraries = listOf(library("lib-1"))
        val tags = listOf(LibraryTagDto("空标签", emptyList()))

        assertEquals(emptyList<LibraryDto>(), LibraryGrouping.filterByTag(libraries, tags, "不存在"))
        assertEquals(emptyList<LibraryDto>(), LibraryGrouping.filterByTag(libraries, tags, "空标签"))
    }

    @Test
    fun `selected tag falls back to all once removed`() {
        val tags = listOf(LibraryTagDto("动画", listOf("lib-1")))

        assertEquals("动画", LibraryGrouping.resolveTag(tags, "动画"))
        assertNull(LibraryGrouping.resolveTag(tags, "电影"))
        assertNull(LibraryGrouping.resolveTag(tags, null))
    }

    private fun library(id: String): LibraryDto = LibraryDto(id = id, name = id)
}
