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
import android.util.Log
import androidx.core.app.NotificationCompat
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

// ВАЖНО: Мы реализуем CoreCallbackHandler прямо в нашем VpnService!
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
            stopVpn()
            return
        }

        try {
            val builder = Builder()
                .setSession("Velox VPN")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .setBlocking(true)

            vpnInterface = builder.establish()
            val fd = vpnInterface?.fd
            
            if (fd == null || fd == -1) {
                Log.e("VeloxVPN", "Failed to establish TUN (fd is invalid)")
                stopVpn()
                return
            }

            // ГЛАВНЫЙ ФИКС: Передаем `this` (наш VpnService), чтобы libv2ray могла вызывать protect()
            coreController = Libv2ray.newCoreController(this)
            coreController?.startLoop(xrayJsonConfig, fd)
            
            Log.d("VeloxVPN", "Xray started successfully!")
            
        } catch (e: Throwable) {
            Log.e("VeloxVPN", "Error starting core", e)
            stopVpn()
        }
    }

    // --- Реализация методов CoreCallbackHandler ---
    override fun startup(): Long {
        Log.d("VeloxVPN", "Core startup")
        return 0
    }

    override fun shutdown(): Long {
        Log.d("VeloxVPN", "Core shutdown")
        return 0
    }

    override fun onEmitStatus(l: Long, s: String?): Long {
        Log.d("VeloxVPN", "Core status: $s")
        return 0
    }

    // --- Парсер конфига (JSON с DNS и Routing) ---
    private fun buildXrayConfig(link: String): String? {
        try {
            if (!link.startsWith("vless://")) return null
            val uri = Uri.parse(link)
            val uuid = uri.userInfo ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port != -1) uri.port else 443
            
            val security = uri.getQueryParameter("security") ?: "none"
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

            return """
                {
                  "log": { "loglevel": "warning" },
                  "dns": {
                    "servers": [ "1.1.1.1", "8.8.8.8" ]
                  },
                  "inbounds": [
                    {
                      "port": 10808,
                      "listen": "127.0.0.1",
                      "protocol": "socks",
                      "settings": { "udp": true },
                      "sniffing": { "enabled": true, "destOverride": ["http", "tls"] }
                    }
                  ],
                  "outbounds": [
                    {
                      "protocol": "vless",
                      "settings": {
                        "vnext": [
                          {
                            "address": "$host",
                            "port": $port,
                            "users": [ { "id": "$uuid", "encryption": "none" $flowParam } ]
                          }
                        ]
                      },
                      "streamSettings": {
                        $safeStreamSettings
                      }
                    },
                    { "protocol": "freedom", "tag": "direct" },
                    { "protocol": "blackhole", "tag": "block" }
                  ],
                  "routing": {
                    "rules": [
                      { "type": "field", "ip": [ "geoip:private" ], "outboundTag": "direct" }
                    ]
                  }
                }
            """.trimIndent()
        } catch (e: Throwable) {
            Log.e("VeloxVPN", "Parse error: ${e.message}")
            return null
        }
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
