package com.kherio.gitwatch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kherio.gitwatch.Candidate
import com.kherio.gitwatch.data.Kind
import com.kherio.gitwatch.data.RepoRef
import com.kherio.gitwatch.domain.InstalledApp
import com.kherio.gitwatch.domain.Root

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(vm: MainViewModel, onBack: () -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    val rootOn by vm.rootEnabled.collectAsStateWithLifecycle()
    val tracked = remember(items) { items.map { it.item.kind to it.item.identifier }.toSet() }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var modules by remember { mutableStateOf<List<Root.Module>?>(null) }
    var pickApp by remember { mutableStateOf<InstalledApp?>(null) }
    var pickModule by remember { mutableStateOf<Root.Module?>(null) }

    LaunchedEffect(Unit) { apps = vm.loadApps() }
    LaunchedEffect(rootOn) { if (rootOn) modules = vm.loadModules() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detectar instaladas") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Apps") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Módulos root") })
            }

            if (tab == 0) {
                OutlinedTextField(
                    query, { query = it }, singleLine = true, label = { Text("Buscar") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(showSystem, { showSystem = it })
                    Text("  Mostrar apps del sistema")
                }
                val list = apps?.filter {
                    (showSystem || !it.system) &&
                        (query.isBlank() || it.name.contains(query, true) || it.pkg.contains(query, true))
                }
                if (list == null) {
                    Loading()
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(list, key = { it.pkg }) { a ->
                            val done = (Kind.APP to a.pkg) in tracked
                            ListItem(
                                headlineContent = { Text(a.name) },
                                supportingContent = { Text("${a.pkg} · ${a.version}") },
                                trailingContent = { if (done) Icon(Icons.Default.Check, "Ya vigilada") },
                                modifier = Modifier.clickable(enabled = !done) { pickApp = a },
                            )
                        }
                    }
                }
            } else {
                if (!rootOn) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Los módulos se leen desde /data/adb/modules, lo que requiere root (KernelSU, ReSukiSU, Magisk, APatch).",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { vm.enableRoot(true) }) { Text("Activar root") }
                    }
                } else {
                    val list = modules
                    if (list == null) {
                        Loading()
                    } else if (list.isEmpty()) {
                        Text("No se encontraron módulos.", Modifier.padding(24.dp))
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(list, key = { it.id }) { m ->
                                val done = (Kind.MODULE to m.id) in tracked
                                val hint = RepoRef.parse(m.updateJson)
                                ListItem(
                                    headlineContent = { Text(m.name) },
                                    supportingContent = {
                                        Text(
                                            "${m.id} · ${m.version}" +
                                                (if (m.disabled) " · desactivado" else "") +
                                                (if (hint != null) "\nRepo: ${hint.first}/${hint.second}" else ""),
                                        )
                                    },
                                    trailingContent = { if (done) Icon(Icons.Default.Check, "Ya vigilado") },
                                    modifier = Modifier.clickable(enabled = !done) { pickModule = m },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pickApp?.let { a ->
        CandidateDialog(
            title = a.name,
            load = { vm.candidates(Kind.APP, a.pkg, a.name, null) },
            onPick = { c -> vm.track(Kind.APP, a.pkg, a.name, c.owner, c.repo); pickApp = null },
            onDismiss = { pickApp = null },
        )
    }
    pickModule?.let { m ->
        CandidateDialog(
            title = m.name,
            load = { vm.candidates(Kind.MODULE, m.id, m.name, m.updateJson.ifBlank { null }) },
            onPick = { c -> vm.track(Kind.MODULE, m.id, m.name, c.owner, c.repo); pickModule = null },
            onDismiss = { pickModule = null },
        )
    }
}

@Composable
private fun Loading() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CandidateDialog(
    title: String,
    load: suspend () -> List<Candidate>,
    onPick: (Candidate) -> Unit,
    onDismiss: () -> Unit,
) {
    var list by remember { mutableStateOf<List<Candidate>?>(null) }
    var manual by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { list = runCatching { load() }.getOrDefault(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repositorio de $title") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val l = list
                when {
                    l == null -> Text("Buscando en GitHub…")
                    l.isEmpty() -> Text("Sin resultados automáticos. Indica el repositorio a mano.")
                    else -> {
                        Text("La detección es orientativa: comprueba que sea el repo correcto.", style = MaterialTheme.typography.bodySmall)
                        l.forEach { c ->
                            ListItem(
                                headlineContent = { Text("${c.owner}/${c.repo}") },
                                supportingContent = { Text(c.note + if (c.stars > 0) " · ★${c.stars}" else "") },
                                modifier = Modifier.clickable { onPick(c) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    manual, { manual = it }, singleLine = true,
                    label = { Text("owner/repo o URL") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = RepoRef.parse(manual) != null,
                onClick = { RepoRef.parse(manual)?.let { (o, r) -> onPick(Candidate(o, r, "Manual")) } },
            ) { Text("Usar este") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
