package app.mebox.android.core.media

import app.mebox.android.core.network.WatchHistoryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaContinueTest {

    @Test
    fun `latest returns the newest history entry of the series`() {
        // 服务端按 watched_at 倒序返回；最新的一条属于第二季，OVA 的记录更旧。
        val history = listOf(
            WatchHistoryDto(mediaId = "other", watchedAt = "2026-10-19T12:00:00Z"),
            WatchHistoryDto(mediaId = "s2e3", positionMs = 90_000),
            WatchHistoryDto(mediaId = "ova", positionMs = 600_000),
        )
        assertEquals(
            "s2e3",
            MediaContinue.latest(history, listOf("s1e1", "s2e3", "ova"))?.mediaId,
        )
        assertEquals(90_000L, MediaContinue.latest(history, listOf("s2e3"))?.positionMs)
    }

    @Test
    fun `latest ignores series that are not in the episode set`() {
        val history = listOf(
            WatchHistoryDto(mediaId = "other-show"),
            WatchHistoryDto(mediaId = "s1e1"),
        )
        assertNull(MediaContinue.latest(history, listOf("s1e2", "s1e3")))
    }

    @Test
    fun `latest handles empty inputs`() {
        assertNull(MediaContinue.latest(emptyList(), listOf("s1e1")))
        assertNull(MediaContinue.latest(listOf(WatchHistoryDto(mediaId = "s1e1")), emptyList()))
        assertNull(MediaContinue.latest(listOf(WatchHistoryDto(mediaId = "")), listOf(" ")))
    }
}
