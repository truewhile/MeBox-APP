package app.mebox.android.core.reader

import app.mebox.android.core.auth.AuthCoordinator
import app.mebox.android.core.auth.RefreshOutcome
import app.mebox.android.core.auth.SessionRefresher
import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.model.Credentials
import app.mebox.android.core.network.*
import app.mebox.android.core.security.InMemoryCredentialStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ReaderOfflineRepositoryTest {
    @Test fun `offline repository blocks reads writes and token refresh before HTTP`() = runTest {
        MockWebServer().use { server ->
            val address = (ServerAddress.parse(server.url("/").toString()) as ServerAddressResult.Valid).address
            val api = MeBoxApiClient(createMobileApi(address, OkHttpClient(), Json), Json)
            val scope = AccountScope("server", "user")
            val credentials = InMemoryCredentialStore().apply {
                write(scope, Credentials("server", "user", "session", "access", "refresh", 0))
            }
            val coordinator = AuthCoordinator(credentials, object : SessionRefresher {
                override suspend fun refresh(scope: AccountScope, refreshToken: String): RefreshOutcome {
                    error("offline mode must never refresh credentials")
                }
            })
            val repo = ReaderRepository(api, coordinator, scope, offlineOnly = true)
            assertTrue(repo.shelf() is ApiResult.NetworkError)
            assertTrue(repo.chapters("book") is ApiResult.NetworkError)
            assertTrue(repo.content("book", 0) is ApiResult.NetworkError)
            assertTrue(repo.saveProgress("book", 0, 1, "chapter") is ApiResult.NetworkError)
            assertTrue(repo.removeBook("book") is ApiResult.NetworkError)
            assertTrue(repo.profile() is ApiResult.NetworkError)
            assertTrue(repo.search("name", 1) is ApiResult.NetworkError)
            assertEquals(0, server.requestCount)
        }
    }
}
