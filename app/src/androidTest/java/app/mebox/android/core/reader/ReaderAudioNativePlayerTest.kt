package app.mebox.android.core.reader

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/** 真正的 Android Looper/Media3 状态机测试，不用 JVM stub 冒充播放器运行结果。 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderAudioNativePlayerTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test fun wavPlaysDirectlyWithoutLocalFallback() {
        val file = wav("native-direct.wav", 3)
        val ref = AtomicReference<ReaderAudioNativePlayer>()
        instrumentation.runOnMainSync {
            val exo = ExoPlayer.Builder(context).build()
            ref.set(ReaderAudioNativePlayer(context, exo,
                ReaderAudioLocalSource(File(context.cacheDir, "native-test-source"), OkHttpClient()), true))
            ref.get().setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            ref.get().prepare()
        }
        try {
            waitFor { ref.get().playbackState == Player.STATE_READY }
            instrumentation.runOnMainSync {
                assertNull(ref.get().playerError)
                assertTrue(ref.get().duration > 0)
                ref.get().seekTo(500)
            }
        } finally { instrumentation.runOnMainSync { ref.get().release() }; file.delete() }
    }

    @Test fun invalidAudioFailsOnceAndCanBeClearedAndReplaced() {
        val invalid = File(context.cacheDir, "native-broken.wma").apply { writeBytes(ByteArray(0)) }
        val valid = wav("native-after-error.wav", 3)
        val ref = AtomicReference<ReaderAudioNativePlayer>()
        instrumentation.runOnMainSync {
            ref.set(ReaderAudioNativePlayer(context, ExoPlayer.Builder(context).build(),
                ReaderAudioLocalSource(File(context.cacheDir, "native-test-source"), OkHttpClient()), true))
            ref.get().setMediaItem(MediaItem.fromUri(invalid.toURI().toString()))
            ref.get().prepare()
        }
        try {
            waitFor { ref.get().playerError != null }
            instrumentation.runOnMainSync {
                assertEquals(PlaybackException.ERROR_CODE_DECODING_FAILED, ref.get().playerError!!.errorCode)
                ref.get().stop()
                ref.get().clearMediaItems()
                assertEquals(0, ref.get().mediaItemCount)
                ref.get().setMediaItem(MediaItem.fromUri(valid.toURI().toString()))
                ref.get().prepare()
            }
            waitFor { ref.get().playbackState == Player.STATE_READY }
            instrumentation.runOnMainSync { assertNull(ref.get().playerError) }
        } finally { instrumentation.runOnMainSync { ref.get().release() }; invalid.delete(); valid.delete() }
    }

    @Test fun localFallbackSupportsSeekPauseResumeStopAndReplay() {
        val file = wav("native-forced-fallback.wav", 5)
        val ref = AtomicReference<ReaderAudioNativePlayer>()
        instrumentation.runOnMainSync {
            // 故意让 Exo 无法识别合法 WAV，只测试真实 VLC fallback，不依赖设备 WMA 硬解能力。
            val exo = ExoPlayer.Builder(context).setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context), ExtractorsFactory { emptyArray() })
            ).build()
            ref.set(ReaderAudioNativePlayer(context, exo,
                ReaderAudioLocalSource(File(context.cacheDir, "native-test-source"), OkHttpClient()), true))
            ref.get().setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            ref.get().prepare()
        }
        try {
            waitFor { ref.get().playbackState == Player.STATE_READY }
            instrumentation.runOnMainSync {
                assertNull(ref.get().playerError)
                assertFalse(ref.get().playWhenReady)
                ref.get().seekTo(1_000)
                ref.get().play()
            }
            waitFor { ref.get().currentPosition > 1_700 }
            var checkpoint = 0L
            instrumentation.runOnMainSync { ref.get().pause(); checkpoint = ref.get().currentPosition }
            Thread.sleep(200)
            instrumentation.runOnMainSync { ref.get().play() }
            waitFor { ref.get().currentPosition > checkpoint + 300 }
            instrumentation.runOnMainSync {
                checkpoint = ref.get().currentPosition
                ref.get().stop()
                assertTrue(ref.get().currentPosition >= checkpoint - 300)
                ref.get().prepare()
                ref.get().play()
            }
            waitFor { ref.get().playbackState == Player.STATE_ENDED }
            instrumentation.runOnMainSync { ref.get().seekTo(0); ref.get().play() }
            waitFor { ref.get().playbackState == Player.STATE_READY && ref.get().currentPosition in 1..2_000 }
            instrumentation.runOnMainSync { ref.get().stop(); ref.get().clearMediaItems(); assertEquals(0, ref.get().mediaItemCount) }
        } finally { instrumentation.runOnMainSync { ref.get().release() }; file.delete() }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for native audio state")
    }

    private fun wav(name: String, seconds: Int): File {
        val samples = 8_000 * seconds
        val bytes = samples * 2
        val file = File(context.cacheDir, name)
        FileOutputStream(file).use { out ->
            fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
            fun le(value: Int, count: Int) { repeat(count) { out.write(value shr (8 * it) and 255) } }
            ascii("RIFF"); le(36 + bytes, 4); ascii("WAVEfmt "); le(16, 4)
            le(1, 2); le(1, 2); le(8_000, 4); le(16_000, 4); le(2, 2); le(16, 2)
            ascii("data"); le(bytes, 4); out.write(ByteArray(bytes))
        }
        return file
    }
}
