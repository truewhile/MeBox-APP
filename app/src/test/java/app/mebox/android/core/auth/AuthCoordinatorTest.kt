package app.mebox.android.core.auth

import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.model.Credentials
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.security.InMemoryCredentialStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthCoordinatorTest {

    private val scope = AccountScope(serverId = "server-1", userId = "user-1")

    private fun initialCredentials(
        accessToken: String = "old-access",
        refreshToken: String = "old-refresh",
    ) = Credentials(
        serverId = scope.serverId,
        userId = scope.userId,
        sessionId = "session-1",
        accessToken = accessToken,
        refreshToken = refreshToken,
        accessExpiresAtEpochSec = 0,
    )

    private class FakeRefresher(
        var outcome: RefreshOutcome,
        private val delayMs: Long = 0,
        private val onRefresh: suspend () -> Unit = {},
    ) : SessionRefresher {
        var calls = 0
            private set

        override suspend fun refresh(scope: AccountScope, refreshToken: String): RefreshOutcome {
            calls += 1
            if (delayMs > 0) delay(delayMs)
            onRefresh()
            return outcome
        }
    }

    private fun unauthorized(): ApiResult<String> =
        ApiResult.Failure(401, "TOKEN_EXPIRED", "expired")

    private fun rotated(): RefreshOutcome = RefreshOutcome.Rotated(
        sessionId = "session-1",
        accessToken = "new-access",
        refreshToken = "new-refresh",
        expiresAtEpochSec = 1_900_000_000,
    )

    @Test
    fun `twenty concurrent 401s trigger exactly one refresh`() = runTest {
        val store = InMemoryCredentialStore()
        store.write(scope, initialCredentials())
        val refresher = FakeRefresher(rotated(), delayMs = 50)
        val coordinator = AuthCoordinator(store, refresher)

        val results = coroutineScope {
            (1..20).map {
                async {
                    coordinator.authorized(scope) { token ->
                        if (token == "new-access") ApiResult.Success(token) else unauthorized()
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, refresher.calls)
        assertTrue("all requests should succeed: $results", results.all { it is ApiResult.Success })
        assertEquals("new-access", store.read(scope)?.accessToken)
        assertEquals("new-refresh", store.read(scope)?.refreshToken)
    }

    @Test
    fun `refreshed tokens are persisted before the retry request`() = runTest {
        val store = InMemoryCredentialStore()
        store.write(scope, initialCredentials())
        val coordinator = AuthCoordinator(store, FakeRefresher(rotated()))

        var storedDuringRetry: String? = null
        val result = coordinator.authorized(scope) { token ->
            if (token == "new-access") {
                storedDuringRetry = store.read(scope)?.accessToken
                ApiResult.Success(token)
            } else {
                unauthorized()
            }
        }

        assertTrue(result is ApiResult.Success)
        assertEquals("new-access", storedDuringRetry)
    }

    @Test
    fun `network failure keeps credentials and reports retryable`() = runTest {
        val store = InMemoryCredentialStore()
        store.write(scope, initialCredentials())
        val coordinator = AuthCoordinator(store, FakeRefresher(RefreshOutcome.Temporary))

        val result = coordinator.authorized(scope) { unauthorized() }

        assertTrue(result is ApiResult.Failure)
        result as ApiResult.Failure
        assertEquals(503, result.status)
        assertTrue(result.retryable)
        assertEquals("old-access", store.read(scope)?.accessToken)
        assertEquals("old-refresh", store.read(scope)?.refreshToken)
    }

    @Test
    fun `explicit refresh rejection clears credentials`() = runTest {
        val store = InMemoryCredentialStore()
        store.write(scope, initialCredentials())
        val coordinator = AuthCoordinator(store, FakeRefresher(RefreshOutcome.Reauthenticate))

        val result = coordinator.authorized(scope) { unauthorized() }

        assertTrue(result is ApiResult.Failure && result.status == 401)
        assertNull(store.read(scope))
    }

    @Test
    fun `second 401 does not trigger a second refresh`() = runTest {
        val store = InMemoryCredentialStore()
        store.write(scope, initialCredentials())
        val refresher = FakeRefresher(rotated())
        val coordinator = AuthCoordinator(store, refresher)

        val result = coordinator.authorized(scope) { unauthorized() }

        assertTrue(result is ApiResult.Failure && result.status == 401)
        assertEquals(1, refresher.calls)
    }

    @Test
    fun `late refresh result is discarded after account switch`() = runTest {
        val store = InMemoryCredentialStore()
        store.write(scope, initialCredentials())
        val refresher = FakeRefresher(rotated(), delayMs = 1_000)
        val coordinator = AuthCoordinator(store, refresher)

        var result: ApiResult<String>? = null
        val job = launch {
            result = coordinator.authorized(scope) { unauthorized() }
        }
        runCurrent()
        coordinator.onAccountChanged()
        advanceUntilIdle()
        job.join()

        assertTrue("expected Stale, got $result", result is ApiResult.Stale)
        // 旧账号的刷新结果不能覆盖存储。
        assertEquals("old-access", store.read(scope)?.accessToken)
        assertEquals("old-refresh", store.read(scope)?.refreshToken)
    }

    @Test
    fun `non auth failures are returned without refreshing`() = runTest {
        val store = InMemoryCredentialStore()
        store.write(scope, initialCredentials())
        val refresher = FakeRefresher(rotated())
        val coordinator = AuthCoordinator(store, refresher)

        val result = coordinator.authorized(scope) {
            ApiResult.Failure(403, "PERMISSION_DENIED", "no access")
        }

        assertTrue(result is ApiResult.Failure && result.status == 403)
        assertEquals(0, refresher.calls)
        assertEquals("old-access", store.read(scope)?.accessToken)
    }
}
