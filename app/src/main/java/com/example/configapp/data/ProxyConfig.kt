package com.example.configapp.data

data class ProxyConfig(
    val id: String,
    val name: String,
    val protocol: Protocol,
    val host: String,
    val port: Int,
    val rawLink: String,
    val extra: Map<String, String> = emptyMap()
)

enum class Protocol { VLESS, VMESS, TROJAN, SHADOWSOCKS }
