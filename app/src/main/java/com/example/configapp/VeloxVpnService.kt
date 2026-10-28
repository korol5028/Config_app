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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

class VeloxVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var coreController: CoreController? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        val configLink = intent?.getStringExtra("CONFIG_JSON") ?: return START_NOT_STICKY
        
        CoroutineScope(Dispatchers.IO).launch {
            startVpn(configLink)
        }
        return START_STICKY
    }

    private fun startVpn(configLink: String) {
        if (isRunning) return
        isRunning = true

        val xrayJsonConfig = buildXrayConfig(configLink)
        if (xrayJsonConfig == null) {
            Log.e("VeloxVPN", "Invalid config link format")
            stopSelf()
            return
        }

        val builder = Builder()
            .setSession("Velox VPN")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setMtu(1500)
            .setBlocking(true)

        vpnInterface = builder.establish()
        val fd = vpnInterface?.fd ?: run {
            Log.e("VeloxVPN", "Failed to establish TUN")
            stopSelf()
            return
        }

        try {
            // ИСПРАВЛЕНО: используем Libv2ray.newCoreController и правильный обработчик
            val callback = object : CoreCallbackHandler {
                override fun startup(): Long {
                    Log.d("VeloxVPN", "Core started")
                    return 0
                }

                override fun shutdown(): Long {
                    Log.d("VeloxVPN", "Core stopped")
                    return 0
                }

                override fun onEmitStatus(l: Long, s: String?): Long {
                    Log.d("VeloxVPN", "Core status: $s")
                    return 0
                }
            }
            
            coreController = Libv2ray.newCoreController(callback)
            // Запускаем ядро (в этой версии метод может называться startLoop)
            coreController?.startLoop(xrayJsonConfig, fd)
            
            Log.d("VeloxVPN", "Xray started successfully!")
            startForeground(1, createNotification())
            
        } catch (e: Exception) {
            Log.e("VeloxVPN", "Error starting core", e)
            stopVpn()
        }
    }

    // Парсим vless:// и собираем JSON для Xray
    private fun buildXrayConfig(link: String): String? {
        try {
            if (!link.startsWith("vless://")) return null
            val uri = Uri.parse(link)
            val uuid = uri.userInfo ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port != -1) uri.port else 443
            
            val sni = uri.getQueryParameter("sni") ?: host
            val pbk = uri.getQueryParameter("pbk") ?: ""
            val sid = uri.getQueryParameter("sid") ?: ""
            val flow = uri.getQueryParameter("flow") ?: ""

            return """
                {
                  "log": { "loglevel": "warning" },
                  "inbounds": [
                    {
                      "port": 10808,
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
                            "users": [ { "id": "$uuid", "encryption": "none", "flow": "$flow" } ]
                          }
                        ]
                      },
                      "streamSettings": {
                        "network": "tcp",
                        "security": "reality",
                        "realitySettings": {
                          "serverName": "$sni",
                          "publicKey": "$pbk",
                          "shortId": "$sid"
                        }
                      }
                    }
                  ]
                }
            """.trimIndent()
        } catch (e: Exception) {
            Log.e("VeloxVPN", "Parse error", e)
            return null
        }
    }

    private fun stopVpn() {
        try {
            coreController?.stopLoop()
        } catch (e: Exception) {
            Log.e("VeloxVPN", "Error stopping core", e)
        }
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
