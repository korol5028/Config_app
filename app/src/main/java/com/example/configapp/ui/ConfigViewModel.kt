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
import java.util.concurrent.TimeUnit

class ConfigViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ConfigRepository(app)
    private val _configs = MutableStateFlow<List<ProxyConfig>>(emptyList())
    val configs: StateFlow<List<ProxyConfig>> = _configs.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    init { viewModelScope.launch { _configs.value = repo.loadAll() } }

    fun importFromText(text: String) {
        viewModelScope.launch {
            _message.value = "Загрузка..."
            val input = text.trim()
            
            if (input.startsWith("http://") || input.startsWith("https://")) {
                val items = downloadAndParseSubscription(input)
                if (items.isEmpty()) {
                    _message.value = "Ошибка: Не удалось загрузить подписку"
                    return@launch
                }
                val merged = _configs.value + items
                _configs.value = merged
                repo.saveAll(merged)
                _message.value = "Добавлено из подписки: ${items.size}"
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

    private suspend fun downloadAndParseSubscription(url: String): List<ProxyConfig> {
        return withContext(Dispatchers.IO) {
            try {
                val userAgents = listOf(
                    "v2rayNG/1.8.5",
                    "Happ/1.0",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                )
                
                var body: String? = null
                
                for (ua in userAgents) {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", ua)
                        .build()
                    
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        body = response.body?.string()
                        if (!body.isNullOrBlank()) break
                    }
                }
                
                if (body.isNullOrBlank()) return@withContext emptyList()
                
                // СОЗДАЕМ БЕЗОПАСНУЮ ПЕРЕМЕННУЮ (без null)
                var safeBody: String = body!!.trim()

                if (safeBody.contains("<html", ignoreCase = true)) {
                    return@withContext emptyList()
                }

                if (!safeBody.contains("://")) {
                    try {
                        val cleanBase64 = safeBody
                            .replace('-', '+')
                            .replace('_', '/')
                            .replace("\n", "")
                            .replace("\r", "")
                        
                        val padded = when (cleanBase64.length % 4) {
                            2 -> "$cleanBase64=="
                            3 -> "$cleanBase64="
                            else -> cleanBase64
                        }
                        val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                        if (decoded.contains("://")) {
                            safeBody = decoded
                        }
                    } catch (e: Exception) {
                        // Игнорируем, если не получилось декодировать
                    }
                }
                
                // Передаем уже безопасную строку
                parseRawText(safeBody) 
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    private fun parseRawText(text: String): List<ProxyConfig> {
        return text.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { ConfigParser.parse(it) }
    }

    fun delete(config: ProxyConfig) {
        val updated = _configs.value.filterNot { it.id == config.id }
        _configs.value = updated
        repo.saveAll(updated)
    }

    fun consumeMessage() { _message.value = null }
}
