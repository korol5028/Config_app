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
import org.json.JSONArray
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
                val (downloadedItems, errorMsg) = downloadAndParseSubscription(input)
                
                if (errorMsg != null) {
                    _message.value = errorMsg
                } else if (downloadedItems.isEmpty()) {
                    _message.value = "Не найдено валидных конфигов"
                } else {
                    val merged = _configs.value + downloadedItems
                    _configs.value = merged
                    repo.saveAll(merged)
                    _message.value = "Добавлено конфигов: ${downloadedItems.size}"
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

    private suspend fun downloadAndParseSubscription(url: String): Pair<List<ProxyConfig>, String?> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "v2rayNG/1.8.5")
                    .build()
                
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext Pair(emptyList<ProxyConfig>(), "HTTP Error: ${response.code}")
                
                var body = response.body?.string()?.trim() ?: return@withContext Pair(emptyList<ProxyConfig>(), "Пустой ответ")

                // 1. Проверка на JSON формат
                if (body.startsWith("[")) {
                    try {
                        val jsonArray = JSONArray(body)
                        val extractedLinks = mutableListOf<String>()
                        for (i in 0 until jsonArray.length()) {
                            val jsonObject = jsonArray.getJSONObject(i)
                            // Ищем поле с ссылкой (url, link, config)
                            if (jsonObject.has("url")) extractedLinks.add(jsonObject.getString("url"))
                            else if (jsonObject.has("link")) extractedLinks.add(jsonObject.getString("link"))
                            else if (jsonObject.has("config")) extractedLinks.add(jsonObject.getString("config"))
                            else {
                                // Если ключи другие, пробегаемся по всем строкам и ищем "://"
                                val keys = jsonObject.keys()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    val value = jsonObject.optString(key)
                                    if (value.contains("://")) {
                                        extractedLinks.add(value)
                                        break
                                    }
                                }
                            }
                        }
                        val parsedFromJson = parseRawText(extractedLinks.joinToString("\n"))
                        if (parsedFromJson.isNotEmpty()) return@withContext Pair(parsedFromJson, null)
                    } catch (e: Exception) {
                        // Если JSON битый, идем дальше
                    }
                }

                // 2. Проверка на HTML
                if (body.startsWith("<")) {
                    return@withContext Pair(emptyList<ProxyConfig>(), "Получен HTML")
                }

                // 3. Base64
                if (!body.contains("://")) {
                    try {
                        val cleanBody = body.replace("\n", "").replace("\r", "").replace("-", "+").replace("_", "/")
                        val padded = when (cleanBody.length % 4) { 2 -> "$cleanBody=="; 3 -> "$cleanBody="; else -> cleanBody }
                        body = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                    } catch (e: Exception) {
                        return@withContext Pair(emptyList<ProxyConfig>(), "Ошибка Base64")
                    }
                }
                
                // 4. Обычный текст
                val parsed = parseRawText(body)
                if (parsed.isEmpty()) {
                    return@withContext Pair(emptyList<ProxyConfig>(), "Не распознано. Ответ: ${body.take(30)}")
                } else {
                    Pair(parsed, null)
                }
            } catch (e: Exception) {
                Pair(emptyList<ProxyConfig>(), "Ошибка сети: ${e.message?.take(20)}")
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
