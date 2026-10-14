package com.kherio.gitwatch

import android.content.Context
import androidx.room.Room
import com.kherio.gitwatch.data.AppDb
import com.kherio.gitwatch.data.Assets
import com.kherio.gitwatch.data.GhResult
import com.kherio.gitwatch.data.GitHub
import com.kherio.gitwatch.data.Kind
import com.kherio.gitwatch.data.Prefs
import com.kherio.gitwatch.data.RepoRef
import com.kherio.gitwatch.data.TrackedItem
import com.kherio.gitwatch.domain.Installer
import com.kherio.gitwatch.domain.Outcome
import com.kherio.gitwatch.domain.Root
import com.kherio.gitwatch.domain.Versions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class Candidate(val owner: String, val repo: String, val note: String, val stars: Int = 0)

@Serializable
data class ExportItem(
    val kind: String,
    val identifier: String,
    val displayName: String,
    val owner: String,
    val repo: String,
    val includePrereleases: Boolean = false,
    val assetRegex: String = "",
    val versionRegex: String = "",
)

@Serializable
data class ExportFile(val version: Int = 1, val items: List<ExportItem>)

class Repository(private val ctx: Context) {
    val prefs = Prefs(ctx)
    private val db = Room.databaseBuilder(ctx, AppDb::class.java, "gitwatch.db").build()
    val dao = db.dao()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    val github = GitHub(prefs, client)
    val installer = Installer(ctx, client, prefs)

    val items: Flow<List<TrackedItem>> = dao.observeAll()

    private val _modules = MutableStateFlow<Map<String, Root.Module>>(emptyMap())
    val modules: StateFlow<Map<String, Root.Module>> = _modules

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun refreshModules() {
        _modules.value = if (prefs.rootEnabled) Root.modules().associateBy { it.id } else emptyMap()
    }

    @Suppress("DEPRECATION")
    fun installedVersion(item: TrackedItem, mods: Map<String, Root.Module> = _modules.value): String? =
        if (item.kind == Kind.APP) {
            runCatching { ctx.packageManager.getPackageInfo(item.identifier, 0).versionName }.getOrNull()
        } else {
            mods[item.identifier]?.version?.takeIf { it.isNotBlank() }
        }

    // ---------- comprobación de versiones ----------

    suspend fun check(item: TrackedItem): TrackedItem {
        val now = System.currentTimeMillis()
        val updated = when (val r = github.releases(item.owner, item.repo, item.etag)) {
            is GhResult.Err -> item.copy(lastError = r.msg, lastChecked = now)
            is GhResult.Ok -> {
                val list = r.v.list
                if (list == null) {
                    item.copy(lastChecked = now, lastError = null) // 304: sin cambios
                } else {
                    val rel = list.firstOrNull { !it.draft && (item.includePrereleases || !it.prerelease) }
                    if (rel == null) {
                        item.copy(etag = r.v.etag, lastChecked = now, lastError = "Sin releases publicadas")
                    } else {
                        val asset = Assets.pick(rel.assets, item.kind, item.assetRegex)
                        val ext = if (item.kind == Kind.APP) ".apk" else ".zip"
                        item.copy(
                            etag = r.v.etag,
                            latestTag = rel.tag,
                            latestVersion = Versions.normalize(rel.tag, item.versionRegex),
                            latestUrl = rel.htmlUrl,
                            latestAssetName = asset?.name,
                            latestAssetUrl = asset?.url,
                            latestAssetDigest = asset?.digest,
                            latestAssetSize = asset?.size ?: 0,
                            latestNotes = rel.body?.take(4000),
                            lastChecked = now,
                            lastError = if (asset == null) "La release no incluye ningún $ext" else null,
                        )
                    }
                }
            }
        }
        dao.update(updated)
        return updated
    }

    // ---------- alta de elementos ----------

    suspend fun track(kind: String, identifier: String, name: String, owner: String, repo: String) {
        val existing = dao.find(kind, identifier)
        val item = existing?.copy(owner = owner, repo = repo, etag = null)
            ?: TrackedItem(
                kind = kind, identifier = identifier, displayName = name,
                owner = owner, repo = repo, includePrereleases = prefs.defaultPrerelease,
            )
        val rowId = dao.upsert(item)
        check(item.copy(id = if (existing != null) existing.id else rowId))
    }

    /** Cambios hechos desde el diálogo de edición: se invalida el ETag para recalcular con las nuevas reglas. */
    suspend fun edit(item: TrackedItem) {
        val edited = item.copy(etag = null)
        dao.update(edited)
        check(edited)
    }

