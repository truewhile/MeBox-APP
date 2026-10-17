package app.mebox.android.feature

import app.mebox.android.core.network.ApiResult

/** 服务端错误码到用户可见文案的映射，保持页面不直接拼接原始错误。 */
internal object ErrorMessages {

    fun connection(failure: ApiResult.Failure): String = when {
        failure.status == 404 ->
            "服务器未提供 MeBox 移动端接口，请确认地址或升级服务端"
        failure.status == 401 || failure.status == 403 ->
            "服务器拒绝了连接（HTTP ${failure.status}）"
        failure.code == "SERVICE_UNAVAILABLE" ->
            "服务器暂时不可用，请稍后重试"
        else -> action(failure)
    }

    fun action(failure: ApiResult.Failure): String = when (failure.code) {
        "INVALID_CREDENTIALS" -> "用户名或密码错误"
        "TOKEN_EXPIRED" -> "登录已过期，请重新登录"
        "SESSION_REVOKED" -> "登录已失效，请重新登录"
        "ACCOUNT_DISABLED" -> "账号已被禁用"
        "ACCOUNT_EXPIRED" -> "账号已到期"
        "RATE_LIMITED" -> "尝试过于频繁，请稍后再试"
        "PERMISSION_DENIED" -> "没有访问权限"
        "SERVICE_UNAVAILABLE" -> "服务器暂时不可用，请稍后重试"
        else -> "请求失败（HTTP ${failure.status}）"
    }
}
