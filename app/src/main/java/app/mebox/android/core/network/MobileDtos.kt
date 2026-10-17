package app.mebox.android.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerInfo(
    val name: String = "MeBox",
    @SerialName("server_id") val serverId: String,
    @SerialName("server_version") val serverVersion: String = "",
    @SerialName("api_version") val apiVersion: String = "v1",
    @SerialName("min_supported_app_version_code") val minAppVersionCode: Int = 1,
    val capabilities: ServerCapabilities = ServerCapabilities(),
)

@Serializable
data class ServerCapabilities(
    @SerialName("device_sessions") val deviceSessions: Boolean = false,
    @SerialName("playback_sessions") val playbackSessions: Boolean = false,
    @SerialName("reader_progress_v1") val readerProgressV1: Boolean = false,
    @SerialName("reader_content_batch") val readerContentBatch: Boolean = false,
    @SerialName("local_transcode") val localTranscode: Boolean = false,
)

@Serializable
data class MobileUser(
    val id: String,
    val username: String = "",
    val nickname: String = "",
    val role: String = "user",
    val tier: String = "free",
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("force_password_reset") val forcePasswordReset: Boolean = false,
)

@Serializable
data class TokenPair(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("token_type") val tokenType: String = "Bearer",
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("client_name") val clientName: String = "MeBox Android",
    @SerialName("client_version") val clientVersion: String,
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class AuthResponse(
    @SerialName("auth_session_id") val authSessionId: String,
    val user: MobileUser,
    val tokens: TokenPair,
)

@Serializable
data class SessionDevice(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("client_name") val clientName: String = "",
    @SerialName("client_version") val clientVersion: String = "",
    @SerialName("last_used_at") val lastUsedAt: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
)

@Serializable
data class SessionResponse(
    @SerialName("auth_session_id") val authSessionId: String,
    val user: MobileUser,
    val session: SessionDevice? = null,
)

@Serializable
data class MobileApiError(
    val code: String = "",
    val message: String = "",
    /** 旧接口（如 /api/reader/...）的错误字段。 */
    val error: String = "",
    @SerialName("request_id") val requestId: String = "",
    val retryable: Boolean = false,
    @SerialName("retry_after_sec") val retryAfterSec: Int? = null,
)