    // ---------- autodetección del repositorio de origen ----------

    suspend fun candidates(kind: String, id: String, label: String, hint: String?): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        fun add(owner: String, repo: String, note: String, stars: Int = 0) {
            out.putIfAbsent("$owner/$repo".lowercase(), Candidate(owner, repo, note, stars))
        }

        hint?.let { RepoRef.parse(it) }?.let { (o, r) -> add(o, r, "Detectado en updateJson") }
        Regex("""^(?:io|com)\.github\.([a-z0-9_-]+)\.([a-z0-9_-]+)""").find(id)?.let {
            add(it.groupValues[1], it.groupValues[2], "Deducido del nombre del paquete")
        }

        val searched = mutableListOf<Candidate>()
        fun collect(r: GhResult<List<com.kherio.gitwatch.data.GhRepo>>, note: String) {
            if (r is GhResult.Ok) r.v.forEach { g ->
                RepoRef.parse(g.fullName)?.let { (o, rp) -> searched += Candidate(o, rp, g.description?.take(80) ?: note, g.stars) }
            }
        }
        if (prefs.token.isNotBlank() && kind == Kind.APP) {
            collect(github.searchCode("\"$id\" filename:build.gradle OR filename:build.gradle.kts OR filename:AndroidManifest.xml"), "Aparece en el código")
        }
        collect(github.searchRepos("\"$id\" in:readme,name,description"), "Menciona el paquete")
        collect(github.searchRepos("$label in:name"), "Coincide por nombre")

        searched.sortedByDescending { it.stars }.forEach { add(it.owner, it.repo, it.note, it.stars) }
        return out.values.take(8)
    }

    // ---------- instalación ----------

    suspend fun install(item: TrackedItem, onProgress: (Float) -> Unit = {}): Outcome {
        val file = installer.download(item, onProgress)
        try {
            val realId = installer.inspect(file, item.kind)
                ?: return Outcome.Failed(
                    if (item.kind == Kind.APP) "El archivo descargado no es un APK válido"
                    else "El ZIP no es un módulo válido (falta module.prop)",
                )
            var current = item
            if (item.isPlaceholderId) {
                if (dao.find(item.kind, realId) != null) return Outcome.Failed("Ya estás vigilando $realId")
                current = item.copy(identifier = realId)
                dao.update(current)
            } else if (realId != item.identifier) {
                return Outcome.Failed("El archivo es de $realId, no de ${item.identifier}. No se instala.")
            }
            return installer.install(current, file)
        } finally {
            file.delete()
        }
    }

    // ---------- copia de seguridad / migración ----------

    suspend fun exportJson(): String = json.encodeToString(
        ExportFile.serializer(),
        ExportFile(items = dao.all().map {
            ExportItem(it.kind, it.identifier, it.displayName, it.owner, it.repo, it.includePrereleases, it.assetRegex, it.versionRegex)
        }),
    )

    /** Acepta el formato propio y, en la medida de lo posible, la exportación de Obtainium ({"apps":[{id,url,name}]}). */
    suspend fun importJson(text: String): Int {
        var count = 0
        val root = json.parseToJsonElement(text).jsonObject
        val ours = root["items"] as? JsonArray
        if (ours != null) {
            json.decodeFromString(ExportFile.serializer(), text).items.forEach { e ->
                if (dao.find(e.kind, e.identifier) == null) {
                    dao.upsert(TrackedItem(
                        kind = e.kind, identifier = e.identifier, displayName = e.displayName,
                        owner = e.owner, repo = e.repo, includePrereleases = e.includePrereleases,
                        assetRegex = e.assetRegex, versionRegex = e.versionRegex,
                    ))
                    count++
                }
            }
        } else {
            (root["apps"] as? JsonArray)?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val url = o["url"]?.jsonPrimitive?.content ?: return@forEach
                val id = o["id"]?.jsonPrimitive?.content ?: return@forEach
                val (owner, repo) = RepoRef.parse(url) ?: return@forEach
                if (dao.find(Kind.APP, id) == null) {
                    dao.upsert(TrackedItem(
                        kind = Kind.APP, identifier = id,
                        displayName = o["name"]?.jsonPrimitive?.content ?: repo,
                        owner = owner, repo = repo, includePrereleases = prefs.defaultPrerelease,
                    ))
                    count++
                }
            }
        }
        return count
    }
}
