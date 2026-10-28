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
import androidx.compose.ui.graphics.Color
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
    val subInfo by viewModel.subInfo.collectAsState()
    var isConnected by remember { mutableStateOf(false) }
    
    var selectedConfig by remember { mutableStateOf<ProxyConfig?>(null) }
    LaunchedEffect(configs) {
        if (selectedConfig == null && configs.isNotEmpty()) {
            selectedConfig = configs.first()
        }
    }

    val context = LocalContext.current

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            selectedConfig?.let { config ->
                startVpnService(context, config.rawLink)
                isConnected = true
            }
        } else {
            isConnected = false
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
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = theme.textGray)
        }

        // Кнопка
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(280.dp).border(1.dp, theme.border, CircleShape))
                Box(modifier = Modifier.size(220.dp).border(1.dp, theme.border, CircleShape))
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) theme.accent else theme.cardBg)
                        .clickable {
                            if (isConnected) {
                                val intent = Intent(context, VeloxVpnService::class.java).apply { action = "STOP" }
                                context.startService(intent)
                                isConnected = false
                            } else {
                                if (selectedConfig == null) {
                                    Toast.makeText(context, "Сначала добавьте сервер", Toast.LENGTH_SHORT).show()
                                    return@clickable
                                }
                                val prepareIntent = VpnService.prepare(context)
                                if (prepareIntent != null) {
                                    vpnPermissionLauncher.launch(prepareIntent)
                                } else {
                                    startVpnService(context, selectedConfig!!.rawLink)
                                    isConnected = true
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = selectedConfig?.name ?: "Сервер не выбран",
                color = theme.text, fontSize = 16.sp, fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isConnected) "ПОДКЛЮЧЕНО" else "НАЖМИТЕ ДЛЯ ПОДКЛЮЧЕНИЯ",
                color = if (isConnected) theme.accent else theme.textGray, fontSize = 11.sp, letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Список подписок
        LazyColumn(
            contentPadding = PaddingValues(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(configs) { cfg ->
                SubscriptionCard(
                    cfg = cfg,
                    isSelected = (cfg.id == selectedConfig?.id),
                    theme = theme,
                    subInfo = subInfo,
                    isFirstInList = cfg.id == configs.firstOrNull()?.id, // Инфу подписки показываем только в первой карточке
                    onClick = { selectedConfig = cfg },
                    onDelete = { viewModel.delete(cfg) }
                )
            }
        }
    }
}

@Composable
fun SubscriptionCard(cfg: ProxyConfig, isSelected: Boolean, theme: AppTheme, subInfo: SubInfo?, isFirstInList: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = if (isSelected) theme.selectedCardBg else theme.cardBg),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = theme.textGray)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(cfg.name, color = theme.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Автообновление: вкл.", color = theme.textGray, fontSize = 11.sp)
                }
                Icon(Icons.Default.Refresh, contentDescription = null, tint = theme.textGray, modifier = Modifier.padding(end = 12.dp))
                Icon(Icons.Default.Speed, contentDescription = null, tint = theme.textGray, modifier = Modifier.padding(end = 12.dp))
                Icon(Icons.Default.MoreVert, contentDescription = "Удалить", tint = theme.textGray, modifier = Modifier.clickable { onDelete() })
            }

            // Если это первый сервер в списке и есть данные о подписке — показываем их
            if (isSelected && isFirstInList && subInfo != null) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = theme.border, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(subInfo.traffic, color = theme.text, fontSize = 13.sp, modifier = Modifier.border(1.dp, theme.border, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
                    Text("Истекает: ${subInfo.expire}", color = theme.textGray, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("📦 ${subInfo.title}", color = theme.textGray, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Text("🔥 Трафик: ${subInfo.traffic}", color = theme.textGray, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

fun startVpnService(context: android.content.Context, configLink: String) {
    val intent = Intent(context, VeloxVpnService::class.java).apply {
        putExtra("CONFIG_JSON", configLink)
    }
    ContextCompat.startForegroundService(context, intent)
}
