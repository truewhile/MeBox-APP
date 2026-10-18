package app.mebox.android.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterAdvanceGuardTest {

    @Test
    fun `loading a chapter blocks repeated finish signals`() {
        val guard = ChapterAdvanceGuard()

        // 第一章正常播完：只处理一次。
        guard.beginLoad()
        guard.mediaInstalled()
        assertTrue(guard.tryFinish(3))

        // 开始加载第四章音轨，此时播放器还停在第三章片尾，
        // 每秒一次的轮询会反复判定「本章结束」，都必须被挡住。
        guard.beginLoad()
        repeat(5) { assertFalse(guard.tryFinish(4)) }
        assertFalse(guard.tryFinish(5))
        assertFalse(guard.tryFinish(6))
    }

    @Test
    fun `same chapter is handled only once`() {
        val guard = ChapterAdvanceGuard()
        guard.beginLoad()
        guard.mediaInstalled()

        assertTrue(guard.tryFinish(7))
        assertFalse(guard.tryFinish(7))
        assertFalse(guard.tryFinish(7))
    }

    @Test
    fun `next chapter can finish after its audio is installed`() {
        val guard = ChapterAdvanceGuard()
        guard.beginLoad()
        guard.mediaInstalled()
        assertTrue(guard.tryFinish(1))

        guard.beginLoad()
        guard.mediaInstalled()
        assertTrue(guard.tryFinish(2))
        assertEquals(2, guard.finishedIndex)
    }

    @Test
    fun `failed load does not keep skipping following chapters`() {
        val guard = ChapterAdvanceGuard()
        guard.beginLoad()
        guard.mediaInstalled()
        assertTrue(guard.tryFinish(1))

        // 第二章音轨加载失败：旧章片尾还在播，不能继续往后跳。
        guard.beginLoad()
        guard.loadFailed(2)
        assertFalse(guard.loading)
        assertFalse(guard.tryFinish(2))

        // 用户手动切到第三章并加载成功后，恢复正常换章。
        guard.beginLoad()
        guard.mediaInstalled()
        assertTrue(guard.tryFinish(3))
    }

    @Test
    fun `reset clears loading and finished state`() {
        val guard = ChapterAdvanceGuard()
        guard.beginLoad()
        assertTrue(guard.loading)

        guard.reset()
        assertFalse(guard.loading)
        assertEquals(-1, guard.finishedIndex)
        assertTrue(guard.tryFinish(0))
    }
}
