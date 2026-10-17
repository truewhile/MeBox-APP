package app.mebox.android.feature

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.mebox.android.BuildConfig
import app.mebox.android.core.auth.MobileSessionRepository
import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.network.MobileUser
import app.mebox.android.core.network.ServerAddress
import app.mebox.android.core.network.ServerAddressError
import app.mebox.android.core.network.ServerAddressResult
import app.mebox.android.core.network.ServerCompatibility
import app.mebox.android.core.network.ServerInfo
import app.mebox.android.core.player.PlayerEngineKind
import app.mebox.android.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AppUiState {
    data object Loading : AppUiState

    data class Connection(
        val addressText: String,
        val testing: Boolean = false,
        val error: String? = null,
        val notice: String? = null,
        val pendingCleartext: ServerAddress? = null,
    ) : AppUiState

    data class Login(
        val address: ServerAddress,
        val serverInfo: ServerInfo,
        val username: String = "",
        val submitting: Boolean = false,
        val error: String? = null,
    ) : AppUiState

    data class Account(
        val address: ServerAddress,
        val serverInfo: ServerInfo,
        val scope: AccountScope,
        val user: MobileUser,
        val deviceLabel: String,
        val busy: Boolean = false,
        val notice: String? = null,
    ) : AppUiState
}

/**
 * 单 Activity 的顶层状态机：连接 → 登录 → 账号。
 *
 * 会话恢复在启动时执行；只有明确的会话撤销/账号状态才会清除本地凭证，
 * 临时网络故障保留凭证并提示重试。
 */
