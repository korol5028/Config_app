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

    companion object {
        const val ACTION_VPN_STATE = "com.example.configapp.VPN_STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_ERROR = "error"
        const val STATE_CONNECTED = "CONNECTED"
        const val STATE_DISCONNECTED = "DISCONNECTED"
        const val STATE_ERROR = "ERROR"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn(null)
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
                stopVpn("Ошибка: ${e.message}")
            }
        }.start()

        return START_STICKY
    }

    private fun startVpn(configLink: String) {
        if (isRunning) return
        isRunning = true

        val xrayJsonConfig = buildXrayConfig(configLink)
        if (xrayJsonConfig == null) {
            stopVpn("Неверный формат ссылки")
            return
        }

        try {
            val builder = Builder()
                .setSession("Velox VPN")
                .addAddress("10.255.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .setBlocking(true)

            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}

            vpnInterface = builder.establish()
            val tunFd = vpnInterface?.fd
            
            if (tunFd == null || tunFd == -1) {
                stopVpn("Не удалось создать TUN")
                return
            }

            coreController = Libv2ray.newCoreController(this)
            coreController?.startLoop(xrayJsonConfig, tunFd)
            
            TimeUnit.MILLISECONDS.sleep(1000)

            val configFile = File(filesDir, "hev_config.yaml")
            configFile.writeText(buildHevYaml(configFile.absolutePath))

            tunnelThread = Thread {
                try {
                    TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
                } catch (e: Throwable) {
                    Log.e("VeloxVPN", "Tunnel thread error: ${e.message}")
                }
            }.apply { isDaemon = true }
            tunnelThread?.start()
            
            sendStateBroadcast(STATE_CONNECTED, null)
            
        } catch (e: Throwable) {
            stopVpn("Ошибка запуска: ${e.message}")
        }
    }

    private fun sendStateBroadcast(state: String, error: String?) {
        val intent = Intent(ACTION_VPN_STATE).apply {
            putExtra(EXTRA_STATE, state)
            if (error != null) putExtra(EXTRA_ERROR, error)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun buildHevYaml(logPath: String): String {
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

    override fun startup(): Long = 0
    override fun shutdown(): Long = 0
    override fun onEmitStatus(l: Long, s: String?): Long = 0

    private fun buildXrayConfig(link: String): String? {
        try {
            val root = JSONObject()
            root.put("log", JSONObject().put("loglevel", "warning"))
            val inbounds = JSONArray()
            val inbound = JSONObject()
            inbound.put("port", 10808)
            inbound.put("listen", "127.0.0.1")
            inbound.put("protocol", "socks")
            inbound.put("settings", JSONObject().put("auth", "noauth"))
            inbounds.put(inbound)
            root.put("inbounds", inbounds)

            val outbounds = JSONArray()
            val outbound = JSONObject()

            when {
                link.startsWith("vless://") -> buildVlessOutbound(link, outbound)
                link.startsWith("ss://") -> buildShadowsocksOutbound(link, outbound)
                link.startsWith("vmess://") -> buildVmessOutbound(link, outbound)
                link.startsWith("trojan://") -> buildTrojanOutbound(link, outbound)
                link.startsWith("hysteria2://") || link.startsWith("hy2://") -> buildHysteria2Outbound(link, outbound)
                else -> return null
            }

            outbounds.put(outbound)
            root.put("outbounds", outbounds)
            return root.toString(2)
        } catch (e: Throwable) { return null }
    }

    private fun buildVlessOutbound(link: String, outbound: JSONObject) {
        val uri = Uri.parse(link)
        val security = uri.getQueryParameter("security") ?: "reality"
        val type = uri.getQueryParameter("type") ?: "tcp"
        outbound.put("protocol", "vless")
        val vnext = JSONArray()
        val server = JSONObject()
        server.put("address", uri.host)
        server.put("port", uri.port)
        val users = JSONArray()
        val user = JSONObject()
        user.put("id", uri.userInfo)
        user.put("encryption", "none")
        uri.getQueryParameter("flow")?.let { if (it.isNotEmpty()) user.put("flow", it) }
        users.put(user)
        server.put("users", users)
        vnext.put(server)
        outbound.put("settings", JSONObject().put("vnext", vnext))
        val stream = JSONObject()
        stream.put("network", type)
        stream.put("security", security)
        if (security == "reality") {
            val reality = JSONObject()
            reality.put("serverName", uri.getQueryParameter("sni") ?: uri.host)
            reality.put("publicKey", uri.getQueryParameter("pbk") ?: "")
            reality.put("shortId", uri.getQueryParameter("sid") ?: "")
            reality.put("fingerprint", uri.getQueryParameter("fp") ?: "chrome")
            stream.put("realitySettings", reality)
        }
        outbound.put("streamSettings", stream)
    }

    private fun buildShadowsocksOutbound(link: String, outbound: JSONObject) {
        var body = link.removePrefix("ss://")
        if (body.contains("#")) body = body.substringBefore("#")
        body = body.substringBefore("?")
        val at = body.lastIndexOf('@')
        val userInfo = body.substring(0, at)
        val hostPort = body.substring(at + 1)
        
        var method = ""
        var password = ""
        val decoded = try { String(Base64.decode(userInfo.replace('-', '+').replace('_', '/'), Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { userInfo }
        
        if (decoded.contains(":")) {
            method = decoded.substringBefore(":")
            password = decoded.substringAfter(":")
        } else if (userInfo.contains(":")) {
            method = userInfo.substringBefore(":")
            password = userInfo.substringAfter(":")
        }

        outbound.put("protocol", "shadowsocks")
        val servers = JSONArray()
        val server = JSONObject()
        server.put("address", hostPort.substringBefore(":"))
        server.put("port", hostPort.substringAfter(":").toIntOrNull() ?: 443)
        server.put("method", method)
        server.put("password", password)
        server.put("uot", false)
        server.put("level", 0)
        servers.put(server)
        outbound.put("settings", JSONObject().put("servers", servers))
    }

    private fun buildVmessOutbound(link: String, outbound: JSONObject) {
        val base64 = link.removePrefix("vmess://").trim()
        val decoded = String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
        val json = JSONObject(decoded)
        outbound.put("protocol", "vmess")
        val vnext = JSONArray()
        val server = JSONObject()
        server.put("address", json.optString("add"))
        server.put("port", json.optInt("port", 443))
        val users = JSONArray()
        val user = JSONObject()
        user.put("id", json.optString("id"))
        user.put("alterId", json.optInt("aid", 0))
        user.put("security", "auto")
        users.put(user)
        server.put("users", users)
        vnext.put(server)
        outbound.put("settings", JSONObject().put("vnext", vnext))
        val stream = JSONObject()
        stream.put("network", json.optString("net", "tcp"))
        if (json.optString("tls", "") == "tls") stream.put("security", "tls")
        outbound.put("streamSettings", stream)
    }

    private fun buildTrojanOutbound(link: String, outbound: JSONObject) {
        val uri = Uri.parse(link)
        outbound.put("protocol", "trojan")
        val servers = JSONArray()
        val server = JSONObject()
        server.put("address", uri.host)
        server.put("port", uri.port)
        server.put("password", uri.userInfo)
        server.put("level", 0)
        servers.put(server)
        outbound.put("settings", JSONObject().put("servers", servers))
        val stream = JSONObject()
        stream.put("network", "tcp")
        stream.put("security", "tls")
        stream.put("tlsSettings", JSONObject().put("serverName", uri.getQueryParameter("sni") ?: uri.host))
        outbound.put("streamSettings", stream)
    }

    private fun buildHysteria2Outbound(link: String, outbound: JSONObject) {
        val uri = Uri.parse(link)
        outbound.put("protocol", "hysteria")
        val settings = JSONObject()
        settings.put("version", 2)
        settings.put("address", uri.host)
        settings.put("port", uri.port)
        settings.put("auth", uri.userInfo)
        outbound.put("settings", settings)
        val stream = JSONObject()
        stream.put("network", "hysteria")
        stream.put("security", "tls")
        stream.put("tlsSettings", JSONObject().put("serverName", uri.getQueryParameter("sni") ?: uri.host))
        outbound.put("streamSettings", stream)
    }

    private fun stopVpn(errorMsg: String?) {
        try { TProxyService.TProxyStopService() } catch (e: Throwable) {}
        tunnelThread?.interrupt()
        tunnelThread = null
        try { coreController?.stopLoop() } catch (e: Throwable) {}
        coreController = null
        vpnInterface?.close()
        vpnInterface = null
        isRunning = false
        
        if (errorMsg != null) {
            sendStateBroadcast(STATE_ERROR, errorMsg)
            showToast(errorMsg)
        } else {
            sendStateBroadcast(STATE_DISCONNECTED, null)
        }
        
        stopForeground(true)
        stopSelf()
    }

    private fun showToast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
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
        stopVpn(null)
        super.onDestroy()
    }
}
