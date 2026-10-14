package com.kherio.gitwatch.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kherio.gitwatch.data.Kind
import com.kherio.gitwatch.data.RepoRef
import com.kherio.gitwatch.data.TrackedItem
import com.kherio.gitwatch.domain.ItemUi
import com.kherio.gitwatch.domain.Status

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onImport: () -> Unit, onSettings: () -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val shared by vm.sharedText.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val uri = LocalUriHandler.current

    var onlyUpdates by rememberSaveable { mutableStateOf(false) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TrackedItem?>(null) }
    var notes by remember { mutableStateOf<TrackedItem?>(null) }
    var deleting by remember { mutableStateOf<TrackedItem?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    LaunchedEffect(shared) { if (shared != null) showAdd = true }

    val updates = items.count { it.status == Status.UPDATE }
    val shown = if (onlyUpdates) items.filter { it.status == Status.UPDATE } else items

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitWatch") },
                actions = {
                    IconButton(onClick = { vm.refreshAll() }) { Icon(Icons.Default.Refresh, "Comprobar ahora") }
                    IconButton(onClick = onImport) { Icon(Icons.Default.Search, "Detectar instaladas") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Ajustes") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Añadir") }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.refreshAll() },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !onlyUpdates, onClick = { onlyUpdates = false }, label = { Text("Todo (${items.size})") })
                        FilterChip(selected = onlyUpdates, onClick = { onlyUpdates = true }, label = { Text("Con actualización ($updates)") })
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            if (items.isEmpty()) "Nada vigilado todavía. Pulsa + para añadir un repositorio, o la lupa para detectar tus apps y módulos instalados."
                            else "No hay actualizaciones pendientes.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(shown, key = { it.item.id }) { ui ->
                    ItemCard(
                        ui = ui,
                        progress = progress[ui.item.id],
                        onInstall = { vm.install(ui) },
                        onIgnore = { vm.ignore(ui.item) },
                        onNotes = { notes = ui.item },
                        onEdit = { editing = ui.item },
                        onDelete = { deleting = ui.item },
                        onOpen = { uri.openUri(ui.item.latestUrl ?: "https://github.com/${ui.item.owner}/${ui.item.repo}") },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddDialog(
            initial = shared ?: "",
            onDismiss = { showAdd = false; vm.sharedText.value = null },
            onAdd = { kind, input, id -> vm.addManual(kind, input, id); showAdd = false },
        )
    }
    editing?.let { item ->
        EditDialog(item, onDismiss = { editing = null }, onSave = { vm.save(it); editing = null })
    }
    notes?.let { item ->
        AlertDialog(
            onDismissRequest = { notes = null },
            title = { Text("${item.displayName} ${item.latestTag.orEmpty()}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(item.latestNotes?.ifBlank { null } ?: "Esta release no incluye notas.")
                }
            },
            confirmButton = { TextButton(onClick = { notes = null }) { Text("Cerrar") } },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Dejar de vigilar") },
            text = { Text("${item.displayName} se quitará de la lista (la app o el módulo instalado no se toca).") },
            confirmButton = { TextButton(onClick = { vm.delete(item); deleting = null }) { Text("Quitar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ItemCard(
    ui: ItemUi,
    progress: Float?,
    onInstall: () -> Unit,
    onIgnore: () -> Unit,
    onNotes: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    val item = ui.item
    var menu by remember { mutableStateOf(false) }
    val isModule = item.kind == Kind.MODULE

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${if (isModule) "Módulo · " else ""}${item.owner}/${item.repo}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusLabel(ui.status)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Más") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Notas de la versión") }, onClick = { menu = false; onNotes() })
                        DropdownMenuItem(text = { Text("Abrir en GitHub") }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text("Editar reglas") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Dejar de vigilar") }, onClick = { menu = false; onDelete() })
                    }
                }
            }

            Text(
                "Instalada: ${ui.installed ?: "—"}   ·   Última: ${item.latestVersion ?: "—"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (item.lastError != null) {
                Text(item.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (item.lastChecked > 0) {
                Text(
                    "Comprobado " + DateUtils.getRelativeTimeSpanString(item.lastChecked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }

            val canInstall = (ui.status == Status.UPDATE || ui.status == Status.NOT_INSTALLED || ui.status == Status.IGNORED) &&
                item.latestAssetUrl != null
            if (canInstall && progress == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onInstall) {
                        Text(if (ui.status == Status.NOT_INSTALLED) "Instalar" else "Actualizar")
                    }
                    if (ui.status == Status.UPDATE) TextButton(onClick = onIgnore) { Text("Ignorar versión") }
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(status: Status) {
    val (text, color) = when (status) {
        Status.UPDATE -> "Actualización" to MaterialTheme.colorScheme.primary
        Status.NOT_INSTALLED -> "No instalado" to MaterialTheme.colorScheme.tertiary
        Status.ERROR -> "Error" to MaterialTheme.colorScheme.error
        Status.UNKNOWN -> "Sin comprobar" to MaterialTheme.colorScheme.onSurfaceVariant
        Status.IGNORED -> "Ignorada" to MaterialTheme.colorScheme.onSurfaceVariant
        Status.UPTODATE -> "Al día" to Color(0xFF2E7D32)
    }
    Text(text, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun AddDialog(initial: String, onDismiss: () -> Unit, onAdd: (kind: String, input: String, id: String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var kind by remember { mutableStateOf(Kind.APP) }
    var id by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir repositorio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("URL o owner/repo") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == Kind.APP, onClick = { kind = Kind.APP }, label = { Text("App (.apk)") })
                    FilterChip(selected = kind == Kind.MODULE, onClick = { kind = Kind.MODULE }, label = { Text("Módulo root (.zip)") })
                }
                OutlinedTextField(
                    id, { id = it },
                    label = { Text(if (kind == Kind.APP) "Paquete (opcional)" else "ID del módulo (opcional)") },
                    singleLine = true,
                )
                Text(
                    "Si no indicas el paquete/ID, se detecta al instalar por primera vez.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = RepoRef.parse(text) != null, onClick = { onAdd(kind, text, id.trim()) }) { Text("Añadir") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun EditDialog(item: TrackedItem, onDismiss: () -> Unit, onSave: (TrackedItem) -> Unit) {
    var pre by remember { mutableStateOf(item.includePrereleases) }
    var assetRegex by remember { mutableStateOf(item.assetRegex) }
    var versionRegex by remember { mutableStateOf(item.versionRegex) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Incluir pre-releases", Modifier.weight(1f))
                    Switch(pre, { pre = it })
                }
                OutlinedTextField(
                    assetRegex, { assetRegex = it }, singleLine = true,
                    label = { Text("Filtro del archivo (regex)") },
                    placeholder = { Text("ej. arm64.*release") },
                )
                OutlinedTextField(
                    versionRegex, { versionRegex = it }, singleLine = true,
                    label = { Text("Extraer versión del tag (regex)") },
                    placeholder = { Text("ej. build-(\\d+\\.\\d+)") },
                )
                Text("Deja vacío para el comportamiento automático.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(item.copy(includePrereleases = pre, assetRegex = assetRegex.trim(), versionRegex = versionRegex.trim()))
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
