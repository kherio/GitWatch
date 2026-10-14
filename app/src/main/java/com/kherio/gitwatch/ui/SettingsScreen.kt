package com.kherio.gitwatch.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val prefs = vm.repo.prefs
    val rootOn by vm.rootEnabled.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf(prefs.token) }
    var interval by remember { mutableIntStateOf(prefs.intervalHours) }
    var notify by remember { mutableStateOf(prefs.notify) }
    var auto by remember { mutableStateOf(prefs.autoInstall) }
    var pre by remember { mutableStateOf(prefs.defaultPrerelease) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("Comprobación")
            Text("Frecuencia en segundo plano", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 6, 12, 24).forEach { h ->
                    FilterChip(
                        selected = interval == h,
                        onClick = { interval = h; vm.setInterval(h) },
                        label = { Text("$h h") },
                    )
                }
            }
            SwitchRow("Notificar actualizaciones", notify) { notify = it; prefs.notify = it }
            SwitchRow("Incluir pre-releases en lo nuevo que añada", pre) { pre = it; prefs.defaultPrerelease = it }

            HorizontalDivider()
            Section("GitHub")
            OutlinedTextField(
                token, { token = it; prefs.token = it },
                label = { Text("Token (opcional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Sin token la API permite 60 peticiones/hora; con token, 5000. Basta un token sin permisos (solo repos públicos). " +
                    "Además activa la búsqueda por código al detectar repositorios.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Section("Root")
            SwitchRow("Usar root (módulos e instalación silenciosa)", rootOn) { vm.enableRoot(it); if (!it) auto = false }
            SwitchRow("Instalar solas las actualizaciones de apps", auto && rootOn, enabled = rootOn) {
                auto = it; prefs.autoInstall = it
            }
            Text(
                "Los módulos nunca se instalan solos: solo se avisa. Android sigue verificando la firma de cada APK.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Section("Copia de seguridad")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exporter.launch("gitwatch-backup.json") }) { Text("Exportar") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Text("Importar") }
            }
            Text("También puedes importar una exportación de Obtainium.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange, enabled = enabled)
    }
}
