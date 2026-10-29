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
import hev.sockstun.TProxyService
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class VeloxVpnService : VpnService(), CoreCallbackHandler {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var coreController: CoreController? = null
    private var tunnelThread: Thread? = null

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
            // 1. Создаем TUN интерфейс
            val builder = Builder()
                .setSession("Velox VPN")
                .addAddress("10.255.0.2", 30) // Важно: должно совпадать с tunnel.ipv4 в YAML
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500) // Важно: должно совпадать с tunnel.mtu в YAML
                .setBlocking(true)

            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {}

            vpnInterface = builder.establish()
            val tunFd = vpnInterface?.fd
            
            if (tunFd == null || tunFd == -1) {
                Log.e("VeloxVPN", "Failed to establish TUN")
                showToast("Ошибка: не удалось создать TUN")
                stopVpn()
                return
            }

            // 2. Запускаем ядро Xray
            coreController = Libv2ray.newCoreController(this)
            coreController?.startLoop(xrayJsonConfig, tunFd)
            Log.d("VeloxVPN", "Xray started successfully!")

            // 3. Ждем, чтобы Xray поднял порт 10808
            TimeUnit.MILLISECONDS.sleep(1000)

            // 4. Запускаем tun2socks (мост) через TProxyService
            val configFile = File(filesDir, "hev_config.yaml")
            configFile.writeText(buildHevYaml(configFile.absolutePath))

            tunnelThread = Thread {
                try {
                    Log.d("VeloxVPN", "TProxyStartService(fd=$tunFd, cfg=${configFile.absolutePath})")
                    // Передаем НЕ detachFd, а именно fd
                    TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
                } catch (e: Throwable) {
                    Log.e("VeloxVPN", "Tunnel thread error: ${e.message}")
                }
            }.apply { isDaemon = true }
            tunnelThread?.start()
            
            Log.d("VeloxVPN", "Tunnel started successfully!")
            
        } catch (e: Throwable) {
            Log.e("VeloxVPN", "Error starting core or tunnel", e)
            showToast("Ошибка запуска: ${e.message}")
            stopVpn()
        }
    }

    private fun buildHevYaml(logPath: String): String {
        // Важно: отступы двумя пробелами, LF, без лишних пробелов
        return """
            tunnel:
              ipv4: 10.255.0.2
              mtu: 1500
            socks5:
              address: 127.0.0.1
              port: 10808
              udp: 'udp'
            misc:
              log-file: $logPath
              log-level: debug
              task-stack-size: 81920
        """.trimIndent()
    }

    // ... остальные методы (buildXrayConfig, buildVlessOutbound и т.д.) остаются без изменений ...
    // ... (я не буду повторять их здесь, чтобы не загромождать ответ, но они должны быть в файле) ...
}
