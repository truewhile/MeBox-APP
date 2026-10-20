package app.mebox.android.core.reader

import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.network.ReaderBookDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ReaderProgressSyncTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = AccountScope("server", "user")
    private fun event(time: Long = 200) = ReaderProgress("book", 1, 3, updatedAtMs = time)

    @Test fun `network failure preserves pending event for successful retry`() = runTest {
        val store = ReaderProgressStore(temporary.newFolder(), Json)
        store.save(scope, event())
        var online = false
        val sent = mutableListOf<Int>()
        val sync = ReaderProgressSync(store, scope,
            loadBooks = { ApiResult.Success(listOf(ReaderBookDto(id = "book", durChapterTime = 100))) },
            send = {
                if (!online) ApiResult.NetworkError("offline", IOException("offline"))
                else { sent += it.position; ApiResult.Success(Unit) }
            },
        )
        sync.flush()
        assertEquals(1, store.pending(scope).size)
        online = true
        sync.flush()
        assertEquals(listOf(3), sent)
        assertTrue(store.pending(scope).isEmpty())
    }

    @Test fun `newer server reading is not overwritten by old offline event`() = runTest {
        val store = ReaderProgressStore(temporary.newFolder(), Json)
        store.save(scope, event())
        var sends = 0
        ReaderProgressSync(store, scope,
            loadBooks = { ApiResult.Success(listOf(ReaderBookDto(id = "book", durChapterTime = 300))) },
            send = { sends++; ApiResult.Success(Unit) },
        ).flush()
        assertEquals(0, sends)
        assertNull(store.load(scope, "book"))
    }

    @Test fun `concurrent flushes remain serial and old acknowledgement preserves later position`() = runTest {
        val store = ReaderProgressStore(temporary.newFolder(), Json)
        val first = event()
        store.save(scope, first)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<Int>()
        val sync = ReaderProgressSync(store, scope,
            loadBooks = { ApiResult.Success(listOf(ReaderBookDto(id = "book"))) },
            send = {
                sent += it.position
                if (sent.size == 1) { started.complete(Unit); release.await() }
                ApiResult.Success(Unit)
            },
        )
        val firstRequest = async { sync.flush() }
        started.await()
        store.save(scope, event(201).copy(position = 1))
        val secondRequest = async { sync.flush() }
        release.complete(Unit)
        firstRequest.await()
        secondRequest.await()
        assertEquals(listOf(3, 1), sent)
        assertTrue(store.pending(scope).isEmpty())
    }

    @Test fun `cancelled send retains durable event`() = runTest {
        val store = ReaderProgressStore(temporary.newFolder(), Json)
        store.save(scope, event())
        val started = CompletableDeferred<Unit>()
        val sync = ReaderProgressSync(store, scope,
            loadBooks = { ApiResult.Success(listOf(ReaderBookDto(id = "book"))) },
            send = { started.complete(Unit); CompletableDeferred<ApiResult<Unit>>().await() },
        )
        val request = async { sync.flush() }
        started.await()
        request.cancel()
        request.join()
        assertEquals(1, store.pending(scope).size)
    }
}
