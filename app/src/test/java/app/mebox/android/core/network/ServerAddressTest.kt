package app.mebox.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {

    private fun valid(raw: String): ServerAddress {
        val result = ServerAddress.parse(raw)
        assertTrue("expected $raw to be valid, got $result", result is ServerAddressResult.Valid)
        return (result as ServerAddressResult.Valid).address
    }

    private fun invalid(raw: String): ServerAddressError {
        val result = ServerAddress.parse(raw)
        assertTrue("expected $raw to be invalid, got $result", result is ServerAddressResult.Invalid)
        return (result as ServerAddressResult.Invalid).error
    }

    @Test
    fun `domain without scheme defaults to https`() {
        val address = valid("example.com")
        assertEquals("https", address.scheme)
        assertEquals("example.com", address.host)
        assertEquals(null, address.port)
        assertEquals("https://example.com/", address.baseUrl)
        assertEquals(false, address.isCleartext)
    }

    @Test
    fun `scheme and case are normalized`() {
        val address = valid("HTTPS://Example.COM")
        assertEquals("https", address.scheme)
        assertEquals("example.com", address.host)
        assertEquals("https://example.com", address.origin)
    }

    @Test
    fun `port without scheme is preserved`() {
        val address = valid("example.com:8443")
        assertEquals("https", address.scheme)
        assertEquals(8443, address.port)
        assertEquals("https://example.com:8443", address.origin)
    }

    @Test
    fun `ipv4 with port and root path`() {
        val address = valid("http://192.168.1.10:8080/")
        assertEquals("http", address.scheme)
        assertEquals("192.168.1.10", address.host)
        assertEquals(8080, address.port)
        assertTrue(address.isCleartext)
        assertEquals("http://192.168.1.10:8080/", address.baseUrl)
    }

    @Test
    fun `bracketed ipv6 is supported`() {
        val address = valid("http://[fe80::1]:8080")
        assertEquals("fe80::1", address.host)
        assertEquals(8080, address.port)
        assertEquals("http://[fe80::1]:8080", address.origin)
        assertEquals("http://[fe80::1]:8080/", address.baseUrl)
    }

    @Test
    fun `full ipv6 without port is supported`() {
        val address = valid("https://[2001:db8::1]")
        assertEquals("2001:db8::1", address.host)
        assertEquals(null, address.port)
    }

    @Test
    fun `trailing whitespace is trimmed`() {
        val address = valid("  https://example.com  ")
        assertEquals("example.com", address.host)
    }

    @Test
    fun `empty input is rejected`() {
        assertEquals(ServerAddressError.EMPTY, invalid("   "))
    }

    @Test
    fun `unsupported scheme is rejected`() {
        assertEquals(ServerAddressError.UNSUPPORTED_SCHEME, invalid("ftp://example.com"))
        assertEquals(ServerAddressError.UNSUPPORTED_SCHEME, invalid("file:///tmp/x"))
    }

    @Test
    fun `embedded credentials are rejected`() {
        assertEquals(ServerAddressError.EMBEDDED_CREDENTIALS, invalid("https://user:pass@example.com"))
    }

    @Test
    fun `query and fragment are rejected`() {
        assertEquals(ServerAddressError.QUERY_OR_FRAGMENT, invalid("https://example.com/?a=1"))
        assertEquals(ServerAddressError.QUERY_OR_FRAGMENT, invalid("https://example.com#section"))
    }

    @Test
    fun `sub path is rejected with a clear error`() {
        assertEquals(ServerAddressError.SUBPATH_UNSUPPORTED, invalid("https://example.com/mebox"))
        assertEquals(ServerAddressError.SUBPATH_UNSUPPORTED, invalid("https://example.com/mebox/"))
    }

    @Test
    fun `invalid ports are rejected`() {
        assertEquals(ServerAddressError.INVALID_PORT, invalid("https://example.com:abc"))
        assertEquals(ServerAddressError.INVALID_PORT, invalid("https://example.com:0"))
        assertEquals(ServerAddressError.INVALID_PORT, invalid("https://example.com:70000"))
        assertEquals(ServerAddressError.INVALID_PORT, invalid("https://example.com:"))
    }

    @Test
    fun `invalid hostnames are rejected`() {
        assertEquals(ServerAddressError.INVALID_ADDRESS, invalid("https://exa mple.com"))
        assertEquals(ServerAddressError.INVALID_HOST, invalid("https://256.1.1.1"))
        assertEquals(ServerAddressError.INVALID_HOST, invalid("https://-bad.example.com"))
    }

    @Test
    fun `unbracketed ipv6 is rejected`() {
        assertEquals(ServerAddressError.INVALID_HOST, invalid("https://::1"))
        assertEquals(ServerAddressError.INVALID_HOST, invalid("https://fe80::1"))
        assertEquals(ServerAddressError.INVALID_HOST, invalid("https://[zz::1]"))
    }

    @Test
    fun `localhost is allowed for local deployments`() {
        val address = valid("http://localhost:3000")
        assertEquals("localhost", address.host)
        assertEquals(3000, address.port)
    }
}
