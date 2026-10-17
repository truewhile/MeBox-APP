package app.mebox.android.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilterTest {

    @Test
    fun `empty filter is inactive`() {
        assertFalse(LibraryFilter().isActive)
        assertNull(LibraryFilter().normalizedYears())
        assertEquals("", LibraryFilter().summary())
    }

    @Test
    fun `all conditions are reported as active`() {
        val filter = LibraryFilter(
            genres = setOf("动作", "喜剧", "科幻", "动画"),
            yearMin = 2010,
            yearMax = 2020,
            ratingMin = 8f,
            unwatched = true,
        )
        assertTrue(filter.isActive)
        val summary = filter.summary()
        assertTrue(summary.contains("动作、喜剧、科幻"))
        assertTrue(summary.contains("等 4 个类型"))
        assertTrue(summary.contains("2010-2020 年"))
        assertTrue(summary.contains("评分 ≥ 8"))
        assertTrue(summary.contains("仅未看完"))
    }

    @Test
    fun `one sided years are labelled correctly`() {
        assertTrue(LibraryFilter(yearMin = 2020).summary().contains("2020 年后"))
        assertTrue(LibraryFilter(yearMax = 2000).summary().contains("2000 年前"))
    }

    @Test
    fun `invalid year ranges are dropped`() {
        val filter = LibraryFilter(yearMin = 2020, yearMax = 2010)
        assertNull(filter.normalizedYears())
    }

    @Test
    fun `missing years fall back to facets`() {
        assertEquals(1990..2024, LibraryFilter().normalizedYears(1990, 2024))
        assertEquals(2000..2024, LibraryFilter(yearMin = 2000).normalizedYears(1990, 2024))
    }

    @Test
    fun `sort labels reflect direction`() {
        assertEquals("最近添加 ↓", LibrarySortSelection().label())
        assertEquals("名称 ↑", LibrarySortSelection(LibrarySort.TITLE, descending = false).label())
        assertEquals("desc", LibrarySortSelection(LibrarySort.RATING, descending = true).order)
    }
}
