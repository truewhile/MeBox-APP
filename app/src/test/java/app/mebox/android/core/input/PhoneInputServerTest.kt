package app.mebox.android.core.input

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneInputServerTest {

    @Test
    fun servesTokenPageAndEmitsSubmittedSearch() = runBlocking {
        val server = PhoneInputServer()
        try {
            assertTrue(server.startForTest("127.0.0.1"))
            val base = requireNotNull(server.url.value)

            val page = URL(base).readText()
            assertTrue(page.contains("MeBox 电视助手"))
            assertTrue(page.contains("action=\"/t/"))

            val connection = URL("$base/submit").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8",
            )
            // 先订阅再提交，避免 SharedFlow(replay = 0) 在无订阅者时丢弃事件。
            val collector = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(3_000) { server.events.first() }
            }
            val body = "type=search&query=%E7%B9%81%E8%8A%B1"
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            assertEquals(200, connection.responseCode)
            assertTrue(connection.inputStream.bufferedReader().use { it.readText() }.contains("已推送到电视"))

            val event = collector.await()
            assertEquals(PhoneInputEvent.Search("繁花"), event)
        } finally {
            server.stop()
        }
    }

    @Test
    fun rejectsUnknownTokenPath() = runBlocking {
        val server = PhoneInputServer()
        try {
            assertTrue(server.startForTest("127.0.0.1"))
            val base = requireNotNull(server.url.value)
            val guessed = base.substringBefore("/t/") + "/t/not-the-token"
            val connection = URL(guessed).openConnection() as HttpURLConnection
            assertEquals(404, connection.responseCode)
        } finally {
            server.stop()
        }
    }
}
