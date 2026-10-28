package com.example.configapp.ui

import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.configapp.VeloxVpnService
import com.example.configapp.data.ProxyConfig

@Composable
fun ConfigScreen(viewModel: ConfigViewModel, theme: AppTheme) {
    val configs by viewModel.configs.collectAsState()
    var isConnected by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(true) }
    
    // Храним выбранный сервер
    var selectedConfig by remember { mutableStateOf<ProxyConfig?>(null) }
    
    // Если список загрузился, а сервер не выбран, выбираем первый по умолчанию
    LaunchedEffect(configs) {
        if (selectedConfig == null && configs.isNotEmpty()) {
            selectedConfig = configs.first()
        }
    }

    val context = LocalContext.current

    // Лаунчер для запроса разрешения на VPN
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // Пользователь разрешил VPN
            selectedConfig?.let { config ->
                startVpnService(context, config.rawLink)
                isConnected = true
            }
        } else {
            // Пользователь отказал
            isConnected = false
            Toast.makeText(context, "Разрешение на VPN отклонено", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.bg)
            .padding(horizontal = 16.dp)
    ) {
        // Верхняя панель
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Velox", color = theme.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(4.dp))
                Text("v1.0", color = theme.textGray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(theme.cardBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Language, contentDescription = null, tint = theme.textGray, modifier = Modifier.size(20.dp))
            }
        }

        // Кнопка подключения
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .border(4.dp, if (isConnected) theme.accent else theme.border, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(theme.cardBg)
                    .clickable {
                        if (isConnected) {
                            // Выключаем VPN
                            val intent = Intent(context, VeloxVpnService::class.java).apply { action = "STOP" }
                            context.startService(intent)
                            isConnected = false
                        } else {
                            // Включаем VPN
                            if (selectedConfig == null) {
                                Toast.makeText(context, "Сначала выберите сервер", Toast.LENGTH_SHORT).show()
                                return@clickable
                            }
                            
                            // Проверяем, нужно ли запрашивать разрешение
                            val prepareIntent = VpnService.prepare(context)
                            if (prepareIntent != null) {
                                vpnPermissionLauncher.launch(prepareIntent)
                            } else {
                                // Разрешение уже есть
                                startVpnService(context, selectedConfig!!.rawLink)
                                isConnected = true
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = if (isConnected) theme.accent else theme.textGray,
                    modifier = Modifier.size(56.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (isConnected) "Подключено" else "Нажмите для подключения",
                color = if (isConnected) theme.accent else theme.textGray,
                fontSize = 14.sp
            )
            
            // Отображаем имя выбранного сервера под кнопкой
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = selectedConfig?.name ?: "Сервер не выбран",
                color = theme.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text("ТЕКУЩАЯ ПОДПИСКА", color = theme.textGray, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(8.dp))

        // Список серверов
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, theme.border, RoundedCornerShape(16.dp))
                .background(theme.cardBg)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = theme.textGray)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Мои серверы", color = theme.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier.clip(CircleShape).background(theme.selectedCardBg).padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("${configs.size}", color = theme.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (isExpanded) {
                if (configs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Нет серверов. Добавьте через +", color = theme.textGray)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 80.dp, start = 8.dp, end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(configs) { cfg ->
                            ServerCard(
                                cfg = cfg,
                                isSelected = (cfg.id == selectedConfig?.id),
                                theme = theme,
                                onClick = { selectedConfig = cfg }
                            )
                        }
                    }
                }
            }
        }
    }
}

// Вспомогательная функция для запуска нашего сервиса
fun startVpnService(context: android.content.Context, configLink: String) {
    val intent = Intent(context, VeloxVpnService::class.java).apply {
        putExtra("CONFIG_JSON", configLink)
    }
    ContextCompat.startForegroundService(context, intent)
}

@Composable
fun ServerCard(cfg: ProxyConfig, isSelected: Boolean, theme: AppTheme, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) theme.selectedCardBg else theme.cardBg)
            .border(1.dp, if (isSelected) theme.accent else theme.bg, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(theme.selectedCardBg),
            contentAlignment = Alignment.Center
        ) {
            Text("🌐", fontSize = 16.sp)
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(cfg.name, color = theme.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(cfg.protocol.name, theme)
                Tag("TCP", theme)
                Tag("REALITY", theme)
            }
        }
        
        Icon(Icons.Default.MoreVert, contentDescription = null, tint = theme.textGray)
    }
}

@Composable
fun Tag(text: String, theme: AppTheme) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(theme.selectedCardBg).padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = theme.textGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}
