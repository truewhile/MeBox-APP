package app.mebox.android.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleSelectorTest {

    private fun track(
        id: String,
        label: String,
        language: String? = null,
        selected: Boolean = false,
    ) = EngineTrack(
        id = id,
        label = label,
        type = EngineTrackType.TEXT,
        selected = selected,
        language = language,
    )

    @Test
    fun `auto load disabled selects nothing`() {
        val tracks = listOf(track("1", "简体中文", "zh"))
        val chosen = SubtitleSelector.choose(
            tracks,
            SubtitlePreference(autoLoad = false),
        )
        assertNull(chosen)
    }

    @Test
    fun `chinese priority prefers simplified over traditional`() {
        val tracks = listOf(
            track("1", "English", "en"),
            track("2", "繁體中文", "zh-Hant"),
            track("3", "简体中文", "zh-Hans"),
        )
        val chosen = SubtitleSelector.choose(
            tracks,
            SubtitlePreference(priority = SubtitlePriority.CHINESE),
        )
        assertEquals("3", chosen?.id)
    }

    @Test
    fun `chinese priority matches ISO 639-2 language codes`() {
        val tracks = listOf(
            track("1", "English", "eng"),
            track("2", "Chinese", "chi"),
        )
        val chosen = SubtitleSelector.choose(
            tracks,
            SubtitlePreference(priority = SubtitlePriority.CHINESE),
        )
        assertEquals("2", chosen?.id)
    }

    @Test
    fun `english priority matches label without language metadata`() {
        val tracks = listOf(
            track("1", "简体中文"),
            track("2", "English SDH"),
        )
        val chosen = SubtitleSelector.choose(
            tracks,
            SubtitlePreference(priority = SubtitlePriority.ENGLISH),
        )
        assertEquals("2", chosen?.id)
    }

    @Test
    fun `japanese priority matches japanese label`() {
        val tracks = listOf(
            track("1", "简体中文", "zh"),
            track("2", "日本語", "ja"),
        )
        val chosen = SubtitleSelector.choose(
            tracks,
            SubtitlePreference(priority = SubtitlePriority.JAPANESE),
        )
        assertEquals("2", chosen?.id)
    }

    @Test
    fun `missing preferred language falls back to first track`() {
        val tracks = listOf(
            track("1", "Français", "fr"),
            track("2", "Deutsch", "de"),
        )
        val chosen = SubtitleSelector.choose(
            tracks,
            SubtitlePreference(priority = SubtitlePriority.CHINESE),
        )
        assertEquals("1", chosen?.id)
    }

    @Test
    fun `follow source keeps the selected track`() {
        val tracks = listOf(
            track("1", "简体中文", "zh"),
            track("2", "English", "en", selected = true),
        )
        val chosen = SubtitleSelector.choose(
            tracks,
            SubtitlePreference(priority = SubtitlePriority.FOLLOW_SOURCE),
        )
        assertEquals("2", chosen?.id)
    }

    @Test
    fun `follow source falls back to first when nothing is selected`() {
        val tracks = listOf(
            track("1", "简体中文", "zh"),
            track("2", "English", "en"),
        )
        val chosen = SubtitleSelector.choose(
            tracks,
            SubtitlePreference(priority = SubtitlePriority.FOLLOW_SOURCE),
        )
        assertEquals("1", chosen?.id)
    }

    @Test
    fun `findMatch only returns a language match`() {
        val tracks = listOf(
            track("1", "Français", "fr"),
            track("2", "简体中文", "zh"),
        )
        assertEquals(
            "2",
            SubtitleSelector.findMatch(
                tracks,
                SubtitlePreference(priority = SubtitlePriority.CHINESE),
            )?.id,
        )
        assertNull(
            SubtitleSelector.findMatch(
                tracks,
                SubtitlePreference(priority = SubtitlePriority.JAPANESE),
            ),
        )
    }

    @Test
    fun `external subtitles use the same rules`() {
        val subtitles = listOf(
            EngineSubtitle(url = "a.vtt", label = "English", language = "en"),
            EngineSubtitle(url = "b.ass", label = "简日双语", language = "zh"),
        )
        val chosen = SubtitleSelector.choose(
            subtitles,
            SubtitlePreference(priority = SubtitlePriority.CHINESE),
        )
        assertEquals("b.ass", chosen?.url)
    }
}
