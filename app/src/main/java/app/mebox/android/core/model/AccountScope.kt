package app.mebox.android.core.model

import kotlinx.serialization.Serializable

/** 本地数据分区键：同一实例的同一账号。 */
data class AccountScope(
    val serverId: String,
    val userId: String,
)

/**
 * 已保存的登录凭证。
 *
 * [generation] 由 AuthCoordinator 维护：切换账号或退出时递增，用来丢弃
 * 迟到响应，避免旧账号的刷新结果覆盖新账号。
 */
@Serializable
data class Credentials(
    val serverId: String,
    val userId: String,
    val sessionId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtEpochSec: Long,
    val generation: Long = 0L,
)
