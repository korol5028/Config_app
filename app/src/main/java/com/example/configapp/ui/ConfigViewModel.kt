package com.example.configapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.configapp.data.ConfigParser
import com.example.configapp.data.ConfigRepository
import com.example.configapp.data.ProxyConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConfigViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ConfigRepository(app)
    private val _configs = MutableStateFlow<List<ProxyConfig>>(emptyList())
    val configs: StateFlow<List<ProxyConfig>> = _configs.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { viewModelScope.launch { _configs.value = repo.loadAll() } }

    fun importFromText(text: String) {
        val items = text.split('\n', '\r').map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { ConfigParser.parse(it) }
        if (items.isEmpty()) {
            _message.value = "Не найдено валидных конфигов"
            return
        }
        val merged = _configs.value + items
        _configs.value = merged
        repo.saveAll(merged)
        _message.value = "Добавлено конфигов: ${items.size}"
    }

    fun delete(config: ProxyConfig) {
        val updated = _configs.value.filterNot { it.id == config.id }
        _configs.value = updated
        repo.saveAll(updated)
    }

    fun consumeMessage() { _message.value = null }
}
