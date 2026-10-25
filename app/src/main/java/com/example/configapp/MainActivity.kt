package com.example.configapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.configapp.ui.ConfigScreen
import com.example.configapp.ui.ConfigViewModel
import com.example.configapp.ui.DarkBg

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(background = DarkBg)
            ) {
                App()
            }
        }
    }
}

@Composable
fun App(vm: ConfigViewModel = viewModel()) {
    var showImport by remember { mutableStateOf(false) }

    ConfigScreen(viewModel = vm, onAddClick = { showImport = true })

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
private fun ImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Импорт конфигов", color = Color.White) },
        text = {
            Column {
                Text("Вставьте ссылки (каждая с новой строки):", color = Color.LightGray)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    placeholder = { Text("vless://...\nvmess://...\ntrojan://...\nss://...", color = Color.Gray) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(text) }) { Text("Импорт") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
