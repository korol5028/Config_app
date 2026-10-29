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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class VeloxVpnService : VpnService(), CoreCallbackHandler {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var tunnelThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val isStarting = AtomicBoolean(false)

    private val coreStarted = CountDownLatch(1)
    private val coreError = AtomicReference<String?>(null)

    companion object {
        const val ACTION_VPN_STATE = "com.example.configapp.VPN_STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_ERROR = "error"
        const val STATE_CONNECTED = "CONNECTED"
        const val STATE_DISCONNECTED = "DISCONNECTED"
        const val STATE_ERROR = "ERROR"
        const val CORE_START_TIMEOUT_SEC = 5L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn(null)
            return START_NOT_STICKY
        }

        val configLink = intent?.getStringExtra("CONFIG_JSON") ?: return START_NOT_STICKY

        if (isStarting.getAndSet(true)) {
            Log.w("VeloxVPN", "Уже запускается, игнорируем дубликат")
            return START_STICKY
        }

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
            val fd = vpnInterface?.fd
            if (fd == null || fd == -1) {
                stopVpn("Не удалось создать TUN")
                return
            }

            coreController = Libv2ray.newCoreController(this)
            Thread {
                try {
                    coreController?.startLoop(xrayJson, fd)
                    Log.d("VeloxVPN", "Xray startLoop вернулся")
                } catch (e: Throwable) {
                    Log.e("VeloxVPN", "Xray упал: ${e.message}")
                    coreError.set("Xray упал: ${e.message}")
                    coreStarted.countDown()
                    stopVpn("Xray упал: ${e.message}")
                }
            }.start()

            val started = coreStarted.await(CORE_START_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!started) {
                stopVpn("Ядро Xray не запустилось за ${CORE_START_TIMEOUT_SEC} с")
                return
            }
            val err = coreError.get()
            if (err != null) {
                stopVpn(err)
                return
            }

            val yaml = buildHevYaml(filesDir.absolutePath + "/hev.log")
            val yamlFile = File(filesDir, "hev_config.yaml")
            yamlFile.writeText(yaml)

            tunnelThread = Thread {
                try {
                    TProxyService.TProxyStartService(yamlFile.absolutePath, fd)
                    Log.d("VeloxVPN", "Мост Hev запущен")
                    isRunning.set(true)
                    sendStateBroadcast(STATE_CONNECTED, null)
                } catch (e: Throwable) {
                    Log.e("VeloxVPN", "Мост Hev упал: ${e.message}")
                    stopVpn("Туннель упал: ${e.message}")
                }
            }.apply { isDaemon = true }
            tunnelThread?.start()

        } catch (e: Throwable) {
            stopVpn("Ошибка запуска: ${e.message}")
        }
    }

    override fun startup(): Long {
        Log.d("VeloxVPN", "Ядро Xray запустилось (Startup)")
        coreStarted.countDown()
        return 0
    }

    override fun shutdown(): Long {
        Log.d("VeloxVPN", "Ядро Xray остановилось (Shutdown)")
        coreError.compareAndSet(null, "Ядро Xray остановилось")
        coreStarted.countDown()
        return 0
    }

    // ИСПРАВЛЕНО: Первый параметр теперь Long, как требует библиотека
    override fun onEmitStatus(l: Long, s: String?): Long {
        Log.w("VeloxVPN", "Статус ядра: code=$l, msg=$s")
        if (l != 0L) {
            coreError.compareAndSet(null, "Ошибка ядра: $s (code=$l)")
            coreStarted.countDown()
        }
        return 0
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
                link.startsWith("vless://") -> buildVlessOutbound(link, outbound)
                link.startsWith("ss://") -> buildShadowsocksOutbound(link, outbound)
                link.startsWith("vmess://") -> buildVmessOutbound(link, outbound)
                link.startsWith("trojan://") -> buildTrojanOutbound(link, outbound)
                link.startsWith("hysteria2://") || link.startsWith("hy2://") -> buildHysteria2Outbound(link, outbound)
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
            Log.e("VeloxVPN", "Ошибка сборки конфига: ${e.message}")
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
        val base64 = link.removePrefix("vmess://").trim().substringBefore('#')
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
                headers.put("Host", json.optString("host", ""))
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
        if (!isRunning.get() && !isStarting.get()) return
        isRunning.set(false)
        coreStarted.countDown()

        try { TProxyService.TProxyStopService() } catch (e: Throwable) {}
        tunnelThread?.interrupt()
        tunnelThread = null

        try { coreController?.stopLoop() } catch (e: Throwable) {}
        coreController = null

        vpnInterface?.close()
        vpnInterface = null

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
