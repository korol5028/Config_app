package com.example.configapp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.configapp.data.ProxyConfig

@Composable
fun ConfigScreen(
    viewModel: ConfigViewModel,
    isMainScreen: Boolean
) {
    val configs by viewModel.configs.collectAsState()
    
    if (isMainScreen) {
        // Экран "Подключение"
        var isConnected by remember { mutableStateOf(false) }
        val animatedColor by animateColorAsState(if (isConnected) AccentGreen else Color(0xFF2C3530), label = "")

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = if (isConnected) "ПОДКЛЮЧЕНО" else "ОТКЛЮЧЕНО",
                color = if (isConnected) AccentGreen else TextGray,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(40.dp))

            // Кнопка (без неона, просто красивая)
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .shadow(if (isConnected) 20.dp else 0.dp, CircleShape, ambientColor = AccentGreen, spotColor = AccentGreen)
                    .clip(CircleShape)
                    .background(animatedColor)
                    .clickable { isConnected = !isConnected },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PowerSettingsNew, 
                    contentDescription = null, 
                    tint = if (isConnected) Color.Black else TextWhite, 
                    modifier = Modifier.size(72.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // Здесь можно добавить карточку с текущим сервером
        }
    } else {
        // Экран "Серверы" (Список конфигов)
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Мои серверы", color = TextWhite, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            
            if (configs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Нет серверов.\nНажмите +, чтобы добавить.", color = TextGray, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 100.dp)) {
                    items(configs, key = { it.id }) { cfg ->
                        ConfigItem(cfg, onDelete = { viewModel.delete(cfg) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigItem(cfg: ProxyConfig, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardDarkGreen),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(IconBgGreen),
                contentAlignment = Alignment.Center
            ) {
                Text(cfg.protocol.name.take(1), color = AccentGreen, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(cfg.name, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("${cfg.protocol.name} · ${cfg.host}:${cfg.port}", color = TextGray, fontSize = 13.sp)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = TextGray)
            }
        }
    }
}
