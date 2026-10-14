package com.kherio.gitwatch.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kherio.gitwatch.Candidate
import com.kherio.gitwatch.GitWatchApp
import com.kherio.gitwatch.data.Kind
import com.kherio.gitwatch.data.RepoRef
import com.kherio.gitwatch.data.TrackedItem
import com.kherio.gitwatch.domain.InstalledApp
import com.kherio.gitwatch.domain.InstalledApps
import com.kherio.gitwatch.domain.ItemUi
import com.kherio.gitwatch.domain.Outcome
import com.kherio.gitwatch.domain.Root
import com.kherio.gitwatch.work.UpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = (app as GitWatchApp).repo

    private val tick = MutableStateFlow(0)
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val progress: StateFlow<Map<Long, Float>> = _progress.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    /** Texto recibido por "Compartir" desde el navegador. */
    val sharedText = MutableStateFlow<String?>(null)

    val rootEnabled = MutableStateFlow(repo.prefs.rootEnabled)

    val items: StateFlow<List<ItemUi>> = combine(repo.items, repo.modules, tick) { list, mods, _ ->
        list.map { ItemUi(it, repo.installedVersion(it, mods)) }
            .sortedWith(compareBy<ItemUi> { it.status.ordinal }.thenBy { it.item.displayName.lowercase() })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { repo.refreshModules() }
    }

    private fun say(msg: String) {
        _messages.tryEmit(msg)
    }

    /** Se llama desde onResume: relee las versiones instaladas (por si el usuario instaló algo). */
    fun onResume() {
        tick.update { it + 1 }
        viewModelScope.launch { repo.refreshModules() }
    }

    fun refreshAll() {
        viewModelScope.launch {
            if (_refreshing.value) return@launch
            _refreshing.value = true
            try {
                repo.refreshModules()
                val list = repo.dao.all()
                coroutineScope {
                    list.chunked(4).forEach { chunk -> chunk.map { async { repo.check(it) } }.awaitAll() }
                }
                val failed = repo.dao.all().count { it.lastError != null && it.latestVersion == null }
                if (failed > 0) say("$failed elementos con error al comprobar")
            } finally {
                _refreshing.value = false
            }
        }
    }

    // ---------- altas ----------

    fun addManual(kind: String, input: String, identifier: String) {
        viewModelScope.launch {
            val (owner, repoName) = RepoRef.parse(input) ?: return@launch say("URL de GitHub no válida")
            val id = identifier.ifBlank { "$owner/$repoName" }
            repo.track(kind, id, repoName, owner, repoName)
            sharedText.value = null
            say("Añadido $owner/$repoName")
        }
    }

    fun track(kind: String, id: String, name: String, owner: String, repoName: String) {
        viewModelScope.launch {
            repo.track(kind, id, name, owner, repoName)
            say("Vigilando $name ($owner/$repoName)")
        }
    }

    suspend fun candidates(kind: String, id: String, label: String, hint: String?): List<Candidate> =
        repo.candidates(kind, id, label, hint)

    suspend fun loadApps(): List<InstalledApp> =
        withContext(Dispatchers.IO) { InstalledApps.list(getApplication()) }

    suspend fun loadModules(): List<Root.Module> = withContext(Dispatchers.IO) { Root.modules() }

    // ---------- acciones sobre un elemento ----------

    fun install(ui: ItemUi) {
        val item = ui.item
        viewModelScope.launch {
            if (item.latestAssetUrl == null) return@launch say("La release no incluye archivo instalable")
            setProgress(item.id, 0f)
            try {
                val out = repo.install(item) { setProgress(item.id, it) }
                say(out.message)
                if (out is Outcome.Done) repo.dao.update(item.copy(ignoredTag = null))
            } catch (e: Exception) {
                say("Error: ${e.message}")
            } finally {
                setProgress(item.id, null)
                repo.refreshModules()
                tick.update { it + 1 }
            }
        }
    }

    private fun setProgress(id: Long, value: Float?) {
        _progress.update { m -> if (value == null) m - id else m + (id to value) }
    }

    fun ignore(item: TrackedItem) {
        viewModelScope.launch {
            repo.dao.update(item.copy(ignoredTag = item.latestTag))
            say("Versión ${item.latestVersion} ignorada")
        }
    }

    fun save(item: TrackedItem) {
        viewModelScope.launch { repo.edit(item) }
    }

    fun delete(item: TrackedItem) {
        viewModelScope.launch { repo.dao.delete(item) }
    }

    // ---------- ajustes ----------

    fun enableRoot(enable: Boolean) {
        viewModelScope.launch {
            if (!enable) {
                repo.prefs.rootEnabled = false
                repo.prefs.autoInstall = false
                rootEnabled.value = false
                repo.refreshModules()
                return@launch
            }
            if (Root.available()) {
                repo.prefs.rootEnabled = true
                rootEnabled.value = true
                repo.refreshModules()
                say("Root activado")
            } else {
                say("No se pudo obtener root. Concede el permiso a GitWatch en tu gestor.")
            }
        }
    }

    fun setInterval(hours: Int) {
        repo.prefs.intervalHours = hours
        UpdateWorker.schedule(getApplication(), hours, replace = true)
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)!!
                    .use { it.write(repo.exportJson().toByteArray()) }
            }.isSuccess
            say(if (ok) "Copia exportada" else "No se pudo exportar")
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)!!
                    .use { it.readBytes().decodeToString() }
            }.getOrNull() ?: return@launch say("No se pudo leer el archivo")
            runCatching { repo.importJson(text) }
                .onSuccess { say("Importados $it elementos nuevos. Pulsa comprobar para actualizarlos.") }
                .onFailure { say("Archivo no reconocido") }
        }
    }
}
