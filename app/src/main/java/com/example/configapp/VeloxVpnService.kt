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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VeloxVpnService : VpnService(), CoreCallbackHandler {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var tunnelThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val isStarting = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)
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

        if (isStarting.getAndSet(true)) return START_STICKY
        isStoppedByUser.set(false)
        isStopping.set(false)

        try {
            startForeground(1, createNotification())
        } catch (e: Exception) {
            Log.e("VeloxVPN", "Ошибка startForeground: ${e.message}")
        }

        Thread {
            try {
                startVpn(configLink)
            } catch (e: Throwable) {
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
                .setMtu(1400)
                .setBlocking(true)

            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}

            vpnInterface = builder.establish()
            val fd = vpnInterface?.fd
            if (fd == null || fd == -1) {
                stopVpn("Не удалось создать TUN")
                return
            }

            // 1. Запускаем Xray как SOCKS-прокси (БЕЗ TUN)
            coreController = Libv2ray.newCoreController(this)
            Thread {
                try {
                    coreController?.startLoop(xrayJson, 0) // 0 = без TUN
                } catch (e: Throwable) {
                    if (!isStoppedByUser.get()) stopVpn("Xray упал: ${e.message}")
                }
            }.start()

            // 2. Ждём, пока Xray откроет порт
            var portOpen = false
            for (i in 0 until 15) {
                TimeUnit.MILLISECONDS.sleep(200)
                if (isStoppedByUser.get()) return
                if (isPortOpen(10808)) { portOpen = true; break }
            }
            if (!portOpen) {
                stopVpn("Xray не открыл порт 10808")
                return
            }

            // 3. Запускаем HEV мост
            val yamlFile = File(filesDir, "hev_config.yaml")
            yamlFile.writeText(buildHevYaml(filesDir.absolutePath + "/hev.log"))

            tunnelThread = Thread {
                try {
                    TProxyService.TProxyStartService(yamlFile.absolutePath, fd)
                } catch (e: Throwable) {
                    if (!isStoppedByUser.get()) stopVpn("HEV упал: ${e.message}")
                }
            }.apply { isDaemon = true }
            tunnelThread?.start()

            // 4. Проверяем интернет через SOCKS-прокси
            Thread {
                if (checkRealInternetThroughProxy()) {
                    if (!isStoppedByUser.get()) {
                        isRunning.set(true)
                        sendStateBroadcast(STATE_CONNECTED, null)
                    }
                } else {
                    stopVpn("Прокси не работает")
                }
            }.start()

        } catch (e: Throwable) {
            stopVpn("Ошибка запуска: ${e.message}")
        }
    }

    private fun isPortOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100); true }
    } catch (e: Exception) { false }

    private fun checkRealInternetThroughProxy(): Boolean {
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808))
            val client = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("https://cp.cloudflare.com/generate_204") // ИСПРАВЛЕНО: http на https
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            response.close()
            Log.d("VeloxVPN", "Проверка прокси: код $code")
            code == 204 || code == 200
        } catch (e: Exception) {
            Log.e("VeloxVPN", "Ошибка проверки прокси: ${e.message}")
            showToast("Ошибка прокси: ${e.message}")
            false
        }
    }

    override fun startup(): Long = 0
    override fun shutdown(): Long = 0
    override fun onEmitStatus(l: Long, s: String?): Long = 0

    private fun buildHevYaml(logPath: String) = """
        tunnel:
          ipv4: 10.255.0.2
          mtu: 1400
        socks5:
          address: 127.0.0.1
          port: 10808
          udp: 'udp'
        misc:
          log-file: $logPath
          log-level: debug
    """.trimIndent()

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
            root.put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))

            val inbounds = JSONArray().put(JSONObject().apply {
                put("port", 10808)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().put("udp", true))
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                })
            })
            root.put("inbounds", inbounds)

            val outbounds = JSONArray()
            val outbound = JSONObject()
            when {
                link.startsWith("vless://", true) -> buildVlessOutbound(link, outbound)
                link.startsWith("ss://", true) -> buildShadowsocksOutbound(link, outbound)
                link.startsWith("vmess://", true) -> buildVmessOutbound(link, outbound)
                link.startsWith("trojan://", true) -> buildTrojanOutbound(link, outbound)
                else -> return null
            }
            outbounds.put(outbound)
            outbounds.put(JSONObject().put("protocol", "freedom").put("tag", "direct"))
            outbounds.put(JSONObject().put("protocol", "blackhole").put("tag", "block"))
            root.put("outbounds", outbounds)

            val routing = JSONObject()
            routing.put("domainStrategy", "IPIfNonMatch")
            val rules = JSONArray()
            val localRule = JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray().put("10.0.0.0/8").put("172.16.0.0/12").put("192.168.0.0/16").put("127.0.0.0/8"))
                put("outboundTag", "direct")
            }
            rules.put(localRule)
            routing.put("rules", rules)
            root.put("routing", routing)
            
            return root.toString(2)
        } catch (e: Throwable) { return null }
    }

    private fun buildVlessOutbound(link: String, outbound: JSONObject) {
        val uri = Uri.parse(link)
        val security = uri.getQueryParameter("security") ?: "reality"
        val type = uri.getQueryParameter("type") ?: "tcp"
        outbound.put("protocol", "vless")
        
        val user = JSONObject().apply {
            put("id", uri.userInfo)
            put("encryption", "none")
            uri.getQueryParameter("flow")?.let { if (it.isNotEmpty()) put("flow", it) }
        }
        
        outbound.put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().apply {
            put("address", uri.host); put("port", uri.port); put("users", JSONArray().put(user))
        })))
        
        val stream = JSONObject().apply { put("network", type); put("security", security) }
        if (security == "reality") {
            stream.put("realitySettings", JSONObject().apply {
                put("serverName", uri.getQueryParameter("sni") ?: uri.host)
                put("publicKey", uri.getQueryParameter("pbk") ?: "")
                put("shortId", uri.getQueryParameter("sid") ?: "")
                put("fingerprint", uri.getQueryParameter("fp") ?: "chrome")
                put("spiderX", "/")
            })
        }
        outbound.put("streamSettings", stream)
    }

    private fun buildShadowsocksOutbound(link: String, outbound: JSONObject) {
        val body = link.substring(5).substringBefore("#").substringBefore("?")
        val at = body.lastIndexOf('@')
        if (at < 0) return
        val userInfo = body.substring(0, at)
        val hostPortStr = body.substring(at + 1)
        val (host, port) = splitHostPort(hostPortStr) ?: return

        var method = ""; var password = ""
        val decoded = try { String(Base64.decode(userInfo.replace('-', '+').replace('_', '/'), Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { userInfo }
        if (decoded.contains(":")) { method = decoded.substringBefore(":"); password = decoded.substringAfter(":") }
        else if (userInfo.contains(":")) { method = userInfo.substringBefore(":"); password = userInfo.substringAfter(":") }

        outbound.put("protocol", "shadowsocks")
        outbound.put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().apply {
            put("address", host); put("port", port)
            put("method", method); put("password", password); put("uot", false); put("level", 0)
        })))
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

    private fun buildVmessOutbound(link: String, outbound: JSONObject) {
        val base64 = link.substring(8).trim().substringBefore('#')
        val json = JSONObject(String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8))
        outbound.put("protocol", "vmess")
        val user = JSONObject().apply { put("id", json.optString("id")); put("alterId", json.optInt("aid", 0)); put("security", "auto") }
        outbound.put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().apply {
            put("address", json.optString("add")); put("port", json.optInt("port", 443)); put("users", JSONArray().put(user))
        })))
        val stream = JSONObject().apply {
            val net = json.optString("net", "tcp"); put("network", net)
            if (json.optString("tls", "") == "tls") put("security", "tls")
            if (net == "ws") put("wsSettings", JSONObject().apply {
                put("path", json.optString("path", "/")); put("headers", JSONObject().put("Host", json.optString("host", "")))
            })
            if (net == "grpc") put("grpcSettings", JSONObject().put("serviceName", json.optString("path", "")))
        }
        outbound.put("streamSettings", stream)
    }

    private fun buildTrojanOutbound(link: String, outbound: JSONObject) {
        val uri = Uri.parse(link); val password = uri.userInfo ?: return; val host = uri.host ?: return
        val port = if (uri.port != -1) uri.port else 443; val sni = uri.getQueryParameter("sni") ?: host
        outbound.put("protocol", "trojan")
        outbound.put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().apply {
            put("address", host); put("port", port); put("password", password); put("level", 0)
        })))
        outbound.put("streamSettings", JSONObject().apply {
            put("network", "tcp"); put("security", "tls")
            put("tlsSettings", JSONObject().apply { put("serverName", sni); put("allowInsecure", false); put("fingerprint", "chrome") })
        })
    }

    private fun stopVpn(errorMsg: String?) {
        if (isStopping.getAndSet(true)) return
        isRunning.set(false)

        val pfd = vpnInterface
        vpnInterface = null
        try { pfd?.close() } catch (e: Throwable) {}

        stopForeground(true)
        stopSelf()

        if (errorMsg != null) {
            sendStateBroadcast(STATE_ERROR, errorMsg)
            showToast(errorMsg)
        } else {
            sendStateBroadcast(STATE_DISCONNECTED, null)
        }

        val ctrl = coreController
        coreController = null
        val thread = tunnelThread
        tunnelThread = null

        Thread {
            try { TProxyService.TProxyStopService() } catch (e: Throwable) {}
            thread?.interrupt()
            try { ctrl?.stopLoop() } catch (e: Throwable) {}
        }.start()
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
