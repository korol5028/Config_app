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
                
                val body = response.body?.string()?.trim() ?: return@withContext Pair(emptyList<ProxyConfig>(), "Пустой ответ")

                // Вызываем наш супер-парсер
                val extractedLinks = extractLinksFromText(body)
                
                if (extractedLinks.isNotEmpty()) {
                    val parsed = parseRawText(extractedLinks.joinToString("\n"))
                    if (parsed.isNotEmpty()) {
                        return@withContext Pair(parsed, null)
                    }
                }
                
                return@withContext Pair(emptyList<ProxyConfig>(), "Не распознано. Ответ: ${body.take(30)}")

            } catch (e: Exception) {
                Pair(emptyList<ProxyConfig>(), "Ошибка сети: ${e.message?.take(20)}")
            }
        }
    }

    // МЕТОД, КОТОРЫЙ ВЫТАСКИВАЕТ ССЫЛКИ ИЗ ЛЮБОГО ФОРМАТА
    private fun extractLinksFromText(text: String): List<String> {
        val links = mutableListOf<String>()
        // Ищем популярные протоколы
        val regex = Regex("""(vless|vmess|trojan|ss|hysteria|hysteria2|hy2|tuic)://[^\s"']+""")
        
        // 1. Прямой поиск
        links.addAll(regex.findAll(text).map { it.value })
        
        // 2. Поиск внутри Base64 (если весь текст закодирован)
        if (links.isEmpty()) {
            try {
                val clean = text.replace("\n", "").replace("\r", "").replace("-", "+").replace("_", "/")
                val padded = when (clean.length % 4) { 2 -> "$clean=="; 3 -> "$clean="; else -> clean }
                val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                links.addAll(regex.findAll(decoded).map { it.value })
            } catch (e: Exception) {}
        }
        
        // 3. Если это JSON - заходим внутрь и проверяем каждое поле
        if (links.isEmpty() && text.trim().startsWith("[")) {
            try {
                val jsonArray = JSONArray(text)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i)
                    if (obj != null) {
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = obj.optString(key)
                            
                            // Если значение - это прямая ссылка
                            if (regex.containsMatchIn(value)) {
                                links.addAll(regex.findAll(value).map { it.value })
                            } else {
                                // Если значение - это Base64
                                try {
                                    val clean = value.replace("\n", "").replace("\r", "").replace("-", "+").replace("_", "/")
                                    val padded = when (clean.length % 4) { 2 -> "$clean=="; 3 -> "$clean="; else -> clean }
                                    val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                                    if (regex.containsMatchIn(decoded)) {
                                        links.addAll(regex.findAll(decoded).map { it.value })
                                    }
                                } catch (e: Exception) {}
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }
        
        return links.distinct()
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
