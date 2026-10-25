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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
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
        title = { Text("Импорт конфигов") },
        text = {
            Column {
                Text("Вставьте ссылки (каждая с новой строки):")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    placeholder = { Text("vless://...\nvmess://...\ntrojan://...\nss://...") }
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
