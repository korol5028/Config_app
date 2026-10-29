package com.example.configapp

import com.example.configapp.data.ConfigParser
import com.example.configapp.data.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigParserTest {

    @Test
    fun testValidVlessParsing() {
        val link = "vless://12345678-1234-1234-1234-123456789012@1.2.3.4:443?security=reality&sni=google.com&pbk=abcdef&sid=1234&type=tcp#MyServer"
        val result = ConfigParser.parse(link)
        
        assertNotNull(result)
        assertEquals(Protocol.VLESS, result?.protocol)
        assertEquals("1.2.3.4", result?.host)
        assertEquals(443, result?.port)
        assertEquals("MyServer", result?.name)
    }

    @Test
    fun testValidShadowsocksParsing() {
        val link = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNzd29yZA==@1.2.3.4:8080#SSServer"
        val result = ConfigParser.parse(link)
        
        assertNotNull(result)
        assertEquals(Protocol.SHADOWSOCKS, result?.protocol)
        assertEquals("1.2.3.4", result?.host)
        assertEquals(8080, result?.port)
        assertEquals("SSServer", result?.name)
    }

    @Test
    fun testInvalidLinkReturnsNull() {
        val link = "garbage_link_123:!!!"
        val result = ConfigParser.parse(link)
        assertNull(result)
    }

    @Test
    fun testEmptyLinkReturnsNull() {
        val result = ConfigParser.parse("")
        assertNull(result)
    }
}
