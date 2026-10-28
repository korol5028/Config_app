package com.example.configapp.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.configapp.data.Protocol
import com.example.configapp.data.ProxyConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    viewModel: ConfigViewModel,
    theme: AppTheme,
    onAddClick: (String) -> Unit // Передает текст в окно импорта
) {
    val configs by viewModel.configs.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedProtocol by remember { mutableStateOf<Protocol?>(null) }
    
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Логика фильтрации
    val filteredConfigs = configs.filter { config ->
        val matchesSearch = config.name.contains(searchQuery, ignoreCase = true) || 
                            config.host.contains(searchQuery, ignoreCase = true)
        val matchesProtocol = selectedProtocol == null || config.protocol == selectedProtocol
        matchesSearch && matchesProtocol
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.bg)
            .padding(horizontal = 16.dp)
    ) {
        // Шапка
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Мои сервера", color = theme.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("${configs.size} конфигов", color = theme.textGray, fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { Toast.makeText(context, "Синхронизация...", Toast.LENGTH_SHORT).show() }, modifier = Modifier.background(theme.cardBg, CircleShape)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Обновить", tint = theme.accent)
                }
                IconButton(onClick = { Toast.makeText(context, "Замер пинга в разработке", Toast.LENGTH_SHORT).show() }, modifier = Modifier.background(theme.cardBg, CircleShape)) {
                    Icon(Icons.Default.Speed, contentDescription = "Пинг", tint = theme.accent)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Строка поиска
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
            placeholder = { Text("Поиск по названию или IP...", color = theme.textGray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = theme.textGray) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Очистить", tint = theme.textGray)
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = theme.cardBg,
                unfocusedContainerColor = theme.cardBg,
                disabledContainerColor = theme.cardBg,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = theme.text,
                unfocusedTextColor = theme.text
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Фильтры
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = selectedProtocol == null,
                    onClick = { selectedProtocol = null },
                    label = { Text("Все") },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = theme.accent,
                        selectedLabelColor = theme.accentText,
                        containerColor = theme.cardBg,
                        labelColor = theme.textGray
                    ),
                    border = null
                )
            }
            items(Protocol.values()) { protocol ->
                FilterChip(
                    selected = selectedProtocol == protocol,
                    onClick = { selectedProtocol = protocol },
                    label = { Text(protocol.name) },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = theme.accent,
                        selectedLabelColor = theme.accentText,
                        containerColor = theme.cardBg,
                        labelColor = theme.textGray
                    ),
                    border = null
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Кнопки действий
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { onAddClick("") },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = theme.selectedCardBg, contentColor = theme.accent)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Импорт", fontWeight = FontWeight.Bold)
            }
            
            Button(
                onClick = {
                    val clipboardText = clipboardManager.getText()?.text
                    if (!clipboardText.isNullOrBlank()) {
                        onAddClick(clipboardText)
                    } else {
                        Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = theme.cardBg, contentColor = theme.text)
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Вставить", fontWeight = FontWeight.Bold)
            }
            
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(theme.cardBg)
                    .clickable { Toast.makeText(context, "QR-сканер в следующем обновлении", Toast.LENGTH_SHORT).show() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "QR", tint = theme.accent)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Список серверов
        if (filteredConfigs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = theme.textGray, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Ничего не найдено", color = theme.textGray, fontSize = 16.sp)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(filteredConfigs) { config ->
                    ServerCard(
                        cfg = config,
                        isSelected = false,
                        theme = theme,
                        onClick = { /* Логика выбора сервера */ }
                    )
                }
            }
        }
    }
}
