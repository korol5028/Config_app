package com.example.configapp

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.configapp.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = BgDarkGreen)) {
                App()
            }
        }
    }
}

@Composable
fun App(vm: ConfigViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showImport by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BgDarkGreen,
        bottomBar = {
            FloatingBottomNav(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
        },
        floatingActionButton = {
            if (selectedTab == 1) {
                FloatingActionButton(
                    onClick = { showImport = true },
                    containerColor = AccentGreen,
                    contentColor = Color.Black
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> ConfigScreen(viewModel = vm, isMainScreen = true)
                1 -> ConfigScreen(viewModel = vm, isMainScreen = false)
                2 -> SettingsScreen()
            }
        }
    }

    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onImport = { text ->
                vm.importFromText(text)
                showImport = false
            }
        )
    }
}

@Composable
fun FloatingBottomNav(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 16.dp)
            .background(Color(0xFF1E2922), RoundedCornerShape(30.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem("Подключение", Icons.Default.PowerSettingsNew, 0, selectedTab, onTabSelected)
            NavItem("Серверы", Icons.Default.Dns, 1, selectedTab, onTabSelected)
            NavItem("Настройки", Icons.Default.Settings, 2, selectedTab, onTabSelected)
        }
    }
}

@Composable
fun RowScope.NavItem(title: String, icon: ImageVector, index: Int, selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val isSelected = selectedTab == index
    val color = if (isSelected) AccentGreen else TextGray
    
    Column(
        modifier = Modifier
            .clickable { onTabSelected(index) }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = title, tint = color)
        Spacer(modifier = Modifier.height(4.dp))
        Text(title, color = color, fontSize = 10.sp)
    }
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDarkGreen,
        title = { Text("Импорт", color = TextWhite) },
        text = {
            Column {
                Text("Вставьте ссылку на конфиг или подписку:", color = TextGray)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    placeholder = { Text("vless://...\nили\nhttps://...", color = TextGray) }
                )
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text) }) { Text("Добавить", color = AccentGreen) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextGray) } }
    )
}
