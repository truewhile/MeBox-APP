package app.mebox.android.core.network

/** 统一的网络结果，不让页面直接依赖 Retrofit/OkHttp 类型。 */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    data class Failure(
        val status: Int,
        val code: String,
        val message: String,
        val retryable: Boolean = false,
        val retryAfterSec: Int? = null,
        val requestId: String = "",
    ) : ApiResult<Nothing>

    data class NetworkError(
        val message: String,
        val cause: Throwable? = null,
    ) : ApiResult<Nothing>

    /**
     * 请求发起后账号 generation 发生变化：结果属于旧账号，必须丢弃，
     * 不能写入新账号的本地存储。
     */
    data object Stale : ApiResult<Nothing>
}

fun ApiResult<*>.isAuthFailure(): Boolean =
    this is ApiResult.Failure && status == 401

fun ApiResult<*>.isTemporaryFailure(): Boolean =
    this is ApiResult.NetworkError ||
        (this is ApiResult.Failure && (retryable || status >= 500 || status == 429))
