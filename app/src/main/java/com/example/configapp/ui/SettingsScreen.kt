package com.example.configapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Цвета из референса
val BgDarkGreen = Color(0xFF0A120D)
val CardDarkGreen = Color(0xFF141D17)
val AccentGreen = Color(0xFF2BB673)
val TextWhite = Color(0xFFFFFFFF)
val TextGray = Color(0xFF88958D)
val IconBgGreen = Color(0xFF1E3A2B)
val IconBgRed = Color(0xFF3A1E1E)

@Composable
fun SettingsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDarkGreen)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text("Настройки", color = TextWhite, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))

        // Группа 1: Оформление
        SettingsGroupTitle("Оформление")
        SettingsCard {
            SettingsRowItem(
                icon = Icons.Default.Palette,
                iconBg = IconBgGreen,
                title = "Тема",
                subtitle = "Зелёная",
                showArrow = true
            )
            HorizontalDivider(color = BgDarkGreen)
            SettingsRowItem(
                icon = Icons.Default.Language,
                iconBg = IconBgGreen,
                title = "Язык",
                subtitle = "Как в системе",
                showArrow = true
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Группа 2: Соединение
        SettingsGroupTitle("Соединение")
        SettingsCard {
            SettingsRowItem(
                icon = Icons.Default.FlashOn,
                iconBg = IconBgGreen,
                title = "Автоподключение",
                subtitle = "Подключаться при запуске приложения",
                showSwitch = true
            )
            HorizontalDivider(color = BgDarkGreen)
            SettingsRowItem(
                icon = Icons.Default.Security,
                iconBg = IconBgRed,
                title = "Kill Switch",
                subtitle = "Блокировать интернет без VPN",
                showSwitch = true
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Группа 3: Туннель
        SettingsGroupTitle("Туннель")
        SettingsCard {
            SettingsRowItem(
                icon = Icons.Default.CallSplit,
                iconBg = IconBgGreen,
                title = "Маршрутизация",
                subtitle = "Настроить",
                showArrow = true
            )
            HorizontalDivider(color = BgDarkGreen)
            SettingsRowItem(
                icon = Icons.Default.GridOn,
                iconBg = IconBgGreen,
                title = "Фрагментирование",
                subtitle = "Обход DPI через фрагментацию пакетов",
                showSwitch = true
            )
            HorizontalDivider(color = BgDarkGreen)
            SettingsRowItem(
                icon = Icons.Default.MergeType,
                iconBg = IconBgGreen,
                title = "Мультиплексирование (Mux)",
                subtitle = "Объединение соединений в один канал",
                showSwitch = true
            )
            HorizontalDivider(color = BgDarkGreen)
            SettingsRowItem(
                icon = Icons.Default.Public,
                iconBg = IconBgGreen,
                title = "Тип IP",
                subtitle = "ipv4",
                showArrow = true
            )
        }
        
        Spacer(modifier = Modifier.height(100.dp)) // Отступ для нижнего меню
    }
}

@Composable
fun SettingsGroupTitle(title: String) {
    Text(
        text = title,
        color = TextGray,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardDarkGreen)
    ) {
        content()
    }
}

@Composable
fun SettingsRowItem(
    icon: ImageVector,
    iconBg: Color,
    title: String,
    subtitle: String,
    showSwitch: Boolean = false,
    showArrow: Boolean = false
) {
    var isChecked by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (showSwitch) isChecked = !isChecked }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Иконка с фоном
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(20.dp))
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Текст
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = TextGray, fontSize = 13.sp, maxLines = 2)
            }
        }

        // Переключатель или стрелочка
        if (showSwitch) {
            Switch(
                checked = isChecked,
                onCheckedChange = { isChecked = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TextWhite,
                    checkedTrackColor = AccentGreen,
                    uncheckedThumbColor = TextGray,
                    uncheckedTrackColor = Color(0xFF2C3530)
                )
            )
        } else if (showArrow) {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextGray)
        }
    }
}
