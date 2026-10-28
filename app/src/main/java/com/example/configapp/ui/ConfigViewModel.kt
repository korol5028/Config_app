package com.example.configapp.ui

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.configapp.data.ConfigParser
import com.example.configapp.data.ConfigRepository
import com.example.configapp.data.ProxyConfig
import com.example.configapp.data.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.UUID
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

                // 1. Пытаемся распарсить как массив JSON (Xray configs)
                if (body.startsWith("[")) {
                    val parsedJson = parseXrayJsonArray(body)
                    if (parsedJson.isNotEmpty()) {
                        return@withContext Pair(parsedJson, null)
                    }
                }

                // 2. Пытаемся распарсить как обычные ссылки (vless://...)
                val linkRegex = Regex("""(vless|vmess|trojan|ss)://[^\s"']+""")
                val foundLinks = linkRegex.findAll(body).map { it.value }.toList()
                
                if (foundLinks.isNotEmpty()) {
                    val parsed = parseRawText(foundLinks.joinToString("\n"))
                    if (parsed.isNotEmpty()) {
                        return@withContext Pair(parsed, null)
                    }
                }

                // 3. Если это Base64
                if (!body.contains("://")) {
                    try {
                        val cleanBody = body.replace("\n", "").replace("\r", "").replace("-", "+").replace("_", "/")
                        val padded = when (cleanBody.length % 4) { 2 -> "$cleanBody=="; 3 -> "$cleanBody="; else -> cleanBody }
                        val decodedBody = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                        
                        val decodedLinks = linkRegex.findAll(decodedBody).map { it.value }.toList()
                        if (decodedLinks.isNotEmpty()) {
                             val parsed = parseRawText(decodedLinks.joinToString("\n"))
                             if (parsed.isNotEmpty()) {
                                 return@withContext Pair(parsed, null)
                             }
                        }
                    } catch (e: Exception) {
                        return@withContext Pair(emptyList<ProxyConfig>(), "Ошибка Base64")
                    }
                }
                
                return@withContext Pair(emptyList<ProxyConfig>(), "Не распознано. Ответ: ${body.take(30)}")

            } catch (e: Exception) {
                Pair(emptyList<ProxyConfig>(), "Ошибка сети: ${e.message?.take(20)}")
            }
        }
    }

    // ПАРСЕР ДЛЯ ФОРМАТА XRAY JSON
    private fun parseXrayJsonArray(jsonString: String): List<ProxyConfig> {
        val configs = mutableListOf<ProxyConfig>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val name = obj.optString("remarks", "Server $i")
                val outbounds = obj.optJSONArray("outbounds") ?: continue
                
                // Ищем в outbounds блок с протоколом vless
                for (j in 0 until outbounds.length()) {
                    val outbound = outbounds.getJSONObject(j)
                    val protocol = outbound.optString("protocol")
                    
                    if (protocol == "vless") {
                        val settings = outbound.optJSONObject("settings") ?: continue
                        val vnext = settings.optJSONArray("vnext")?.optJSONObject(0) ?: continue
                        val host = vnext.optString("address")
                        val port = vnext.optInt("port")
                        val users = vnext.optJSONArray("users")?.optJSONObject(0) ?: continue
                        val uuid = users.optString("id")
                        val flow = users.optString("flow", "")
                        
                        val streamSettings = outbound.optJSONObject("streamSettings") ?: continue
                        val network = streamSettings.optString("network", "tcp")
                        val security = streamSettings.optString("security", "none")
                        
                        val extra = mutableMapOf<String, String>()
                        extra["uuid"] = uuid
                        if (flow.isNotEmpty()) extra["flow"] = flow
                        extra["network"] = network
                        
                        if (security == "reality") {
                            val reality = streamSettings.optJSONObject("realitySettings") ?: continue
                            extra["pbk"] = reality.optString("publicKey")
                            extra["sid"] = reality.optString("shortId")
                            extra["sni"] = reality.optString("serverName")
                            extra["fp"] = reality.optString("fingerprint", "chrome")
                        }
                        
                        val config = ProxyConfig(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            protocol = Protocol.VLESS,
                            host = host,
                            port = port,
                            rawLink = "", 
                            extra = extra
                        )
                        configs.add(config)
                        break // Берем первый vless из этого объекта и идем к следующему
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return configs
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
