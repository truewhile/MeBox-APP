package app.mebox.android.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTrackRestorerTest {

    private fun track(
        id: String,
        label: String,
        language: String? = null,
        type: EngineTrackType = EngineTrackType.AUDIO,
    ) = EngineTrack(
        id = id,
        label = label,
        type = type,
        selected = false,
        language = language,
    )

    private fun selectionOf(track: EngineTrack) =
        PlaybackTrackSelection.Track(PlaybackTrackRestorer.refOf(track))

    @Test
    fun `matches by label when engine track ids change`() {
        val before = track("1", "Japanese", "ja")
        val after = listOf(
            track("7", "English", "en"),
            track("8", "Japanese", "ja"),
        )

        val matched = PlaybackTrackRestorer.match(
            after,
            EngineTrackType.AUDIO,
            selectionOf(before),
        )

        assertEquals("8", matched?.id)
    }

    @Test
    fun `matches ISO 639 language aliases`() {
        val before = track("1", "Chinese", "zh-Hans")
        val after = listOf(
            track("2", "English", "eng"),
            track("3", "中文", "chi"),
        )

        val matched = PlaybackTrackRestorer.match(
            after,
            EngineTrackType.AUDIO,
            selectionOf(before),
        )

        assertEquals("3", matched?.id)
    }

    @Test
    fun `language alone is used only when unambiguous`() {
        val before = track("1", "Track 1", "zh")
        val after = listOf(
            track("2", "简体中文", "zh"),
            track("3", "繁體中文", "zh"),
        )

        assertNull(
            PlaybackTrackRestorer.match(
                after,
                EngineTrackType.AUDIO,
                selectionOf(before),
            ),
        )
    }

    @Test
    fun `generic labels never become track identity`() {
        val before = track("1", "Track 2")
        val after = listOf(track("9", "Track 2"))

        assertNull(
            PlaybackTrackRestorer.match(
                after,
                EngineTrackType.AUDIO,
                selectionOf(before),
            ),
        )
    }

    @Test
    fun `external subtitle file name matches its plain label`() {
        val before = track("1", "简体中文", type = EngineTrackType.TEXT)
        val after = listOf(
            track("4", "简体中文.ass", type = EngineTrackType.TEXT),
        )

        val matched = PlaybackTrackRestorer.match(
            after,
            EngineTrackType.TEXT,
            selectionOf(before),
        )

        assertEquals("4", matched?.id)
    }

    @Test
    fun `track type prevents cross type matches`() {
        val before = track("1", "English", "en", EngineTrackType.AUDIO)
        val after = listOf(
            track("2", "English", "en", EngineTrackType.TEXT),
        )

        assertNull(
            PlaybackTrackRestorer.match(
                after,
                EngineTrackType.AUDIO,
                selectionOf(before),
            ),
        )
    }

    @Test
    fun `same label is disambiguated by language`() {
        val before = track("1", "Commentary", "en")
        val after = listOf(
            track("2", "Commentary", "en"),
            track("3", "Commentary", "de"),
        )

        val matched = PlaybackTrackRestorer.match(
            after,
            EngineTrackType.AUDIO,
            selectionOf(before),
        )

        assertEquals("2", matched?.id)
    }

    @Test
    fun `auto and off selections match nothing`() {
        val tracks = listOf(track("1", "English", "en"))

        assertNull(
            PlaybackTrackRestorer.match(tracks, EngineTrackType.AUDIO, PlaybackTrackSelection.Auto),
        )
        assertNull(
            PlaybackTrackRestorer.match(tracks, EngineTrackType.AUDIO, PlaybackTrackSelection.Off),
        )
    }

    @Test
    fun `matching ignores case and spacing in labels`() {
        val before = track("1", "English  SDH", "en")
        val after = listOf(track("2", "english sdh", "en"))

        assertEquals(
            "2",
            PlaybackTrackRestorer.match(
                after,
                EngineTrackType.AUDIO,
                selectionOf(before),
            )?.id,
        )
    }
}
