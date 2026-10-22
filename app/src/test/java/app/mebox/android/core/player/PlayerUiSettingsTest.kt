package app.mebox.android.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiSettingsTest {

    @Test
    fun `default gestures match the reference player`() {
        val settings = PlayerUiSettings.Default
        assertEquals(PlayerDoubleTapAction.SEEK_BACK, settings.doubleTapLeft)
        assertEquals(PlayerDoubleTapAction.SEEK_FORWARD, settings.doubleTapRight)
        assertEquals(PlayerLongPressAction.FAST, settings.longPressLeft)
        assertEquals(PlayerLongPressAction.FAST, settings.longPressRight)
        assertEquals(10_000L, settings.seekBackwardMs)
        assertEquals(10_000L, settings.seekForwardMs)
        assertTrue(settings.longPressSpeedAdjust)
        assertTrue(settings.slideBrightnessVolume)
        assertEquals(PlayerOrientation.SENSOR, settings.orientation)
        assertTrue(settings.continuousPlay)
        assertFalse(settings.autoPictureInPicture)
        assertFalse(settings.showQualityButton)
    }

    @Test
    fun `enum keys round trip and are unique`() {
        PlayerDoubleTapAction.entries.forEach { action ->
            assertEquals(action, PlayerDoubleTapAction.fromKey(action.key))
        }
        PlayerLongPressAction.entries.forEach { action ->
            assertEquals(action, PlayerLongPressAction.fromKey(action.key))
        }
        PlayerOrientation.entries.forEach { orientation ->
            assertEquals(orientation, PlayerOrientation.fromKey(orientation.key))
        }
        assertEquals(
            PlayerDoubleTapAction.entries.size,
            PlayerDoubleTapAction.entries.map { it.key }.toSet().size,
        )
        assertEquals(
            PlayerLongPressAction.entries.size,
            PlayerLongPressAction.entries.map { it.key }.toSet().size,
        )
        assertEquals(
            PlayerOrientation.entries.size,
            PlayerOrientation.entries.map { it.key }.toSet().size,
        )
    }

    @Test
    fun `unknown keys fall back safely`() {
        assertNull(PlayerDoubleTapAction.fromKey(null))
        assertNull(PlayerDoubleTapAction.fromKey("unknown"))
        assertNull(PlayerLongPressAction.fromKey(null))
        assertNull(PlayerLongPressAction.fromKey("unknown"))
        assertEquals(PlayerOrientation.SENSOR, PlayerOrientation.fromKey(null))
        assertEquals(PlayerOrientation.SENSOR, PlayerOrientation.fromKey("unknown"))
    }
}
