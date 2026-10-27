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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    init { viewModelScope.launch { _configs.value = repo.loadAll() } }

    fun importFromText(text: String) {
        viewModelScope.launch {
            _message.value = "Загрузка..."
            val input = text.trim()
            
            if (input.startsWith("http://") || input.startsWith("https://")) {
                val result = downloadAndParseSubscription(input)
                if (result.isEmpty()) {
                    // Если пусто, выводим первые 30 символов того, что скачалось
                    _message.value = "Не распознано. Ответ сервера: ${result.firstOrNull() ?: "пусто"}"
                } else {
                    val merged = _configs.value + result
                    _configs.value = merged
                    repo.saveAll(merged)
                    _message.value = "Добавлено конфигов: ${result.size}"
                }
            } else {
                val items = parseRawText(input)
                if (items.isEmpty()) {
                    _message.value = "Не найдено валидных конфигов"
                } else {
                    val merged = _configs.value + items
                    _configs.value = merged
                    repo.saveAll(merged)
                    _message.value = "Добавлено конфигов: ${items.size}"
                }
            }
        }
    }

    private suspend fun downloadAndParseSubscription(url: String): List<ProxyConfig> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "v2rayNG/1.8.5") // Ставим User-Agent как у реального клиента
                    .build()
                
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext listOf("HTTP Error: ${response.code}")
                
                var body = response.body?.string()?.trim() ?: return@withContext listOf("Пустой ответ")

                // Если это HTML (ошибка Cloudflare)
                if (body.startsWith("<")) {
                    return@withContext listOf("Получен HTML: ${body.take(30)}")
                }

                // Пытаемся декодировать Base64 (с поддержкой URL-safe и без переносов)
                if (!body.contains("://")) {
                    try {
                        val cleanBody = body.replace("\n", "").replace("\r", "").replace("-", "+").replace("_", "/")
                        val padded = when (cleanBody.length % 4) { 2 -> "$cleanBody=="; 3 -> "$cleanBody="; else -> cleanBody }
                        body = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                    } catch (e: Exception) {
                        return@withContext listOf("Ошибка Base64")
                    }
                }
                
                val parsed = parseRawText(body)
                if (parsed.isEmpty()) {
                    // Если не распарсили, возвращаем кусок текста для дебага
                    listOf(body.take(30))
                } else {
                    parsed
                }
            } catch (e: Exception) {
                listOf("Ошибка сети: ${e.message?.take(20)}")
            }
        }
    }

    private fun parseRawText(text: String): List<ProxyConfig> {
        return text.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { ConfigParser.parse(it) }
    }

    fun delete(config: ProxyConfig) {
        val updated = _configs.value.filterNot { it.id == config.id }
        _configs.value = updated
        repo.saveAll(updated)
    }

    fun consumeMessage() { _message.value = null }
}
