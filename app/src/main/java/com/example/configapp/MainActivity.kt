package com.example.configapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.configapp.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("velox_prefs", Context.MODE_PRIVATE)
        val savedThemeName = prefs.getString("app_theme", "Индиго") ?: "Индиго"
        val initialTheme = getThemeByName(savedThemeName)

        setContent {
            var currentTheme by remember { mutableStateOf(initialTheme) }
            MaterialTheme(colorScheme = darkColorScheme(background = currentTheme.bg)) {
                App(
                    currentTheme = currentTheme, 
                    onThemeChange = { newTheme -> 
                        currentTheme = newTheme
                        prefs.edit().putString("app_theme", newTheme.name).apply() 
                    }
                )
            }
        }
    }
}

fun getThemeByName(name: String): AppTheme {
    return when (name) {
        "Зелёная" -> GreenTheme
        "Тёмно-синяя" -> DarkBlueTheme
        "Белая" -> WhiteTheme
        "Чёрная" -> BlackTheme
        else -> HiddifyTheme
    }
}

@Composable
fun App(
    currentTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
    vm: ConfigViewModel = viewModel()
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    
    var isVpnConnected by remember { mutableStateOf(false) }
    var isVpnConnecting by remember { mutableStateOf(false) }
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.getStringExtra(VeloxVpnService.EXTRA_STATE)) {
                    VeloxVpnService.STATE_CONNECTED -> {
                        isVpnConnected = true
                        isVpnConnecting = false
                    }
                    VeloxVpnService.STATE_DISCONNECTED -> {
                        isVpnConnected = false
                        isVpnConnecting = false
                    }
                    VeloxVpnService.STATE_ERROR -> {
                        isVpnConnected = false
                        isVpnConnecting = false
                    }
                }
            }
        }
        val filter = IntentFilter(VeloxVpnService.ACTION_VPN_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    Scaffold(
        containerColor = currentTheme.bg,
        bottomBar = {
            FloatingBottomNav(selectedTab, currentTheme) { selectedTab = it }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> ConfigScreen(
                    viewModel = vm, 
                    theme = currentTheme, 
                    isConnected = isVpnConnected,
                    isConnecting = isVpnConnecting,
                    onToggleConnection = { isConnecting, isConnectedState -> 
                        isVpnConnecting = isConnecting
                        isVpnConnected = isConnectedState
                    }
                )
                1 -> ServersScreen(
                    viewModel = vm, 
                    theme = currentTheme, 
                    onAddClick = { text -> 
                        importText = text
                        showImport = true 
                    }
                )
                2 -> SettingsScreen(theme = currentTheme, onThemeChange = onThemeChange, viewModel = vm)
            }
        }
    }

    if (showImport) {
        ImportDialog(
            theme = currentTheme,
            initialText = importText,
            onDismiss = { showImport = false },
            onImport = { text ->
                vm.importFromText(text)
                showImport = false
            }
        )
    }
}

@Composable
fun FloatingBottomNav(selectedTab: Int, theme: AppTheme, onTabSelected: (Int) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .background(theme.cardBg, RoundedCornerShape(24.dp))
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem("Главная", Icons.Default.Home, 0, selectedTab, theme, onTabSelected)
            NavItem("Сервера", Icons.Default.Dns, 1, selectedTab, theme, onTabSelected)
            NavItem("Настройки", Icons.Default.Settings, 2, selectedTab, theme, onTabSelected)
        }
    }
}

@Composable
fun RowScope.NavItem(title: String, icon: ImageVector, index: Int, selectedTab: Int, theme: AppTheme, onTabSelected: (Int) -> Unit) {
    val isSelected = selectedTab == index
    val color = if (isSelected) theme.accent else theme.textGray
    val bgColor = if (isSelected) theme.selectedCardBg else Color.Transparent
    
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { onTabSelected(index) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = title, tint = color)
        Spacer(modifier = Modifier.height(4.dp))
        Text(title, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ImportDialog(theme: AppTheme, initialText: String, onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.cardBg,
        title = { Text("Импорт", color = theme.text) },
        text = {
            Column {
                Text("Вставьте ссылку на конфиг или подписку:", color = theme.textGray)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    placeholder = { Text("vless://...\nили\nhttps://...", color = theme.textGray) }
                )
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text) }) { Text("Добавить", color = theme.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = theme.textGray) } }
    )
}
