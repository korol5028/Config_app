package com.example.configapp.ui

import android.app.Application
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    init {
        viewModelScope.launch {
            // Загружаем конфиги асинхронно при старте
            _configs.value = repo.loadAll()
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
                repo.saveAll(merged) // Вызов внутри корутины
                _message.value = "Добавлено серверов: ${result.first.size}"
            } else {
                val items = parseRawText(input)
                if (items.isEmpty()) {
                    _message.value = "Не найдено валидных конфигов"
                    return@launch
                }
                val merged = _configs.value + items
                _configs.value = merged
                repo.saveAll(merged) // Вызов внутри корутины
                _message.value = "Добавлено конфигов: ${items.size}"
            }
        }
    }

    private suspend fun downloadAndParseSubscription(url: String): Triple<List<ProxyConfig>, String, SubInfo?> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", "v2rayNG/1.8.5").build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext Triple(emptyList(), "Ошибка сети: ${response.code}", null)

                    val contentLength = response.body?.contentLength() ?: 0L
                    if (contentLength > 5 * 1024 * 1024) return@withContext Triple(emptyList(), "Файл слишком большой", null)

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
                        if (expireTime > 0) expireStr = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(expireTime * 1000))
                        if (total > 0) trafficStr = "${formatBytes(download)} / ${formatBytes(total)}"
                    }

                    val subInfo = SubInfo(title, expireStr, trafficStr)
                    var body: String? = response.body?.string()
                    if (body.isNullOrBlank()) return@withContext Triple(emptyList(), "Пустой ответ", null)
                    var safeBody: String = body!!.trim()

                    if (!safeBody.contains("://")) {
                        try { safeBody = decodeBase64Safe(safeBody) } catch (e: Exception) {}
                    }
                    Triple(parseRawText(safeBody), "OK", subInfo)
                }
            } catch (e: Exception) {
                Triple(emptyList(), "Ошибка загрузки: ${e.message}", null)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64Safe(str: String): String {
        val clean = str.replace('-', '+').replace('_', '/').replace("\n", "").replace("\r", "")
        val padded = when (clean.length % 4) { 2 -> "$clean=="; 3 -> "$clean="; else -> clean }
        val decodedBytes = try { Base64.UrlSafe.decode(padded) } catch (e: Exception) { Base64.Default.decode(padded) }
        return decodedBytes.decodeToString()
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
        viewModelScope.launch {
            val updated = _configs.value.filterNot { it.id == config.id }
            _configs.value = updated
            repo.saveAll(updated) // Вызов внутри корутины
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            _configs.value = emptyList()
            repo.saveAll(emptyList()) // Вызов внутри корутины
            _subInfo.value = null
            _message.value = "Все сервера удалены"
        }
    }

    fun consumeMessage() { _message.value = null }
}
