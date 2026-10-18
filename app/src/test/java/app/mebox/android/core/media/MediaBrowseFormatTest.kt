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
        // 没有季集号的单条（电影）也归入第一季，与网页端分组一致。
        assertEquals(listOf(1), MediaBrowseFormat.seasons(listOf(MediaDto(id = "1"))))
        assertEquals(emptyList<Int>(), MediaBrowseFormat.seasons(emptyList()))
    }

    @Test
    fun `season zero with episodes is the specials section`() {
        val special = MediaDto(id = "sp", seasonNum = 0, episodeNum = 1, path = "/anime/show/Specials/01.mkv")
        assertEquals(0, MediaBrowseFormat.seasonOf(special))
        assertEquals("特别篇", MediaBrowseFormat.seasonLabel(0))
        // 没有集号且季号缺省时按第一季处理，与网页端 `season_num || 1` 一致。
        assertEquals(1, MediaBrowseFormat.seasonOf(MediaDto(id = "plain")))
    }

    @Test
    fun `path based special sections are grouped and ordered like the web app`() {
        val episodes = listOf(
            MediaDto(id = "ova", seasonNum = 0, episodeNum = 1, path = "/anime/某番/OVA/01.mkv"),
            MediaDto(id = "s1", seasonNum = 1, episodeNum = 1, path = "/anime/某番/Season 1/01.mkv"),
            MediaDto(id = "sp", seasonNum = 0, episodeNum = 1, path = "/anime/某番/Specials/01.mkv"),
            MediaDto(
                id = "movie",
                title = "某番 剧场版",
                episodeNum = 1,
                path = "/anime/某番/剧场版/某番 剧场版.mkv",
            ),
        )
        assertEquals(listOf(1, 0, -1, -2), MediaBrowseFormat.seasons(episodes))
        assertEquals("OVA", MediaBrowseFormat.seasonLabel(MediaBrowseFormat.OVA_SEASON))
        assertEquals("剧场版", MediaBrowseFormat.seasonLabel(MediaBrowseFormat.THEATRICAL_SEASON))

        val groups = MediaBrowseFormat.seasonGroups(episodes)
        assertEquals(4, groups.size)
        assertEquals(listOf("ova"), groups.last().episodes.map { it.id })
    }

    @Test
    fun `season groups sort episodes by episode number including fractions`() {
        val episodes = listOf(
            MediaDto(id = "e3", seasonNum = 1, episodeNum = 3),
            MediaDto(id = "e1", seasonNum = 1, episodeNum = 1),
            MediaDto(id = "e2", seasonNum = 1, episodeNum = 2, episodeFraction = 0.5),
        )
        val group = MediaBrowseFormat.seasonGroups(episodes).single()
        assertEquals(listOf("e1", "e2", "e3"), group.episodes.map { it.id })
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
