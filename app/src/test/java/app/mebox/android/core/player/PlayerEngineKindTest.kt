package app.mebox.android.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerEngineKindTest {

    @Test
    fun `default engine is system decoding`() {
        assertEquals(PlayerEngineKind.EXO, PlayerEngineKind.default)
    }

    @Test
    fun `unknown or missing key falls back to default`() {
        assertEquals(PlayerEngineKind.EXO, PlayerEngineKind.fromKey(null))
        assertEquals(PlayerEngineKind.EXO, PlayerEngineKind.fromKey(""))
        assertEquals(PlayerEngineKind.EXO, PlayerEngineKind.fromKey("mpv"))
    }

    @Test
    fun `persisted keys round trip`() {
        PlayerEngineKind.entries.forEach { engine ->
            assertEquals(engine, PlayerEngineKind.fromKey(engine.key))
        }
    }

    @Test
    fun `persisted keys are unique`() {
        val keys = PlayerEngineKind.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
