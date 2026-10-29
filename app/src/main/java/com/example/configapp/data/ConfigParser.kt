package com.example.configapp.data

import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object ConfigParser {

    fun parse(link: String): ProxyConfig? {
        val trimmed = link.trim()
        return when {
            trimmed.startsWith("vless://", true) -> parseVless(trimmed)
            trimmed.startsWith("vmess://", true) -> parseVmess(trimmed)
            trimmed.startsWith("trojan://", true) -> parseTrojan(trimmed)
            trimmed.startsWith("ss://", true) -> parseShadowsocks(trimmed)
            trimmed.startsWith("hysteria2://", true) || trimmed.startsWith("hy2://", true) -> parseHysteria2(trimmed)
            else -> null
        }
    }

    private fun parseVless(link: String): ProxyConfig? {
        return try {
            val body = link.removePrefix("vless://")
            val hashIdx = body.indexOf('#')
            val name = if (hashIdx >= 0) decode(body.substring(hashIdx + 1)) else "VLESS"
            val main = if (hashIdx >= 0) body.substring(0, hashIdx) else body
            val qIdx = main.indexOf('?')
            val query = if (qIdx >= 0) main.substring(qIdx + 1) else ""
            val hostPart = if (qIdx >= 0) main.substring(0, qIdx) else main
            val atIdx = hostPart.lastIndexOf('@')
            if (atIdx < 0) return null
            val uuid = hostPart.substring(0, atIdx)
            val (host, port) = splitHostPort(hostPart.substring(atIdx + 1)) ?: return null
            ProxyConfig(UUID.randomUUID().toString(), name, Protocol.VLESS, host, port, link,
                parseQuery(query) + ("uuid" to uuid))
        } catch (e: Exception) { null }
    }

    private fun parseTrojan(link: String): ProxyConfig? {
        return try {
            val body = link.removePrefix("trojan://")
            val hashIdx = body.indexOf('#')
            val name = if (hashIdx >= 0) decode(body.substring(hashIdx + 1)) else "Trojan"
            val main = if (hashIdx >= 0) body.substring(0, hashIdx) else body
            val qIdx = main.indexOf('?')
            val query = if (qIdx >= 0) main.substring(qIdx + 1) else ""
            val hostPart = if (qIdx >= 0) main.substring(0, qIdx) else main
            val atIdx = hostPart.lastIndexOf('@')
            if (atIdx < 0) return null
            val password = hostPart.substring(0, atIdx)
            val (host, port) = splitHostPort(hostPart.substring(atIdx + 1)) ?: return null
            ProxyConfig(UUID.randomUUID().toString(), name, Protocol.TROJAN, host, port, link,
                parseQuery(query) + ("password" to password))
        } catch (e: Exception) { null }
    }

    private fun parseShadowsocks(link: String): ProxyConfig? {
        return try {
            var body = link.removePrefix("ss://")
            var name = "Shadowsocks"
            if (body.contains("#")) {
                name = decode(body.substringAfter("#"))
                body = body.substringBefore("#")
            }
            body = body.substringBefore("?")

            val atIdx = body.lastIndexOf('@')
            if (atIdx < 0) return null
            
            val userInfo = body.substring(0, atIdx)
            val hostPort = body.substring(atIdx + 1)

            var method = ""
            var password = ""

            // Пробуем декодировать Base64
            val decodedUserInfo = decodeBase64(userInfo)
            
            if (decodedUserInfo != null && decodedUserInfo.contains(":")) {
                method = decodedUserInfo.substringBefore(":")
                password = decodedUserInfo.substringAfter(":")
            } else if (userInfo.contains(":")) {
                // Если не Base64, значит открытый текст (твой случай)
                method = decode(userInfo.substringBefore(":"))
                password = decode(userInfo.substringAfter(":"))
            } else {
                return null
            }

            val (host, port) = splitHostPort(hostPort) ?: return null

            ProxyConfig(UUID.randomUUID().toString(), name, Protocol.SHADOWSOCKS,
                host, port, link, mapOf("method" to method, "password" to password))
        } catch (e: Exception) { null }
    }

    private fun parseVmess(link: String): ProxyConfig? {
        return try {
            val body = link.removePrefix("vmess://").trim().substringBefore('#')
            val decoded = decodeBase64(body) ?: return null
            val json = JSONObject(decoded)
            val host = json.optString("add", "")
            val port = json.optInt("port", 0)
            if (host.isEmpty() || port == 0) return null
            ProxyConfig(
                id = UUID.randomUUID().toString(),
                name = json.optString("ps", "VMess"),
                protocol = Protocol.VMESS,
                host = host,
                port = port,
                rawLink = link,
                extra = mapOf(
                    "uuid" to json.optString("id"),
                    "aid" to json.optInt("aid", 0).toString(),
                    "net" to json.optString("net", "tcp"),
                    "tls" to json.optString("tls", ""),
                    "scy" to json.optString("scy", "auto")
                )
            )
        } catch (e: Exception) { null }
    }
    
    private fun parseHysteria2(link: String): ProxyConfig? {
        return try {
            val body = link.removePrefix("hysteria2://").removePrefix("hy2://")
            val hashIdx = body.indexOf('#')
            val name = if (hashIdx >= 0) decode(body.substring(hashIdx + 1)) else "Hysteria2"
            val main = if (hashIdx >= 0) body.substring(0, hashIdx) else body
            val qIdx = main.indexOf('?')
            val hostPart = if (qIdx >= 0) main.substring(0, qIdx) else main
            val atIdx = hostPart.lastIndexOf('@')
            if (atIdx < 0) return null
            val auth = hostPart.substring(0, atIdx)
            val (host, port) = splitHostPort(hostPart.substring(atIdx + 1)) ?: return null
            ProxyConfig(UUID.randomUUID().toString(), name, Protocol.HYSTERIA2, host, port, link, mapOf("auth" to auth))
        } catch (e: Exception) { null }
    }

    private fun splitHostPort(hostPort: String): Pair<String, Int>? {
        val s = hostPort.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            val port = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: return null
            return s.substring(1, end) to port
        }
        val idx = s.lastIndexOf(':')
        if (idx < 0) return null
        val port = s.substring(idx + 1).toIntOrNull() ?: return null
        return s.substring(0, idx) to port
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else decode(it.substring(0, i)) to decode(it.substring(i + 1))
        }.toMap()
    }

    private fun decode(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64(s: String): String? = try {
        val n = s.trim().replace("\n", "").replace("\r", "")
        val padded = when (n.length % 4) { 2 -> "$n=="; 3 -> "$n="; else -> n }
        val decodedBytes = try { Base64.UrlSafe.decode(padded) } catch (e: Exception) { Base64.Default.decode(padded) }
        decodedBytes.decodeToString()
    } catch (e: Exception) { null }
}
