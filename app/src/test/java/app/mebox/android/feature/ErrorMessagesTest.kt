package app.mebox.android.feature

import app.mebox.android.core.network.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorMessagesTest {

    private fun failure(status: Int, code: String) =
        ApiResult.Failure(status = status, code = code, message = "raw server text")

    @Test
    fun `missing mobile endpoint is reported as not-a-meBox-server`() {
        assertEquals(
            "服务器未提供 MeBox 移动端接口，请确认地址或升级服务端",
            ErrorMessages.connection(failure(404, "INVALID_ARGUMENT")),
        )
    }

    @Test
    fun `rejected connection does not expose raw text`() {
        assertEquals(
            "服务器拒绝了连接（HTTP 403）",
            ErrorMessages.connection(failure(403, "PERMISSION_DENIED")),
        )
    }

    @Test
    fun `temporary service failure is retryable copy`() {
        assertEquals(
            "服务器暂时不可用，请稍后重试",
            ErrorMessages.connection(failure(503, "SERVICE_UNAVAILABLE")),
        )
    }

    @Test
    fun `login error codes have stable messages`() {
        assertEquals("用户名或密码错误", ErrorMessages.action(failure(401, "INVALID_CREDENTIALS")))
        assertEquals("账号已到期", ErrorMessages.action(failure(403, "ACCOUNT_EXPIRED")))
        assertEquals("登录已失效，请重新登录", ErrorMessages.action(failure(401, "SESSION_REVOKED")))
        assertEquals("尝试过于频繁，请稍后再试", ErrorMessages.action(failure(429, "RATE_LIMITED")))
    }

    @Test
    fun `unknown error does not leak server body`() {
        assertEquals(
            "请求失败（HTTP 418）",
            ErrorMessages.action(failure(418, "SOMETHING_NEW")),
        )
    }
}
