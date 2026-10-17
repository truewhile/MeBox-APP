package app.mebox.android.core.network

/** 服务器与客户端的兼容性判断。 */
object ServerCompatibility {

    fun requiresUpgrade(info: ServerInfo, appVersionCode: Int): Boolean =
        info.apiVersion != MobileApiVersion || info.minAppVersionCode > appVersionCode

    fun upgradeMessage(info: ServerInfo, appVersionCode: Int): String = when {
        info.minAppVersionCode > appVersionCode ->
            "服务器要求更新版本的 App（最低 versionCode ${info.minAppVersionCode}）"
        else ->
            "服务器版本过旧，需要升级 MeBox 服务端"
    }

    const val MobileApiVersion = "v1"
}
