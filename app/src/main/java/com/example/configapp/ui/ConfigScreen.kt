package com.example.configapp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.configapp.data.ProxyConfig

// Неоновая палитра
val DarkBg = Color(0xFF0A0A0A)
val CardBg = Color(0xFF151515)
val NeonGreen = Color(0xFF00FF41)
val NeonRed = Color(0xFFFF003C)
val TextGray = Color(0xFF888888)

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
                containerColor = Color(0xFF1E1E1E),
                contentColor = NeonGreen
            ) {
                Icon(Icons.Default.Add, contentDescription = "Добавить конфиг")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Статус
            val statusColor = if (isConnected) NeonGreen else NeonRed
            Text(
                text = if (isConnected) "ПОДКЛЮЧЕНО" else "ОТКЛЮЧЕНО",
                color = statusColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Большая неоновая кнопка подключения с анимациями
            NeonConnectButton(isConnected = isConnected, onClick = { isConnected = !isConnected })

            Spacer(modifier = Modifier.height(48.dp))

            // Заголовок списка
            Text(
                text = "Мои серверы",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Список конфигов
            if (configs.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "Нет серверов.\nНажмите +, чтобы добавить.",
                        color = TextGray,
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
fun NeonConnectButton(isConnected: Boolean, onClick: () -> Unit) {
    // Анимация нажатия (уменьшение)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "press_scale"
    )

    // Анимация цвета (плавный переход)
    val animatedColor by animateColorAsState(
        targetValue = if (isConnected) NeonGreen else NeonRed,
        animationSpec = tween(500),
        label = "color_transition"
    )

    // Бесконечная пульсация (только когда подключено)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnected) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    
    // Прозрачность пульсации
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = if (isConnected) 0.1f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.scale(scale)) {
        // Внешний пульсирующий ореол (только если включено)
        if (isConnected) {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(animatedColor.copy(alpha = pulseAlpha))
            )
        }

        // Сама кнопка с неоновой тенью
        Box(
            modifier = Modifier
                .size(160.dp)
                .shadow(
                    elevation = if (isConnected) 30.dp else 15.dp,
                    shape = CircleShape,
                    ambientColor = animatedColor,
                    spotColor = animatedColor
                )
                .clip(CircleShape)
                .background(animatedColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null // Отключаем стандартный ripple, так как есть своя анимация
                ) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = "Connect",
                tint = Color.Black,
                modifier = Modifier.size(72.dp)
            )
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
                    .background(Color(0xFF222222)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = cfg.protocol.name.take(1),
                    color = NeonGreen, // Акцентный цвет
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
                    color = TextGray,
                    fontSize = 13.sp
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Удалить",
                    tint = TextGray
                )
            }
        }
    }
}
