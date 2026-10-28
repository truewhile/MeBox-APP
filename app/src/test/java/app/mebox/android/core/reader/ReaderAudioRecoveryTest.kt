package app.mebox.android.core.reader

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音源直链失效后的自动恢复策略（ReaderAudioRecovery）。
 *
 * 真实故障：聆阅·懒人听书源的音频直链是第三方中转现签的 CDN 票，票被拒时播放器
 * 报 2004（HTTP 401）或 3003（拿到的是错误页）。服务端换票后重装本章即可恢复，
 * 但不能无限重装。
 */
class ReaderAudioRecoveryTest {

    @Test
    fun `票失效相关的错误码可自动重装`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        ).forEach { code ->
            assertTrue("错误码 $code 应当可重装", ReaderAudioRecovery.isRetriable(code))
        }
    }

    @Test
    fun `与票无关的错误码不自动重装`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        ).forEach { code ->
            assertFalse("错误码 $code 不该自动重装", ReaderAudioRecovery.isRetriable(code))
        }
    }

    @Test
    fun `同一章只自动重装一次`() {
        val reloaded = mutableSetOf<Int>()
        assertTrue(ReaderAudioRecovery.shouldAutoReload(2004, chapterIndex = 3, reloaded = reloaded))
        assertFalse(
            "第二次失败应当交给用户/正常错误提示，不能无限重装",
            ReaderAudioRecovery.shouldAutoReload(2004, chapterIndex = 3, reloaded = reloaded),
        )
        // 换一章仍然可以自动恢复一次
        assertTrue(ReaderAudioRecovery.shouldAutoReload(3003, chapterIndex = 4, reloaded = reloaded))
    }

    @Test
    fun `成功装载后可以再次自动恢复`() {
        val reloaded = mutableSetOf<Int>()
        assertTrue(ReaderAudioRecovery.shouldAutoReload(2004, chapterIndex = 7, reloaded = reloaded))
        ReaderAudioRecovery.forgetChapter(7, reloaded)
        assertTrue(
            "几小时后票再次过期时应当仍能自动恢复一次",
            ReaderAudioRecovery.shouldAutoReload(2004, chapterIndex = 7, reloaded = reloaded),
        )
    }

    @Test
    fun `停止播放清空全部标记`() {
        val reloaded = mutableSetOf(1, 2, 3)
        ReaderAudioRecovery.clear(reloaded)
        assertTrue(reloaded.isEmpty())
    }
}
