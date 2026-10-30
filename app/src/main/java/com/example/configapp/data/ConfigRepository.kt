package com.example.configapp.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ConfigRepository(context: Context) {
    // Сохраняем в обычный файл, а не в SharedPreferences
    private val configFile = File(context.filesDir, "velox_configs.json")

    // Загрузка (вызывать только из корутины/фонового потока)
    suspend fun loadAll(): List<ProxyConfig> = withContext(Dispatchers.IO) {
        if (!configFile.exists()) return@withContext emptyList()
        return@withContext try {
            val raw = configFile.readText()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    ProxyConfig(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        protocol = Protocol.valueOf(o.getString("protocol")),
                        host = o.getString("host"),
                        port = o.getInt("port"),
                        rawLink = o.getString("rawLink"),
                        extra = o.optJSONObject("extra")?.let { toMap(it) } ?: emptyMap()
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Сохранение (в фоне)
    suspend fun saveAll(items: List<ProxyConfig>) = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray()
            items.forEach { c ->
                arr.put(JSONObject().apply {
                    put("id", c.id); put("name", c.name)
                    put("protocol", c.protocol.name)
                    put("host", c.host); put("port", c.port)
                    put("rawLink", c.rawLink)
                    put("extra", JSONObject().apply { c.extra.forEach { (k, v) -> put(k, v) } })
                })
            }
            configFile.writeText(arr.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toMap(o: JSONObject) = mutableMapOf<String, String>().also { m ->
        val keys = o.keys(); while (keys.hasNext()) keys.next().let { m[it] = o.optString(it) }
    }
}
