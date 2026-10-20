package app.mebox.android.core.auth

import app.mebox.android.core.media.MediaRepository
import app.mebox.android.core.reader.ReaderRepository
import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.model.Credentials
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.network.AuthResponse
import app.mebox.android.core.network.LoginRequest
import app.mebox.android.core.network.MeBoxApiClient
import app.mebox.android.core.network.ServerInfo
import app.mebox.android.core.network.SessionResponse
import app.mebox.android.core.security.CredentialStore

/**
 * 单个服务器上的会话操作。所有需要令牌的读取都经过 [AuthCoordinator]，
 * 保证单次刷新、凭证先落盘后使用。
 */
class MobileSessionRepository(
    val serverInfo: ServerInfo,
    private val api: MeBoxApiClient,
    private val store: CredentialStore,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : SessionRefresher {

    val coordinator = AuthCoordinator(store, this)

    suspend fun login(
        username: String,
        password: String,
        deviceId: String,
        deviceName: String,
        clientVersion: String,
    ): ApiResult<AuthResponse> {
        val result = api.login(
            LoginRequest(
                username = username,
                password = password,
                deviceId = deviceId,
                deviceName = deviceName,
                clientName = CLIENT_NAME,
                clientVersion = clientVersion,
            )
        )
        if (result is ApiResult.Success) {
            val scope = AccountScope(serverInfo.serverId, result.value.user.id)
            // 新登录视为账号代次变化：任何旧账号的在途刷新结果都应被丢弃。
            coordinator.onAccountChanged()
            store.write(scope, credentialsFor(scope, result.value))
        }
        return result
    }

    suspend fun loadSession(scope: AccountScope): ApiResult<SessionResponse> =
        coordinator.authorized(scope) { accessToken -> api.session(accessToken) }

    suspend fun logout(scope: AccountScope): ApiResult<Unit> {
        val credentials = store.read(scope)
        val remote = if (credentials != null) {
            when (val result = api.logout(credentials.accessToken)) {
                is ApiResult.Success -> ApiResult.Success(Unit)
                is ApiResult.Failure -> result
                is ApiResult.NetworkError -> result
                ApiResult.Stale -> ApiResult.Stale
            }
        } else {
            ApiResult.Success(Unit)
        }
        // 本地退出立即生效；远端是否确认由调用方单独提示。
        store.clear(scope)
        coordinator.onAccountChanged()
        return remote
    }

    override suspend fun refresh(scope: AccountScope, refreshToken: String): RefreshOutcome {
        return when (val result = api.refresh(refreshToken)) {
            is ApiResult.Success -> {
                val auth = result.value
                RefreshOutcome.Rotated(
                    sessionId = auth.authSessionId,
                    accessToken = auth.tokens.accessToken,
                    refreshToken = auth.tokens.refreshToken,
                    expiresAtEpochSec = clock() + auth.tokens.expiresIn,
                )
            }
            is ApiResult.Failure -> {
                if (result.retryable || result.status == 429 || result.status >= 500) {
                    RefreshOutcome.Temporary
                } else {
                    RefreshOutcome.Reauthenticate
                }
            }
            is ApiResult.NetworkError -> RefreshOutcome.Temporary
            ApiResult.Stale -> RefreshOutcome.Temporary
        }
    }

    private fun credentialsFor(scope: AccountScope, auth: AuthResponse): Credentials = Credentials(
        serverId = scope.serverId,
        userId = scope.userId,
        sessionId = auth.authSessionId,
        accessToken = auth.tokens.accessToken,
        refreshToken = auth.tokens.refreshToken,
        accessExpiresAtEpochSec = clock() + auth.tokens.expiresIn,
        generation = coordinator.currentGeneration(),
    )

    fun mediaRepository(scope: AccountScope): MediaRepository =
        MediaRepository(api, coordinator, scope)

    fun readerRepository(scope: AccountScope, offlineOnly: Boolean = false): ReaderRepository =
        ReaderRepository(api, coordinator, scope, offlineOnly)

    private companion object {
        const val CLIENT_NAME = "MeBox Android"
    }
}
