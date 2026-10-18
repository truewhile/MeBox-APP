package app.mebox.android.core.reader

import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class ReaderAudioNetworkTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `audio requests reuse a connection warmed by the application client`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("chapter metadata"))
            server.enqueue(MockResponse().setBody("audio bytes"))
            val shared = OkHttpClient()
            try {
                shared.newCall(Request.Builder().url(server.url("/chapter")).build())
                    .execute().use { assertEquals("chapter metadata", it.body!!.string()) }
                val audio = createReaderAudioHttpClient(shared)
                audio.newCall(Request.Builder().url(server.url("/audio?signature=sample")).build())
                    .execute().use { assertEquals("audio bytes", it.body!!.string()) }

                assertEquals(0, server.takeRequest(1, TimeUnit.SECONDS)!!.sequenceNumber)
                val audioRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals(1, audioRequest.sequenceNumber)
                assertEquals("/audio?signature=sample", audioRequest.path)
            } finally {
                shared.connectionPool.evictAll()
                shared.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test
    fun `cacheable audio never reads or writes the application disk cache`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Cache-Control", "max-age=3600").setBody("first"))
            server.enqueue(MockResponse().addHeader("Cache-Control", "max-age=3600").setBody("second"))
            Cache(temporaryFolder.newFolder("http"), 1_048_576).use { diskCache ->
                val shared = OkHttpClient.Builder().cache(diskCache).build()
                try {
                    val audio = createReaderAudioHttpClient(shared)
                    val request = Request.Builder().url(server.url("/audio")).build()
                    audio.newCall(request).execute().use { assertEquals("first", it.body!!.string()) }
                    audio.newCall(request).execute().use { assertEquals("second", it.body!!.string()) }

                    assertEquals(2, server.requestCount)
                    assertEquals(0, diskCache.writeSuccessCount())
                    assertNull(audio.cache)
                } finally {
                    shared.connectionPool.evictAll()
                    shared.dispatcher.executorService.shutdown()
                }
            }
        }
    }
}
