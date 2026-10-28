package com.example.configapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.configapp.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var currentTheme by remember { mutableStateOf(GreenTheme) }

            MaterialTheme(colorScheme = darkColorScheme(background = currentTheme.bg)) {
                App(currentTheme = currentTheme, onThemeChange = { currentTheme = it })
            }
        }
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
    
    val snackbarHostState = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        containerColor = currentTheme.bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            FloatingBottomNav(selectedTab, currentTheme) { selectedTab = it }
        },
        floatingActionButton = {
            if (selectedTab == 1 || selectedTab == 0) {
                FloatingActionButton(
                    onClick = { showImport = true },
                    containerColor = currentTheme.accent,
                    contentColor = currentTheme.accentText
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> ConfigScreen(viewModel = vm, theme = currentTheme)
                1 -> ServersListScreen(vm = vm, theme = currentTheme) // НОВЫЙ ЭКРАН
                2 -> SettingsScreen(theme = currentTheme, onThemeChange = onThemeChange)
            }
        }
    }

    if (showImport) {
        ImportDialog(
            theme = currentTheme,
            onDismiss = { showImport = false },
            onImport = { text ->
                vm.importFromText(text)
                showImport = false
            }
        )
    }
}

// НОВЫЙ ЭКРАН СПИСКА СЕРВЕРОВ
@Composable
fun ServersListScreen(vm: ConfigViewModel, theme: AppTheme) {
    val configs by vm.configs.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.bg)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Мои сервера", color = theme.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier.clip(CircleShape).background(theme.selectedCardBg).padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("${configs.size}", color = theme.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        if (configs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Список пуст.\nДобавьте подписку на главной.", color = theme.textGray, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(configs, key = { it.id }) { cfg ->
                    // Используем ту же карточку, что и на главном экране
                    ServerCard(cfg, isSelected = false, theme = theme)
                }
            }
        }
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
private fun ImportDialog(theme: AppTheme, onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.cardBg,
        title = { Text("Импорт", color = theme.text) },
        text = {
            Column {
                Text("Вставьте конфиг (vless://...) или ссылку на подписку (https://...):", color = theme.textGray)
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
