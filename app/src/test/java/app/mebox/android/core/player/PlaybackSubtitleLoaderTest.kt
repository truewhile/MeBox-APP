package app.mebox.android.core.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSubtitleLoaderTest {
    private val chinese = EngineSubtitle("https://server/zh", "中文", "zh", "https://server/zh/ass")
    private val english = EngineSubtitle("https://server/en", "英文", "en")
    private fun media(subtitles: List<EngineSubtitle>) = EngineMedia("episode", "title", "https://cdn/video", subtitles = subtitles)

    @Test
    fun `remote subtitles are never opened on the video startup path`() = runTest {
        val requests = mutableListOf<EngineSubtitle>()
        val added = mutableListOf<String>()
        val file = Files.createTempFile("subtitle", ".ass").toFile()
        val loader = PlaybackSubtitleLoader(this, { requests += it; file }, { subtitle, _, _ ->
            added += subtitle.label
            true
        })
        loader.prepare(media(listOf(chinese)))
        runCurrent()
        assertTrue(requests.isEmpty())
        loader.onPlaying()
        runCurrent()
        assertEquals(listOf(chinese), requests)
        assertEquals(listOf("中文"), added)
        loader.close()
        assertFalse(file.exists())
    }

    @Test
    fun `slow preferred subtitle cannot block playback notification or overwrite manual selection`() = runTest {
        val response = CompletableDeferred<File>()
        val selections = mutableListOf<Boolean>()
        val loader = PlaybackSubtitleLoader(this, { response.await() }, { _, _, select ->
            selections += select
            true
        })
        loader.prepare(media(listOf(chinese)))
        loader.onPlaying()
        runCurrent()
        assertTrue(selections.isEmpty())
        loader.onUserSelection()
        response.complete(Files.createTempFile("subtitle", ".ass").toFile())
        runCurrent()
        assertEquals(listOf(false), selections)
        loader.close()
    }

    @Test
    fun `one failed subtitle does not stop remaining subtitles or reload video`() = runTest {
        val added = mutableListOf<String>()
        val selected = mutableListOf<Boolean>()
        val file = Files.createTempFile("subtitle", ".vtt").toFile()
        val loader = PlaybackSubtitleLoader(this, {
            if (it == chinese) throw java.io.IOException("timeout")
            file
        }, { subtitle, _, select -> added += subtitle.label; selected += select; true })
        loader.prepare(media(listOf(english, chinese)))
        loader.onPlaying()
        runCurrent()
        assertEquals(listOf("英文"), added)
        assertEquals(listOf(false), selected)
        loader.close()
    }

    @Test
    fun `switching episodes cancels downloads and prevents old subtitle attachment`() = runTest {
        val pending = CompletableDeferred<File>()
        val added = mutableListOf<String>()
        var cancelled = false
        val loader = PlaybackSubtitleLoader(this, {
            try { pending.await() } finally { cancelled = true }
        }, { subtitle, _, _ ->
            added += subtitle.label
            true
        })
        loader.prepare(media(listOf(chinese)))
        loader.onPlaying()
        runCurrent()
        loader.prepare(media(emptyList()).copy(mediaId = "next"))
        runCurrent()
        assertTrue(added.isEmpty())
        assertTrue(cancelled)
        loader.close()
    }

    @Test
    fun `resuming playback does not download or add the same subtitles twice`() = runTest {
        var requests = 0
        val file = Files.createTempFile("subtitle", ".ass").toFile()
        val loader = PlaybackSubtitleLoader(this, { requests++; file }, { _, _, _ -> true })
        loader.prepare(media(listOf(chinese)))
        loader.onPlaying()
        runCurrent()
        loader.onPlaying()
        runCurrent()
        assertEquals(1, requests)
        loader.close()
    }

    @Test
    fun `restored subtitle choice is never auto selected by late loading`() = runTest {
        val selections = mutableListOf<Boolean>()
        val file = Files.createTempFile("subtitle", ".ass").toFile()
        val loader = PlaybackSubtitleLoader(this, { file }, { _, _, select ->
            selections += select
            true
        })
        // 换内核后用户之前的手动选择由播放页恢复；外挂字幕可能还在下载。
        loader.holdAutoSelection()
        loader.prepare(media(listOf(chinese)))
        loader.onPlaying()
        runCurrent()

        assertEquals(listOf(false), selections)
        loader.close()
    }

    @Test
    fun `auto selection hold survives an internal media reload`() = runTest {
        val selections = mutableListOf<Boolean>()
        val file = Files.createTempFile("subtitle", ".ass").toFile()
        val loader = PlaybackSubtitleLoader(this, { file }, { _, _, select ->
            selections += select
            true
        })
        loader.holdAutoSelection()
        loader.prepare(media(listOf(chinese)))
        loader.onPlaying()
        runCurrent()
        loader.prepare(media(listOf(chinese)).copy(mediaId = "after-reload"))
        loader.onPlaying()
        runCurrent()

        assertEquals(listOf(false, false), selections)
        loader.close()
    }
}
