package app.mebox.android.feature.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSourceLoginSessionTest {
    @Test fun `close cancels pending login and polling without cancelling owner`() = runTest {
        val session = ReaderSourceLoginSession(this)
        val group = session.start()
        val response = CompletableDeferred<Unit>()
        var restartedPolling = false
        val login = launch(group) {
            response.await()
            restartedPolling = true
        }
        val poll = launch(group) { CompletableDeferred<Unit>().await() }
        runCurrent()
        session.close()
        response.complete(Unit)
        runCurrent()
        assertTrue(login.isCancelled)
        assertTrue(poll.isCancelled)
        assertNull(session.job)
        assertFalse(restartedPolling)
        assertTrue(coroutineContext[Job]!!.isActive)
    }

    @Test fun `late non cancellable response cannot restart polling after close`() = runTest {
        val session = ReaderSourceLoginSession(this)
        val response = CompletableDeferred<Unit>()
        var restartedPolling = false
        launch(session.start()) {
            withContext(NonCancellable) { response.await() }
            ensureActive()
            restartedPolling = true
        }
        runCurrent()
        session.close()
        response.complete(Unit)
        runCurrent()
        assertFalse(restartedPolling)
    }

    @Test fun `opening again cancels old source tasks but permits new source tasks`() = runTest {
        val session = ReaderSourceLoginSession(this)
        val old = session.start()
        val fresh = session.start()
        var loaded = false
        launch(fresh) { loaded = true }
        runCurrent()
        assertTrue(old.isCancelled)
        assertTrue(fresh.isActive)
        assertTrue(loaded)
        session.close()
    }

    @Test fun `owner cancellation also cancels login session`() {
        val owner = Job()
        val session = ReaderSourceLoginSession(CoroutineScope(owner))
        val group = session.start()
        owner.cancel()
        assertTrue(group.isCancelled)
    }
}
