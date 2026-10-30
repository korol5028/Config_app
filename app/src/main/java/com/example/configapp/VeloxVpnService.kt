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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VeloxVpnService : VpnService(), CoreCallbackHandler {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var tunnelThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val isStarting = AtomicBoolean(false)
    private val isStoppedByUser = AtomicBoolean(false)

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
            isStoppedByUser.set(true)
            stopVpn(null)
            return START_NOT_STICKY
        }

        val configLink = intent?.getStringExtra("CONFIG_JSON") ?: return START_NOT_STICKY

        if (isStarting.getAndSet(true)) {
            Log.w("VeloxVPN", "Уже запускается, игнорируем дубликат")
            return START_STICKY
        }

        isStoppedByUser.set(false)

        try {
            startForeground(1, createNotification())
        } catch (e: Exception) {
            Log.e("VeloxVPN", "Ошибка startForeground: ${e.message}")
        }

        Thread {
            try {
                startVpn(configLink)
            } catch (e: Throwable) {
                Log.e("VeloxVPN", "Фатальная ошибка: ${e.message}")
                stopVpn("Ошибка: ${e.message}")
            } finally {
                isStarting.set(false)
            }
        }.start()

        return START_STICKY
    }

    private fun startVpn(configLink: String) {
        if (isRunning.get()) return

        val xrayJson = buildXrayConfig(configLink)
        if (xrayJson == null) {
            stopVpn("Неверный формат ссылки или неподдерживаемый протокол")
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
            val fd = vpnInterface?.fd
            if (fd == null || fd == -1) {
                stopVpn("Не удалось создать TUN")
                return
            }

            // 1. Запускаем Xray
            coreController = Libv2ray.newCoreController(this)
            Thread {
                try {
                    coreController?.startLoop(xrayJson, fd)
                } catch (e: Throwable) {
                    if (!isStoppedByUser.get()) {
                        Log.e("VeloxVPN", "Xray упал: ${e.message}")
                        stopVpn("Xray упал: ${e.message}")
                    }
                }
            }.start()

            // 2. Ждем, пока порт 10808 реально откроется (макс 3 секунды)
            var portOpen = false
            for (i in 0 until 15) { // 15 * 200ms = 3 секунды
                TimeUnit.MILLISECONDS.sleep(200)
                if (isStoppedByUser.get()) return
                if (isPortOpen(10808)) {
                    portOpen = true
                    break
                }
            }

            if (!portOpen) {
                stopVpn("Ядро Xray не запустилось (порт 10808 закрыт)")
                return
            }

            // 3. Запускаем мост HEV
            val yaml = buildHevYaml(filesDir.absolutePath + "/hev.log")
            val yamlFile = File(filesDir, "hev_config.yaml")
            yamlFile.writeText(yaml)

            tunnelThread = Thread {
                try {
                    TProxyService.TProxyStartService(yamlFile.absolutePath, fd)
                    if (!isStoppedByUser.get()) {
                        Log.d("VeloxVPN", "Мост HEV успешно запущен")
                        isRunning.set(true)
                        sendStateBroadcast(STATE_CONNECTED, null)
                    }
                } catch (e: Throwable) {
                    if (!isStoppedByUser.get()) {
                        Log.e("VeloxVPN", "Мост HEV упал: ${e.message}")
                        stopVpn("Туннель упал: ${e.message}")
                    }
                }
            }.apply { isDaemon = true }
            tunnelThread?.start()

        } catch (e: Throwable) {
            stopVpn("Ошибка запуска: ${e.message}")
        }
    }

    // Проверка открыт ли порт (значит Xray реально запустился)
    private fun isPortOpen(port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), 100)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun startup(): Long = 0
    override fun shutdown(): Long = 0
    override fun onEmitStatus(l: Long, s: String?): Long = 0

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
        """.trimIndent()
    }

    private fun sendStateBroadcast(state: String, error: String?) {
        val intent = Intent(ACTION_VPN_STATE).apply {
            putExtra(EXTRA_STATE, state)
            if (error != null) putExtra(EXTRA_ERROR, error)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun buildXrayConfig(link: String): String? {
        try {
            val root = JSONObject()
            root.put("log", JSONObject().put("loglevel", "warning"))

            val dns = JSONObject()
            dns.put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8"))
            root.put("dns", dns)

            val inbounds = JSONArray()
            val inbound = JSONObject()
            inbound.put("port", 10808)
            inbound.put("listen", "127.0.0.1")
            inbound.put("protocol", "socks")
            inbound.put("settings", JSONObject().put("udp", true))
            inbounds.put(inbound)
            root.put("inbounds", inbounds)

            val outbounds = JSONArray()
            val outbound = JSONObject()

            when {
                link.startsWith("vless://", ignoreCase = true) -> buildVlessOutbound(link, outbound)
                link.startsWith("ss://", ignoreCase = true) -> buildShadowsocksOutbound(link, outbound)
                link.startsWith("vmess://", ignoreCase = true) -> buildVmessOutbound(link, outbound)
                link.startsWith("trojan://", ignoreCase = true) -> buildTrojanOutbound(link, outbound)
                link.startsWith("hysteria2://", ignoreCase = true) || link.startsWith("hy2://", ignoreCase = true) -> {
                    // Xray-core НЕ поддерживает Hysteria2, поэтому мы просто вернем null
                    Log.e("VeloxVPN", "Hysteria2 не поддерживается этим ядром")
                    return null
                }
                else -> return null
            }

            outbounds.put(outbound)
            outbounds.put(JSONObject().put("protocol", "freedom").put("tag", "direct"))
            outbounds.put(JSONObject().put("protocol", "blackhole").put("tag", "block"))
            root.put("outbounds", outbounds)

            val routing = JSONObject()
            val rules = JSONArray()
            val rule = JSONObject()
            rule.put("type", "field")
            rule.put("ip", JSONArray().put("10.0.0.0/8").put("172.16.0.0/12").put("192.168.0.0/16").put("127.0.0.0/8"))
            rule.put("outboundTag", "direct")
            rules.put(rule)
            routing.put("rules", rules)
            root.put("routing", routing)

            return root.toString(2)
        } catch (e: Throwable) {
            return null
        }
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
            reality.put("spiderX", "/")
            stream.put("realitySettings", reality)
        }
        outbound.put("streamSettings", stream)
    }

    private fun buildShadowsocksOutbound(link: String, outbound: JSONObject) {
        val body = link.substring(5)
        val cleanBody = if (body.contains("#")) body.substringBefore("#") else body
        val main = cleanBody.substringBefore("?")
        val at = main.lastIndexOf('@')
        if (at < 0) return
        val userInfo = main.substring(0, at)
        val hostPort = main.substring(at + 1)

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

    // ИСПРАВЛЕННЫЙ VMESS (с WS и gRPC)
    private fun buildVmessOutbound(link: String, outbound: JSONObject) {
        val base64 = link.substring(8).trim().substringBefore('#')
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
        val net = json.optString("net", "tcp")
        stream.put("network", net)
        val tls = json.optString("tls", "")
        if (tls == "tls") stream.put("security", "tls")

        when (net) {
            "ws" -> {
                val ws = JSONObject()
                ws.put("path", json.optString("path", "/"))
                val headers = JSONObject()
                val hostHeader = json.optString("host", "")
                if (hostHeader.isNotEmpty()) headers.put("Host", hostHeader)
                ws.put("headers", headers)
                stream.put("wsSettings", ws)
            }
            "grpc" -> {
                val grpc = JSONObject()
                grpc.put("serviceName", json.optString("path", ""))
                stream.put("grpcSettings", grpc)
            }
        }
        outbound.put("streamSettings", stream)
    }

    private fun buildTrojanOutbound(link: String, outbound: JSONObject) {
        val uri = Uri.parse(link)
        val password = uri.userInfo ?: return
        val host = uri.host ?: return
        val port = if (uri.port != -1) uri.port else 443
        val sni = uri.getQueryParameter("sni") ?: host

        outbound.put("protocol", "trojan")
        val servers = JSONArray()
        val server = JSONObject()
        server.put("address", host)
        server.put("port", port)
        server.put("password", password)
        server.put("level", 0)
        servers.put(server)
        outbound.put("settings", JSONObject().put("servers", servers))

        val stream = JSONObject()
        stream.put("network", "tcp")
        stream.put("security", "tls")
        val tls = JSONObject()
        tls.put("serverName", sni)
        tls.put("allowInsecure", false)
        tls.put("fingerprint", "chrome") 
        stream.put("tlsSettings", tls)
        outbound.put("streamSettings", stream)
    }

    private fun stopVpn(errorMsg: String?) {
        isRunning.set(false)

        val pfd = vpnInterface
        vpnInterface = null
        val ctrl = coreController
        coreController = null
        val thread = tunnelThread
        tunnelThread = null

        // Отправляем статус в UI до остановки потоков
        if (errorMsg != null) {
            sendStateBroadcast(STATE_ERROR, errorMsg)
            showToast(errorMsg)
        } else {
            sendStateBroadcast(STATE_DISCONNECTED, null)
        }

        Thread {
            try { TProxyService.TProxyStopService() } catch (e: Throwable) {}
            thread?.interrupt()
            try { ctrl?.stopLoop() } catch (e: Throwable) {}
            try { pfd?.close() } catch (e: Throwable) {}
        }.start()

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
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, "VPN", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = Intent(this, VeloxVpnService::class.java).apply { action = "STOP" }
        val pi = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Velox VPN")
            .setContentText("Подключено")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", pi)
            .build()
    }

    override fun onDestroy() {
        stopVpn(null)
        super.onDestroy()
    }
}
