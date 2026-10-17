package app.mebox.android.core.log

import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogShareServerTest {

    @Test
    fun servesPageAndBundleAndRejectsUnknownToken() = runBlocking {
        val cacheDir = Files.createTempDirectory("mebox-logs-test").toFile()
        val server = LogShareServer(cacheDir)
        try {
            assertTrue(server.startForTest("127.0.0.1"))
            val base = requireNotNull(server.url.value)

            val page = URL(base).readText()
            assertTrue(page.contains("MeBox 诊断日志"))
            val tokenPath = URL(base).path
            assertTrue(page.contains("$tokenPath/bundle.zip"))
            assertTrue(page.contains("运行环境"))

            val guessed = base.substringBefore("/d/") + "/d/not-the-token"
            val rejected = URL(guessed).openConnection() as HttpURLConnection
            assertEquals(404, rejected.responseCode)

            val bundle = URL("$base/bundle.zip").openConnection() as HttpURLConnection
            assertEquals(200, bundle.responseCode)
            assertEquals("application/zip", bundle.contentType)
            val bytes = bundle.inputStream.use { it.readBytes() }
            assertTrue(bytes.isNotEmpty())
        } finally {
            server.stop()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun bundleContainsDiagnosticsEntry() {
        val cacheDir = Files.createTempDirectory("mebox-bundle-test").toFile()
        try {
            val zip = LogBundle.createBundle(cacheDir)
            assertTrue(zip.isFile)
            ZipFile(zip).use { archive ->
                assertNotNull(archive.getEntry("diagnostics.txt"))
            }
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
