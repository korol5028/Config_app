package com.example.configapp.ui

import androidx.compose.ui.graphics.Color

data class AppTheme(
    val name: String,
    val bg: Color,
    val cardBg: Color,
    val selectedCardBg: Color,
    val accent: Color,
    val accentText: Color,
    val text: Color,
    val textGray: Color,
    val border: Color
)

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

val HiddifyTheme = AppTheme(
    name = "Индиго",
    bg = Color(0xFF16123F),
    cardBg = Color(0xFF251F5A),
    selectedCardBg = Color(0xFF352D7A),
    accent = Color(0xFF8B5CF6),
    accentText = Color.White,
    text = Color.White,
    textGray = Color(0xFFB0A8D6),
    border = Color(0xFF3A327A)
)
