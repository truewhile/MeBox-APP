package app.mebox.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCompatibilityTest {

    private fun info(apiVersion: String = "v1", minCode: Int = 1) = ServerInfo(
        serverId = "s",
        serverVersion = "MeBox-test",
        apiVersion = apiVersion,
        minAppVersionCode = minCode,
    )

    @Test
    fun `current v1 server is compatible`() {
        assertFalse(ServerCompatibility.requiresUpgrade(info(), appVersionCode = 1))
    }

    @Test
    fun `server requiring newer app blocks login`() {
        assertTrue(ServerCompatibility.requiresUpgrade(info(minCode = 99), appVersionCode = 1))
        assertEquals(
            "服务器要求更新版本的 App（最低 versionCode 99）",
            ServerCompatibility.upgradeMessage(info(minCode = 99), appVersionCode = 1),
        )
    }

    @Test
    fun `non v1 api blocks login`() {
        assertTrue(ServerCompatibility.requiresUpgrade(info(apiVersion = "v2"), appVersionCode = 1))
        assertEquals(
            "服务器版本过旧，需要升级 MeBox 服务端",
            ServerCompatibility.upgradeMessage(info(apiVersion = "v2"), appVersionCode = 1),
        )
    }
}