class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<AppUiState>(AppUiState.Loading)
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    /**
     * 当前选择的播放内核。选择结果保存在本机，玩家在「我的 → 播放设置」
     * 或播放页的「更多」面板里切换后立即生效。
     */
    private val _playerEngine = MutableStateFlow(
        PlayerEngineKind.fromKey(container.preferences.playerEngine),
    )
    val playerEngine: StateFlow<PlayerEngineKind> = _playerEngine.asStateFlow()

    fun selectPlayerEngine(kind: PlayerEngineKind) {
        container.preferences.playerEngine = kind.key
        _playerEngine.value = kind
    }

    private var repository: MobileSessionRepository? = null

    init {
        viewModelScope.launch { restoreSession() }
    }

    fun onAddressChange(value: String) {
        val current = _state.value as? AppUiState.Connection ?: return
        _state.value = current.copy(addressText = value, error = null)
    }

    fun onUsernameChange(value: String) {
        val current = _state.value as? AppUiState.Login ?: return
        _state.value = current.copy(username = value, error = null)
    }

    fun connect() {
        val current = _state.value as? AppUiState.Connection ?: return
        val parsed = ServerAddress.parse(current.addressText)
        if (parsed is ServerAddressResult.Invalid) {
            _state.value = current.copy(error = addressErrorMessage(parsed.error))
            return
        }
        val address = (parsed as ServerAddressResult.Valid).address
        if (address.isCleartext && !container.preferences.cleartextRiskAccepted) {
            _state.value = current.copy(pendingCleartext = address, error = null)
            return
        }
        testConnection(address)
    }

    fun confirmCleartext() {
        val current = _state.value as? AppUiState.Connection ?: return
        val pending = current.pendingCleartext ?: return
        container.preferences.cleartextRiskAccepted = true
        _state.value = current.copy(pendingCleartext = null)
        testConnection(pending)
    }

    fun dismissCleartext() {
        val current = _state.value as? AppUiState.Connection ?: return
        _state.value = current.copy(pendingCleartext = null)
    }

    fun login(password: String) {
        val current = _state.value as? AppUiState.Login ?: return
        val repo = repository ?: return
        val username = current.username.trim()
        if (username.isEmpty() || password.isEmpty()) {
            _state.value = current.copy(error = "请输入用户名和密码")
            return
        }
        _state.value = current.copy(submitting = true, error = null)
        viewModelScope.launch {
            val result = repo.login(
                username = username,
                password = password,
                deviceId = container.preferences.deviceId(),
                deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android",
                clientVersion = BuildConfig.VERSION_NAME,
            )
            when (result) {
                is ApiResult.Success -> {
                    val scope = AccountScope(repo.serverInfo.serverId, result.value.user.id)
                    container.preferences.saveAccount(scope.serverId, scope.userId)
                    showAccount(current.address, repo, scope, result.value.user, fallbackDevice = Build.MODEL)
                }
                is ApiResult.Failure -> {
                    _state.value = current.copy(submitting = false, error = failureMessage(result))
                }
                is ApiResult.NetworkError -> {
                    _state.value = current.copy(
                        submitting = false,
                        error = "无法连接服务器，请检查网络后重试",
                    )
                }
                ApiResult.Stale -> {
                    _state.value = current.copy(submitting = false, error = "请求已失效，请重试")
                }
            }
        }
    }

    fun logout() {
        val current = _state.value as? AppUiState.Account ?: return
        val repo = repository ?: return
        _state.value = current.copy(busy = true, notice = null)
        viewModelScope.launch {
            val remote = repo.logout(current.scope)
            container.watchProgress.clear(current.scope)
            container.readerAudio.stop()
            container.readerCache.clearAccount(current.scope)
            container.preferences.clearAccount()
            val unconfirmed = remote is ApiResult.Failure || remote is ApiResult.NetworkError
            _state.value = AppUiState.Connection(
                addressText = current.address.origin,
                notice = if (unconfirmed) "本机已退出，远端未确认" else "已退出登录",
            )
        }
    }

    fun backToConnection() {
        val current = _state.value as? AppUiState.Login ?: return
        _state.value = AppUiState.Connection(addressText = current.address.origin)
    }

    fun clearMessages() {
        when (val current = _state.value) {
            is AppUiState.Connection -> _state.value = current.copy(error = null, notice = null)
            is AppUiState.Login -> _state.value = current.copy(error = null)
            is AppUiState.Account -> _state.value = current.copy(notice = null)
            AppUiState.Loading -> Unit
        }
    }

    private suspend fun restoreSession() {
        val savedAddress = container.preferences.lastAddress
        if (savedAddress.isNullOrBlank()) {
            _state.value = AppUiState.Connection(addressText = "")
            return
        }
        val parsed = ServerAddress.parse(savedAddress)
        if (parsed is ServerAddressResult.Invalid) {
            _state.value = AppUiState.Connection(
                addressText = savedAddress,
                notice = "上次保存的服务器地址无效，请重新填写",
            )
            return
        }
        val address = (parsed as ServerAddressResult.Valid).address
        if (address.isCleartext && !container.preferences.cleartextRiskAccepted) {
            _state.value = AppUiState.Connection(
                addressText = address.origin,
                notice = "需要重新确认局域网 HTTP 风险",
            )
            return
        }
        val serverId = container.preferences.accountServerId
        val userId = container.preferences.accountUserId
        if (serverId.isNullOrBlank() || userId.isNullOrBlank()) {
            testConnection(address, showLoginOnSuccess = true)
            return
        }

        val api = container.apiFor(address)
        when (val info = api.serverInfo()) {
            is ApiResult.Success -> {
                if (ServerCompatibility.requiresUpgrade(info.value, BuildConfig.VERSION_CODE)) {
                    _state.value = AppUiState.Connection(
                        addressText = address.origin,
                        error = ServerCompatibility.upgradeMessage(
                            info.value,
                            BuildConfig.VERSION_CODE,
                        ),
                    )
                    return
                }
                val repo = MobileSessionRepository(info.value, api, container.credentialStore)
                repository = repo
                if (info.value.serverId != serverId) {
                    // 地址指向了另一台实例：旧凭证不属于它，必须重新登录。
                    container.credentialStore.clear(AccountScope(serverId, userId))
                    container.preferences.clearAccount()
                    _state.value = AppUiState.Login(address, info.value, error = "服务器实例已变化，请重新登录")
                    return
                }
                val scope = AccountScope(serverId, userId)
                when (val session = repo.loadSession(scope)) {
                    is ApiResult.Success -> {
                        _state.value = AppUiState.Account(
                            address = address,
                            serverInfo = info.value,
                            scope = scope,
                            user = session.value.user,
                            deviceLabel = session.value.session?.deviceName
                                ?.takeIf { it.isNotBlank() } ?: "Android",
                        )
                    }
                    is ApiResult.Failure -> {
                        if (session.status == 401) {
                            repo.coordinator.onAccountChanged()
                            container.credentialStore.clear(scope)
                            container.watchProgress.clear(scope)
                            container.preferences.clearAccount()
                            _state.value = AppUiState.Login(
                                address = address,
                                serverInfo = info.value,
                                error = "登录已失效，请重新登录",
                            )
                        } else {
                            _state.value = AppUiState.Connection(
                                addressText = address.origin,
                                notice = failureMessage(session),
                            )
                        }
                    }
                    is ApiResult.NetworkError -> {
                        _state.value = AppUiState.Connection(
                            addressText = address.origin,
                            notice = "暂时无法连接，登录状态已保留，请稍后重试",
                        )
                    }
                    ApiResult.Stale -> {
                        _state.value = AppUiState.Connection(addressText = address.origin)
                    }
                }
            }
            is ApiResult.Failure -> {
                _state.value = AppUiState.Connection(
                    addressText = address.origin,
                    notice = connectionFailureMessage(info),
                )
            }
            is ApiResult.NetworkError -> {
                _state.value = AppUiState.Connection(
                    addressText = address.origin,
                    notice = "暂时无法连接服务器，登录状态已保留",
                )
            }
            ApiResult.Stale -> _state.value = AppUiState.Connection(addressText = address.origin)
        }
    }

    private fun testConnection(address: ServerAddress, showLoginOnSuccess: Boolean = true) {
        val previous = _state.value as? AppUiState.Connection
        if (previous != null) {
            _state.value = previous.copy(
                addressText = address.origin,
                testing = true,
                error = null,
                notice = null,
            )
        } else {
            _state.value = AppUiState.Connection(addressText = address.origin, testing = true)
        }
        viewModelScope.launch {
            val api = container.apiFor(address)
            when (val result = api.serverInfo()) {
                is ApiResult.Success -> {
                    val info = result.value
                    if (ServerCompatibility.requiresUpgrade(info, BuildConfig.VERSION_CODE)) {
                        _state.value = AppUiState.Connection(
                            addressText = address.origin,
                            error = ServerCompatibility.upgradeMessage(info, BuildConfig.VERSION_CODE),
                        )
                        return@launch
                    }
                    container.preferences.lastAddress = address.origin
                    repository = MobileSessionRepository(info, api, container.credentialStore)
                    if (showLoginOnSuccess) {
                        _state.value = AppUiState.Login(address, info)
                    }
                }
                is ApiResult.Failure -> {
                    _state.value = AppUiState.Connection(
                        addressText = address.origin,
                        error = connectionFailureMessage(result),
                    )
                }
                is ApiResult.NetworkError -> {
                    _state.value = AppUiState.Connection(
                        addressText = address.origin,
                        error = "无法连接服务器，请检查地址、证书和网络",
                    )
                }
                ApiResult.Stale -> {
                    _state.value = AppUiState.Connection(addressText = address.origin)
                }
            }
        }
    }

    private suspend fun showAccount(
        address: ServerAddress,
        repo: MobileSessionRepository,
        scope: AccountScope,
        user: MobileUser,
        fallbackDevice: String?,
    ) {
        when (val session = repo.loadSession(scope)) {
            is ApiResult.Success -> {
                _state.value = AppUiState.Account(
                    address = address,
                    serverInfo = repo.serverInfo,
                    scope = scope,
                    user = session.value.user,
                    deviceLabel = session.value.session?.deviceName
                        ?.takeIf { it.isNotBlank() } ?: fallbackDevice ?: "Android",
                )
            }
            else -> {
                // 登录本身已成功：会话详情拉取失败不把用户退回登录页。
                _state.value = AppUiState.Account(
                    address = address,
                    serverInfo = repo.serverInfo,
                    scope = scope,
                    user = user,
                    deviceLabel = fallbackDevice ?: "Android",
                    notice = "会话详情暂不可用",
                )
            }
        }
    }

    private fun connectionFailureMessage(failure: ApiResult.Failure): String =
        ErrorMessages.connection(failure)

    private fun failureMessage(failure: ApiResult.Failure): String =
        ErrorMessages.action(failure)

    private fun addressErrorMessage(error: ServerAddressError): String = when (error) {
        ServerAddressError.EMPTY -> "请输入服务器地址"
        ServerAddressError.INVALID_ADDRESS -> "地址格式不正确"
        ServerAddressError.UNSUPPORTED_SCHEME -> "只支持 http 或 https 地址"
        ServerAddressError.INVALID_HOST -> "服务器主机名或 IP 不正确"
        ServerAddressError.INVALID_PORT -> "端口必须在 1—65535 之间"
        ServerAddressError.EMBEDDED_CREDENTIALS -> "请不要把用户名或密码写进地址"
        ServerAddressError.QUERY_OR_FRAGMENT -> "地址不能包含查询参数或锚点"
        ServerAddressError.SUBPATH_UNSUPPORTED -> "首版只支持根路径部署，不能包含子路径"
    }

    fun mediaFactory(scope: AccountScope, address: ServerAddress): ViewModelProvider.Factory? {
        val repo = repository ?: return null
        return app.mebox.android.feature.media.MediaViewModel.Factory(
            repository = repo.mediaRepository(scope),
            origin = address.origin,
            scope = scope,
            credentialStore = container.credentialStore,
            progressStore = container.watchProgress,
            codecSupport = container.deviceCodecSupport,
        )
    }

    fun readerFactory(scope: AccountScope, address: ServerAddress): ViewModelProvider.Factory? {
        val repo = repository ?: return null
        return app.mebox.android.feature.reader.ReaderViewModel.Factory(
            repository = repo.readerRepository(scope),
            scope = scope,
            preferences = container.readerPreferences,
            cache = container.readerCache,
            audio = container.readerAudio,
            origin = address.origin,
        )
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MainViewModel::class.java)) {
                "unsupported view model: ${modelClass.name}"
            }
            return MainViewModel(container) as T
        }
    }
}
