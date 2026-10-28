package com.example.configapp.ui

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.configapp.data.ConfigParser
import com.example.configapp.data.ConfigRepository
import com.example.configapp.data.ProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SubInfo(
    val title: String = "Моя подписка",
    val expire: String = "Неизвестно",
    val traffic: String = "0 / 0 ГБ"
)

class ConfigViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ConfigRepository(app)
    private val _configs = MutableStateFlow<List<ProxyConfig>>(emptyList())
    val configs: StateFlow<List<ProxyConfig>> = _configs.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _subInfo = MutableStateFlow<SubInfo?>(null)
    val subInfo: StateFlow<SubInfo?> = _subInfo.asStateFlow()

    // Для пинга
    private val _pingResults = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pingResults: StateFlow<Map<String, Int>> = _pingResults.asStateFlow()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    init { viewModelScope.launch { _configs.value = repo.loadAll() } }

    fun pingAllServers() {
        if (_isPinging.value) return
        viewModelScope.launch {
            _isPinging.value = true
            val results = mutableMapOf<String, Int>()
            
            withContext(Dispatchers.IO) {
                _configs.value.forEach { config ->
                    val start = System.currentTimeMillis()
                    try {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(config.host, config.port), 2000)
                        socket.close()
                        results[config.id] = (System.currentTimeMillis() - start).toInt()
                    } catch (e: Exception) {
                        results[config.id] = -1 // -1 значит мертв
                    }
                }
            }
            _pingResults.value = results
            _isPinging.value = false
        }
    }

    fun importFromText(text: String) {
        viewModelScope.launch {
            _message.value = "Загрузка..."
            val input = text.trim()
            
            if (input.startsWith("http://") || input.startsWith("https://")) {
                val result = downloadAndParseSubscription(input)
                if (result.first.isEmpty()) {
                    _message.value = result.second
                    return@launch
                }
                _subInfo.value = result.third
                val merged = _configs.value + result.first
                _configs.value = merged
                repo.saveAll(merged)
                _message.value = "Добавлено серверов: ${result.first.size}"
            } else {
                val items = parseRawText(input)
                if (items.isEmpty()) {
                    _message.value = "Не найдено валидных конфигов"
                    return@launch
                }
                val merged = _configs.value + items
                _configs.value = merged
                repo.saveAll(merged)
                _message.value = "Добавлено конфигов: ${items.size}"
            }
        }
    }

    private suspend fun downloadAndParseSubscription(url: String): Triple<List<ProxyConfig>, String, SubInfo?> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", "v2rayNG/1.8.5").build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext Triple(emptyList(), "Ошибка сети: ${response.code}", null)
                
                val title = response.header("profile-title") ?: "Подписка"
                val userInfoStr = response.header("subscription-userinfo")
                
                var expireStr = "Неизвестно"
                var trafficStr = "0 / 0 ГБ"
                
                if (userInfoStr != null) {
                    val parts = userInfoStr.split(";").map { it.trim() }
                    var download = 0L; var total = 0L; var expireTime = 0L
                    for (part in parts) {
                        if (part.startsWith("download=")) download = part.substringAfter("=").toLongOrNull() ?: 0
                        if (part.startsWith("total=")) total = part.substringAfter("=").toLongOrNull() ?: 0
                        if (part.startsWith("expire=")) expireTime = part.substringAfter("=").toLongOrNull() ?: 0
                    }
                    if (expireTime > 0) {
                        val date = Date(expireTime * 1000)
                        expireStr = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(date)
                    }
                    if (total > 0) trafficStr = "${formatBytes(download)} / ${formatBytes(total)}"
                }

                val subInfo = SubInfo(title, expireStr, trafficStr)
                var body: String? = response.body?.string()
                if (body.isNullOrBlank()) return@withContext Triple(emptyList(), "Пустой ответ", null)
                var safeBody: String = body!!.trim()

                if (!safeBody.contains("://")) {
                    try {
                        val cleanBase64 = safeBody.replace('-', '+').replace('_', '/').replace("\n", "").replace("\r", "")
                        val padded = when (cleanBase64.length % 4) { 2 -> "$cleanBase64=="; 3 -> "$cleanBase64="; else -> cleanBase64 }
                        val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                        if (decoded.contains("://")) safeBody = decoded
                    } catch (e: Exception) {}
                }
                Triple(parseRawText(safeBody), "OK", subInfo)
            } catch (e: Exception) {
                Triple(emptyList(), "Ошибка загрузки", null)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun parseRawText(text: String): List<ProxyConfig> {
        return text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { ConfigParser.parse(it) }
    }

    fun delete(config: ProxyConfig) {
        val updated = _configs.value.filterNot { it.id == config.id }
        _configs.value = updated
        repo.saveAll(updated)
    }

    fun clearAll() {
        _configs.value = emptyList()
        repo.saveAll(emptyList())
        _subInfo.value = null
        _pingResults.value = emptyMap()
        _message.value = "Все сервера удалены"
    }

    fun consumeMessage() { _message.value = null }
}
