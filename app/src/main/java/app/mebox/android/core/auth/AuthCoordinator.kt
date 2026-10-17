package app.mebox.android.core.auth

import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.model.Credentials
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.network.isAuthFailure
import app.mebox.android.core.security.CredentialStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 刷新服务返回的结果；刷新实现只负责网络与错误分类。 */
sealed interface RefreshOutcome {
    data class Rotated(
        val sessionId: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAtEpochSec: Long,
    ) : RefreshOutcome

    /** 服务端明确拒绝刷新令牌（撤销、过期、账号状态）：必须重新登录。 */
    data object Reauthenticate : RefreshOutcome

    /** 网络、超时、5xx、429：凭证保留，稍后重试。 */
    data object Temporary : RefreshOutcome
}

interface SessionRefresher {
    suspend fun refresh(scope: AccountScope, refreshToken: String): RefreshOutcome
}

/**
 * 认证协调器：所有需要令牌的请求都经过这里。
 *
 * 规则（对应设计文档 4.2）：
 * - 所有请求捕获发起时的账号 generation；
 * - 401 时只允许一个刷新真正执行，其余请求等待并复用结果；
 * - 刷新成功先原子写入存储，再让等待方继续；
 * - 每个原请求最多重放一次，第二次 401 不再刷新；
 * - 切账号/退出后 generation 变化，迟到的结果直接丢弃；
 * - 刷新网络失败保留旧凭证，明确撤销才清除。
 */
class AuthCoordinator(
    private val store: CredentialStore,
    private val refresher: SessionRefresher,
) {
    private val refreshMutex = Mutex()
    private val generationLock = Any()
    private var generation = 0L

    /** 账号或服务器变化时调用；之后旧请求的结果不再被接受。 */
    fun onAccountChanged() {
        synchronized(generationLock) { generation += 1 }
    }

    fun currentGeneration(): Long = synchronized(generationLock) { generation }

    suspend fun <T> authorized(
        scope: AccountScope,
        call: suspend (accessToken: String) -> ApiResult<T>,
    ): ApiResult<T> {
        val startGeneration = currentGeneration()
        val initial = store.read(scope) ?: return reauthFailure()
        val first = call(initial.accessToken)
        if (currentGeneration() != startGeneration) return ApiResult.Stale
        if (!first.isAuthFailure()) return first

        return when (val refreshed = refresh(scope, initial.accessToken, startGeneration)) {
            is RefreshResult.Rotated -> {
                if (currentGeneration() != startGeneration) return ApiResult.Stale
                val retry = call(refreshed.credentials.accessToken)
                when {
                    currentGeneration() != startGeneration -> ApiResult.Stale
                    retry.isAuthFailure() -> reauthFailure()
                    else -> retry
                }
            }
            RefreshResult.Reauthenticate -> reauthFailure()
            RefreshResult.Temporary -> temporaryFailure()
            RefreshResult.StaleResult -> ApiResult.Stale
        }
    }

    private suspend fun refresh(
        scope: AccountScope,
        staleAccessToken: String,
        startGeneration: Long,
    ): RefreshResult = refreshMutex.withLock {
        if (currentGeneration() != startGeneration) return@withLock RefreshResult.StaleResult
        val current = store.read(scope) ?: return@withLock RefreshResult.Reauthenticate
        // 另一个协程已经完成刷新：直接复用新令牌，不再轮换一次。
        if (current.accessToken != staleAccessToken) {
            return@withLock RefreshResult.Rotated(current)
        }
        val outcome = try {
            refresher.refresh(scope, current.refreshToken)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RefreshOutcome.Temporary
        }
        if (currentGeneration() != startGeneration) {
            // 刷新期间账号已切换：结果属于旧账号，绝不写入当前存储。
            return@withLock RefreshResult.StaleResult
        }
        when (outcome) {
            is RefreshOutcome.Rotated -> {
                val credentials = Credentials(
                    serverId = scope.serverId,
                    userId = scope.userId,
                    sessionId = outcome.sessionId,
                    accessToken = outcome.accessToken,
                    refreshToken = outcome.refreshToken,
                    accessExpiresAtEpochSec = outcome.expiresAtEpochSec,
                    generation = current.generation,
                )
                store.write(scope, credentials)
                RefreshResult.Rotated(credentials)
            }
            RefreshOutcome.Reauthenticate -> {
                store.clear(scope)
                RefreshResult.Reauthenticate
            }
            RefreshOutcome.Temporary -> RefreshResult.Temporary
        }
    }

    private fun reauthFailure() = ApiResult.Failure(
        status = 401,
        code = "SESSION_REVOKED",
        message = "authentication required",
    )

    private fun temporaryFailure() = ApiResult.Failure(
        status = 503,
        code = "SERVICE_UNAVAILABLE",
        message = "temporarily unavailable; credentials kept",
        retryable = true,
    )

    private sealed interface RefreshResult {
        data class Rotated(val credentials: Credentials) : RefreshResult
        data object Reauthenticate : RefreshResult
        data object Temporary : RefreshResult
        data object StaleResult : RefreshResult
    }
}
