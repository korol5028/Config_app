package com.example.configapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ConfigRepository(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("configs", Context.MODE_PRIVATE)

    fun loadAll(): List<ProxyConfig> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
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
        } catch (e: Exception) { emptyList() }
    }

    fun saveAll(items: List<ProxyConfig>) {
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
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun toMap(o: JSONObject) = mutableMapOf<String, String>().also { m ->
        val keys = o.keys(); while (keys.hasNext()) keys.next().let { m[it] = o.optString(it) }
    }

    companion object { private const val KEY = "config_list" }
}
