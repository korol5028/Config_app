package com.example.configapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.configapp.data.ProxyConfig

// Цвета для темной темы
val DarkBg = Color(0xFF121212)
val CardBg = Color(0xFF1E1E1E)
val AccentGreen = Color(0xFF00E676)
val AccentRed = Color(0xFFCF6679)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    viewModel: ConfigViewModel,
    onAddClick: () -> Unit
) {
    val configs by viewModel.configs.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var isConnected by remember { mutableStateOf(false) } // Фейковое состояние

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = DarkBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddClick,
                containerColor = Color(0xFF2C2C2C),
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "Добавить конфиг")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Статус
            Text(
                text = if (isConnected) "ПОДКЛЮЧЕНО" else "ОТКЛЮЧЕНО",
                color = if (isConnected) AccentGreen else AccentRed,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Большая фейковая кнопка подключения
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) AccentGreen.copy(alpha = 0.15f) else Color(0xFF2C2C2C))
                    .clickable { isConnected = !isConnected }, // Переключатель
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) AccentGreen else Color(0xFF3E3E3E)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Connect",
                        tint = if (isConnected) Color.Black else Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Заголовок списка
            Text(
                text = "Мои серверы",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Список конфигов
            if (configs.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "Нет серверов.\nНажмите +, чтобы добавить.",
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
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
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Кружок с первой буквой протокола
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3E3E3E)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = cfg.protocol.name.take(1),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = cfg.name,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${cfg.protocol.name} · ${cfg.host}:${cfg.port}",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Удалить",
                    tint = Color.Gray
                )
            }
        }
    }
}
