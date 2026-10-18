package app.mebox.android.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderModeTest {

    @Test
    fun `default mode is automatic`() {
        assertEquals(DecoderMode.AUTO, DecoderMode.default)
    }

    @Test
    fun `unknown or missing key falls back to automatic`() {
        assertEquals(DecoderMode.AUTO, DecoderMode.fromKey(null))
        assertEquals(DecoderMode.AUTO, DecoderMode.fromKey(""))
        assertEquals(DecoderMode.AUTO, DecoderMode.fromKey("mediacodec"))
    }

    @Test
    fun `persisted keys round trip`() {
        DecoderMode.entries.forEach { mode ->
            assertEquals(mode, DecoderMode.fromKey(mode.key))
        }
    }

    @Test
    fun `persisted keys are unique`() {
        val keys = DecoderMode.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
