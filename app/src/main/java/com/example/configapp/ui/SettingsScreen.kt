package com.example.configapp.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(theme: AppTheme, onThemeChange: (AppTheme) -> Unit, viewModel: ConfigViewModel) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text("Настройки", color = theme.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))

        // Оформление (Темы)
        SettingsGroupTitle("Оформление", theme)
        SettingsCard(theme) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(theme.selectedCardBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = theme.accent, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Тема", color = theme.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(theme.name, color = theme.textGray, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ThemeColorButton(GreenTheme, theme, Color(0xFFA3E635), onThemeChange)
                    ThemeColorButton(DarkBlueTheme, theme, Color(0xFF3B82F6), onThemeChange)
                    ThemeColorButton(WhiteTheme, theme, Color(0xFFF3F4F6), onThemeChange)
                    ThemeColorButton(BlackTheme, theme, Color(0xFF111111), onThemeChange)
                    ThemeColorButton(HiddifyTheme, theme, Color(0xFF8B5CF6), onThemeChange)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Информация (Ссылки)
        SettingsGroupTitle("Информация", theme)
        SettingsCard(theme) {
            SettingsRowItem(
                icon = Icons.Default.Send,
                iconBg = Color(0xFF1E3A8A),
                title = "Информационный канал",
                subtitle = "Новости и обновления",
                theme = theme,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/halyava_vpnz"))
                    context.startActivity(intent)
                }
            )
            HorizontalDivider(color = theme.bg)
            SettingsRowItem(
                icon = Icons.Default.Handshake,
                iconBg = Color(0xFF3A1E1E),
                title = "Наш партнер",
                subtitle = "Получить доступ / Связаться",
                theme = theme,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/halyava_vpnz"))
                    context.startActivity(intent)
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Данные (Очистка)
        SettingsGroupTitle("Данные", theme)
        SettingsCard(theme) {
            SettingsRowItem(
                icon = Icons.Default.Delete,
                iconBg = Color(0xFF3A1E1E),
                title = "Очистить все сервера",
                subtitle = "Удалить все добавленные конфиги",
                theme = theme,
                onClick = { viewModel.clearAll() }
            )
        }

        Spacer(modifier = Modifier.height(100.dp))
    }
}

@Composable
fun ThemeColorButton(targetTheme: AppTheme, currentTheme: AppTheme, circleColor: Color, onThemeChange: (AppTheme) -> Unit) {
    val isSelected = targetTheme.name == currentTheme.name
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(circleColor)
            .border(2.dp, if (isSelected) currentTheme.accent else Color(0xFF555555), CircleShape)
            .clickable { onThemeChange(targetTheme) },
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                Icons.Default.Check, 
                contentDescription = null, 
                tint = if (circleColor == Color(0xFF111111) || circleColor == Color(0xFF16123F) || circleColor == Color(0xFF3B82F6)) Color.White else Color.Black
            )
        }
    }
}

@Composable
fun SettingsGroupTitle(title: String, theme: AppTheme) {
    Text(title, color = theme.textGray, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
}

@Composable
fun SettingsCard(theme: AppTheme, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(theme.cardBg)) {
        content()
    }
}

@Composable
fun SettingsRowItem(icon: ImageVector, iconBg: Color, title: String, subtitle: String, theme: AppTheme, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(iconBg), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = theme.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = theme.textGray, fontSize = 13.sp)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = theme.textGray)
    }
}
