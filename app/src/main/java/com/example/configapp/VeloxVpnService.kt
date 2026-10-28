package com.example.configapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONObject
import java.net.URLDecoder

class VeloxVpnService : VpnService(), CoreCallbackHandler {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var coreController: CoreController? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        val configLink = intent?.getStringExtra("CONFIG_JSON") ?: return START_NOT_STICKY
        
        try {
            startForeground(1, createNotification())
        } catch (e: Exception) {
            Log.e("VeloxVPN", "Failed to start foreground: ${e.message}")
        }

        Thread {
            try {
                startVpn(configLink)
            } catch (e: Throwable) {
                Log.e("VeloxVPN", "Crash prevented: ${e.message}")
                stopVpn()
            }
        }.start()

        return START_STICKY
    }

    private fun startVpn(configLink: String) {
        if (isRunning) return
        isRunning = true

        val xrayJsonConfig = buildXrayConfig(configLink)
        if (xrayJsonConfig == null) {
            Log.e("VeloxVPN", "Invalid config link")
            showToast("Ошибка: неверный формат ссылки")
            stopVpn()
            return
        }

        try {
            val builder = Builder()
                .setSession("Velox VPN")
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .setBlocking(true)

            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.e("VeloxVPN", "Failed to disallow app: ${e.message}")
            }

            vpnInterface = builder.establish()
            val fd = vpnInterface?.fd
            
            if (fd == null || fd == -1) {
                Log.e("VeloxVPN", "Failed to establish TUN")
                showToast("Ошибка: не удалось создать TUN")
                stopVpn()
                return
            }

            coreController = Libv2ray.newCoreController(this)
            coreController?.startLoop(xrayJsonConfig, fd)
            
            Log.d("VeloxVPN", "Xray started successfully!")
            
        } catch (e: Throwable) {
            Log.e("VeloxVPN", "Error starting core", e)
            showToast("Ошибка запуска: ${e.message}")
            stopVpn()
        }
    }

    private fun showToast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun startup(): Long = 0
    override fun shutdown(): Long = 0
    override fun onEmitStatus(l: Long, s: String?): Long {
        Log.d("VeloxVPN", "Status: $s")
        return 0
    }

    private fun buildXrayConfig(link: String): String? {
        return when {
            link.startsWith("vless://") -> buildVlessConfig(link)
            link.startsWith("ss://") -> buildShadowsocksConfig(link)
            link.startsWith("vmess://") -> buildVmessConfig(link)
            link.startsWith("trojan://") -> buildTrojanConfig(link)
            link.startsWith("hysteria2://") || link.startsWith("hy2://") -> buildHysteria2Config(link)
            else -> null
        }
    }

    // --- VLESS (REALITY / TLS / WS) ---
    private fun buildVlessConfig(link: String): String? {
        try {
            val uri = Uri.parse(link)
            val uuid = uri.userInfo ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port != -1) uri.port else 443
            
            val security = uri.getQueryParameter("security") ?: "reality"
            val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: host
            val pbk = uri.getQueryParameter("pbk") ?: ""
            val sid = uri.getQueryParameter("sid") ?: ""
            val flow = uri.getQueryParameter("flow") ?: ""
            val fp = uri.getQueryParameter("fp") ?: "chrome" 
            val type = uri.getQueryParameter("type") ?: "tcp"
            val path = uri.getQueryParameter("path") ?: "/"
            val hostHeader = uri.getQueryParameter("host") ?: ""

            val streamSettings = if (security == "reality") {
                """
                "network": "tcp",
                "security": "reality",
                "realitySettings": {
                  "serverName": "$sni",
                  "publicKey": "$pbk",
                  "shortId": "$sid",
                  "fingerprint": "$fp",
                  "spiderX": "/"
                }
                """.trimIndent()
            } else if (security == "tls") {
                """
                "network": "$type",
                "security": "tls",
                "tlsSettings": {
                  "serverName": "$sni",
                  "allowInsecure": false
                },
                ${if (type == "ws") "\"wsSettings\": { \"path\": \"$path\", \"headers\": { \"Host\": \"$hostHeader\" } }," else ""}
                """.trimIndent()
            } else {
                """
                "network": "$type",
                "security": "none",
                ${if (type == "ws") "\"wsSettings\": { \"path\": \"$path\", \"headers\": { \"Host\": \"$hostHeader\" } }," else ""}
                """.trimIndent()
            }

            val safeStreamSettings = streamSettings.trimEnd(',')
            val flowParam = if (flow.isNotEmpty()) ",\"flow\": \"$flow\"" else ""

            return baseConfig(
                protocol = "vless",
                settings = """
                    "vnext": [
                      {
                        "address": "$host",
                        "port": $port,
                        "users": [ { "id": "$uuid", "encryption": "none" $flowParam } ]
                      }
                    ]
                """.trimIndent(),
                streamSettings = safeStreamSettings
            )
        } catch (e: Throwable) {
            Log.e("VeloxVPN", "Vless parse error: ${e.message}")
            return null
        }
    }

    // --- SHADOWSOCKS (SS) ---
    private fun buildShadowsocksConfig(link: String): String? {
        try {
            var body = link.removePrefix("ss://")
            if (body.contains("#")) body = body.substringBefore("#")
            body = body.substringBefore("?")

            if (!body.contains("@")) return null
            
            val userInfo = body.substringBefore("@")
            val hostPort = body.substringAfter("@")
            
            val host = hostPort.substringBefore(":")
            val port = hostPort.substringAfter(":").toIntOrNull() ?: return null

            var method: String
            var password: String

            val decodedUserInfo = try {
                String(Base64.decode(userInfo.replace('-', '+').replace('_', '/'), Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                userInfo
            }

            if (decodedUserInfo.contains(":")) {
                method = decodedUserInfo.substringBefore(":")
                password = decodedUserInfo.substringAfter(":")
            } else {
                if (userInfo.contains(":")) {
                    method = userInfo.substringBefore(":")
                    password = userInfo.substringAfter(":")
                } else {
                    return null
                }
            }

            return baseConfig(
                protocol = "shadowsocks",
                settings = """
                    "servers": [
                      {
                        "address": "$host",
                        "port": $port,
                        "method": "$method",
                        "password": "$password",
                        "uot": false,
                        "level": 0
                      }
                    ]
                """.trimIndent(),
                streamSettings = "\"network\": \"tcp\""
            )
        } catch (e: Throwable) {
            Log.e("VeloxVPN", "Shadowsocks parse error: ${e.message}")
            return null
        }
    }

    // --- VMESS (Base64 JSON) ---
    private fun buildVmessConfig(link: String): String? {
        try {
            val base64 = link.removePrefix("vmess://").trim()
            val decoded = String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
            val json = JSONObject(decoded)

            val host = json.optString("add")
            val port = json.optInt("port", 443)
            val uuid = json.optString("id")
            val aid = json.optInt("aid", 0)
            val net = json.optString("net", "tcp")
            val type = json.optString("type", "none")
            val tls = json.optString("tls", "")
            val sni = json.optString("sni", json.optString("host", host))
            val path = json.optString("path", "/")
            val hostHeader = json.optString("host", "")

            if (host.isEmpty() || uuid.isEmpty()) return null

            val security = if (tls == "tls") "tls" else "none"
            val streamSettings = if (net == "ws") {
                """
                "network": "ws",
                "security": "$security",
                ${if (security == "tls") "\"tlsSettings\": { \"serverName\": \"$sni\", \"allowInsecure\": false }," else ""}
                "wsSettings": { "path": "$path", "headers": { "Host": "$hostHeader" } }
                """.trimIndent()
            } else {
                """
                "network": "$net",
                "security": "$security",
                ${if (security == "tls") "\"tlsSettings\": { \"serverName\": \"$sni\", \"allowInsecure\": false }," else ""}
                """.trimIndent()
            }

            val safeStreamSettings = streamSettings.trimEnd(',')

            return baseConfig(
                protocol = "vmess",
                settings = """
                    "vnext": [
                      {
                        "address": "$host",
                        "port": $port,
                        "users": [ { "id": "$uuid", "alterId": $aid, "security": "auto" } ]
                      }
                    ]
                """.trimIndent(),
                streamSettings = safeStreamSettings
            )
        } catch (e: Throwable) {
            Log.e("VeloxVPN", "Vmess parse error: ${e.message}")
            return null
        }
    }

    // --- TROJAN ---
    private fun buildTrojanConfig(link: String): String? {
        try {
            val uri = Uri.parse(link)
            val password = uri.userInfo ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port != -1) uri.port else 443
            
            val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: host
            val type = uri.getQueryParameter("type") ?: "tcp"
            val path = uri.getQueryParameter("path") ?: "/"
            val hostHeader = uri.getQueryParameter("host") ?: ""

            val streamSettings = if (type == "ws") {
                """
                "network": "ws",
                "security": "tls",
                "tlsSettings": { "serverName": "$sni", "allowInsecure": false },
                "wsSettings": { "path": "$path", "headers": { "Host": "$hostHeader" } }
                """.trimIndent()
            } else {
                """
                "network": "tcp",
                "security": "tls",
                "tlsSettings": { "serverName": "$sni", "allowInsecure": false }
                """.trimIndent()
            }

            return baseConfig(
                protocol = "trojan",
                settings = """
                    "servers": [
                      {
                        "address": "$host",
                        "port": $port,
                        "password": "$password",
                        "level": 0
                      }
                    ]
                """.trimIndent(),
                streamSettings = streamSettings
            )
        } catch (e: Throwable) {
            Log.e("VeloxVPN", "Trojan parse error: ${e.message}")
            return null
        }
    }

    // --- HYSTERIA2 ---
    private fun buildHysteria2Config(link: String): String? {
        try {
            val uri = Uri.parse(link)
            // hysteria2://auth@host:port
            val auth = uri.userInfo ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port != -1) uri.port else 443
            
            val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer") ?: host
            val insecure = uri.getQueryParameter("insecure") == "1" || uri.getQueryParameter("allowInsecure") == "1"

            val streamSettings = """
                "network": "hysteria",
                "security": "tls",
                "tlsSettings": {
                  "serverName": "$sni",
                  "allowInsecure": $insecure
                }
            """.trimIndent()

            return baseConfig(
                protocol = "hysteria",
                settings = """
                    "version": 2,
                    "address": "$host",
                    "port": $port,
                    "auth": "$auth"
                """.trimIndent(),
                streamSettings = streamSettings
            )
        } catch (e: Throwable) {
            Log.e("VeloxVPN", "Hysteria2 parse error: ${e.message}")
            return null
        }
    }

    // Универсальная обертка для JSON (inbounds + outbounds)
    private fun baseConfig(protocol: String, settings: String, streamSettings: String): String {
        return """
            {
              "log": { "loglevel": "warning" },
              "inbounds": [
                {
                  "port": 10808,
                  "protocol": "socks",
                  "settings": { "auth": "noauth" }
                }
              ],
              "outbounds": [
                {
                  "protocol": "$protocol",
                  "settings": { $settings },
                  "streamSettings": { $streamSettings }
                }
              ]
            }
        """.trimIndent()
    }

    private fun stopVpn() {
        try { coreController?.stopLoop() } catch (e: Throwable) {}
        coreController = null
        vpnInterface?.close()
        vpnInterface = null
        isRunning = false
        stopForeground(true)
        stopSelf()
    }

    private fun createNotification(): Notification {
        val channelId = "velox_vpn_channel"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "VPN", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, VeloxVpnService::class.java).apply { action = "STOP" }
        val pendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Velox VPN")
            .setContentText("Подключено к серверу")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", pendingIntent)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
