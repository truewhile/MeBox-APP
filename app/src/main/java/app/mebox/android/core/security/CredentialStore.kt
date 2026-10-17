package app.mebox.android.core.security

import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.model.Credentials

/**
 * 凭证存储。写入必须是原子且完整的令牌对；读失败表示需要重新登录，
 * 不能返回部分字段让调用方拼出无效会话。
 */
interface CredentialStore {
    fun read(scope: AccountScope): Credentials?
    fun write(scope: AccountScope, credentials: Credentials)
    fun clear(scope: AccountScope)
}

/** 内存实现用于单元测试和 Compose 预览。 */
class InMemoryCredentialStore : CredentialStore {
    private val entries = mutableMapOf<String, Credentials>()

    private fun key(scope: AccountScope) = "${scope.serverId}\u0000${scope.userId}"

    @Synchronized
    override fun read(scope: AccountScope): Credentials? = entries[key(scope)]

    @Synchronized
    override fun write(scope: AccountScope, credentials: Credentials) {
        entries[key(scope)] = credentials
    }

    @Synchronized
    override fun clear(scope: AccountScope) {
        entries.remove(key(scope))
    }
}
