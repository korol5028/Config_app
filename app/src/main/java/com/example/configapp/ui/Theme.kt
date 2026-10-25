package com.example.configapp.ui

import androidx.compose.ui.graphics.Color

data class AppTheme(
    val name: String,
    val bg: Color,           // Фон приложения
    val cardBg: Color,       // Фон карточек и меню
    val selectedCardBg: Color, // Фон выбранного сервера
    val accent: Color,       // Главный цвет (кнопки, текст)
    val accentText: Color,   // Цвет текста на кнопках (черный или белый)
    val text: Color,         // Основной текст
    val textGray: Color,     // Второстепенный текст
    val border: Color        // Рамки
)

// 1. Зеленая (как на референсе)
val GreenTheme = AppTheme(
    name = "Зелёная",
    bg = Color(0xFF0B110D),
    cardBg = Color(0xFF131A15),
    selectedCardBg = Color(0xFF1E2B22),
    accent = Color(0xFFA3E635),
    accentText = Color.Black,
    text = Color.White,
    textGray = Color(0xFF88958D),
    border = Color(0xFF2A3B31)
)

// 2. Темно-синяя
val DarkBlueTheme = AppTheme(
    name = "Тёмно-синяя",
    bg = Color(0xFF0A0F1A),
    cardBg = Color(0xFF121A29),
    selectedCardBg = Color(0xFF1E2A45),
    accent = Color(0xFF3B82F6),
    accentText = Color.White,
    text = Color.White,
    textGray = Color(0xFF8B9DC3),
    border = Color(0xFF2B3A5A)
)

// 3. Белая
val WhiteTheme = AppTheme(
    name = "Белая",
    bg = Color(0xFFF3F4F6),
    cardBg = Color(0xFFFFFFFF),
    selectedCardBg = Color(0xFFE5E7EB),
    accent = Color(0xFF10B981),
    accentText = Color.White,
    text = Color(0xFF111827),
    textGray = Color(0xFF6B7280),
    border = Color(0xFFD1D5DB)
)

// 4. Черная
val BlackTheme = AppTheme(
    name = "Чёрная",
    bg = Color(0xFF000000),
    cardBg = Color(0xFF111111),
    selectedCardBg = Color(0xFF222222),
    accent = Color(0xFFFFFFFF),
    accentText = Color.Black,
    text = Color.White,
    textGray = Color(0xFF888888),
    border = Color(0xFF333333)
)
