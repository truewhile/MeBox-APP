package app.mebox.android.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class SystemHttpProxyTest {

    @Test
    fun `http proxy becomes a libvlc proxy url`() {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 7890))
        assertEquals("http://127.0.0.1:7890", httpProxyUrlOf(proxy))
    }

    @Test
    fun `direct connection has no proxy url`() {
        assertNull(httpProxyUrlOf(Proxy.NO_PROXY))
        assertNull(httpProxyUrlOf(null))
    }

    @Test
    fun `socks proxies are not supported`() {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 1080))
        assertNull(httpProxyUrlOf(proxy))
    }

    @Test
    fun `invalid port is ignored`() {
        val address = InetSocketAddress.createUnresolved("127.0.0.1", 0)
        assertNull(httpProxyUrlOf(Proxy(Proxy.Type.HTTP, address)))
    }
}
