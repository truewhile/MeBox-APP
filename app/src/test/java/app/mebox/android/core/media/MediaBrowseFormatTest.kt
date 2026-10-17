package app.mebox.android.core.media

import app.mebox.android.core.network.MediaDto
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaBrowseFormatTest {

    @Test
    fun `detail meta skips empty fields and keeps order`() {
        val media = MediaDto(
            id = "1",
            year = 2019,
            durationSec = 7_200,
            height = 1080,
            seasonNum = 1,
            episodeNum = 3,
        )
        assertEquals("2019 · 2小时0分 · 1080p · S01E03", MediaBrowseFormat.detailMeta(media))
    }

    @Test
    fun `detail meta of a bare movie is empty`() {
        assertEquals("", MediaBrowseFormat.detailMeta(MediaDto(id = "1")))
    }

    @Test
    fun `series subtitle falls back to year only`() {
        assertEquals("2021", MediaBrowseFormat.seriesSubtitle(MediaDto(id = "1", year = 2021)))
        assertEquals("", MediaBrowseFormat.seriesSubtitle(MediaDto(id = "1")))
    }

    @Test
    fun `seasons are distinct and sorted`() {
        val episodes = listOf(
            MediaDto(id = "1", seasonNum = 2, episodeNum = 1),
            MediaDto(id = "2", seasonNum = 0),
            MediaDto(id = "3", seasonNum = 1, episodeNum = 1),
            MediaDto(id = "4", seasonNum = 2, episodeNum = 2),
        )
        assertEquals(listOf(1, 2), MediaBrowseFormat.seasons(episodes))
        assertEquals(emptyList<Int>(), MediaBrowseFormat.seasons(listOf(MediaDto(id = "1"))))
    }

    @Test
    fun `continue subtitle shows remaining time`() {
        assertEquals(
            "S01E02 · 剩余 30 分钟",
            MediaBrowseFormat.continueSubtitle("S01E02", positionMs = 600_000, durationMs = 2_400_000),
        )
        assertEquals(
            "继续观看",
            MediaBrowseFormat.continueSubtitle("", positionMs = 0, durationMs = 0),
        )
    }

    @Test
    fun `remaining clamps negative and sub minute values`() {
        assertEquals("不到 1 分钟", MediaBrowseFormat.remaining(-5_000))
        assertEquals("不到 1 分钟", MediaBrowseFormat.remaining(30_000))
        assertEquals("59 分钟", MediaBrowseFormat.remaining(59 * 60_000))
        assertEquals("1 小时 5 分", MediaBrowseFormat.remaining(65 * 60_000))
    }

    @Test
    fun `duration switches to hours above sixty minutes`() {
        assertEquals("0分钟", MediaBrowseFormat.duration(0))
        assertEquals("45分钟", MediaBrowseFormat.duration(45 * 60))
        assertEquals("1小时0分", MediaBrowseFormat.duration(3_600))
        assertEquals("2小时30分", MediaBrowseFormat.duration(9_000))
    }
}
