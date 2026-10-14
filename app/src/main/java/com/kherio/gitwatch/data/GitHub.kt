package com.kherio.gitwatch.data

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

@Serializable
data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
    val digest: String? = null,
)

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
data class GhRepo(
    @SerialName("full_name") val fullName: String,
    val description: String? = null,
    @SerialName("stargazers_count") val stars: Int = 0,
)

@Serializable private data class RepoSearch(val items: List<GhRepo> = emptyList())
@Serializable private data class CodeItem(val repository: GhRepo)
@Serializable private data class CodeSearch(val items: List<CodeItem> = emptyList())

sealed interface GhResult<out T> {
    data class Ok<T>(val v: T) : GhResult<T>
    data class Err(val msg: String, val rateLimited: Boolean = false) : GhResult<Nothing>
}

/** list == null significa "304 Not Modified": nada ha cambiado desde la última comprobación. */
data class Releases(val list: List<GhRelease>?, val etag: String?)

class GitHub(private val prefs: Prefs, private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun <T> call(
        url: String,
        etag: String? = null,
        parse: (body: String?, etag: String?) -> T,
    ): GhResult<T> = withContext<GhResult<T>>(Dispatchers.IO) {
        val rb = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        // El token solo viaja a api.github.com (todas las URLs de esta clase lo son).
        prefs.token.takeIf { it.isNotBlank() }?.let { rb.header("Authorization", "Bearer $it") }
        etag?.let { rb.header("If-None-Match", it) }
        try {
            client.newCall(rb.build()).execute().use { r ->
                when {
                    r.code == 304 -> GhResult.Ok(parse(null, etag))
                    r.isSuccessful -> GhResult.Ok(parse(r.body?.string().orEmpty(), r.header("ETag")))
                    r.code == 404 -> GhResult.Err("No encontrado (¿repo privado o mal escrito?)")
                    r.code == 401 -> GhResult.Err("Token de GitHub no válido")
                    r.code == 429 || (r.code == 403 && r.header("x-ratelimit-remaining") == "0") ->
                        GhResult.Err("Límite de la API de GitHub alcanzado. Añade un token en Ajustes.", rateLimited = true)
                    else -> GhResult.Err("Error HTTP ${r.code}")
                }
            }
        } catch (e: IOException) {
            GhResult.Err("Sin conexión: ${e.message}")
        } catch (e: SerializationException) {
            GhResult.Err("Respuesta inesperada de GitHub")
        }
    }

    suspend fun releases(owner: String, repo: String, etag: String?): GhResult<Releases> =
        call("https://api.github.com/repos/$owner/$repo/releases?per_page=20", etag) { body, newEtag ->
            Releases(body?.let { json.decodeFromString<List<GhRelease>>(it) }, newEtag)
        }

    suspend fun searchRepos(query: String): GhResult<List<GhRepo>> =
        call("https://api.github.com/search/repositories?per_page=6&q=${enc(query)}") { body, _ ->
            json.decodeFromString<RepoSearch>(body.orEmpty()).items
        }

    /** La búsqueda de código exige token. */
    suspend fun searchCode(query: String): GhResult<List<GhRepo>> =
        call("https://api.github.com/search/code?per_page=8&q=${enc(query)}") { body, _ ->
            json.decodeFromString<CodeSearch>(body.orEmpty()).items.map { it.repository }
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

object RepoRef {
    private val url = Regex("""github(?:usercontent)?\.com[/:]([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)""")
    private val plain = Regex("""^\s*([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)\s*$""")

    fun parse(text: String): Pair<String, String>? {
        val m = url.find(text) ?: plain.find(text) ?: return null
        val owner = m.groupValues[1]
        val repo = m.groupValues[2].removeSuffix(".git")
        return if (owner.isNotEmpty() && repo.isNotEmpty()) owner to repo else null
    }
}

object Assets {
    private val archTokens = mapOf(
        "arm64-v8a" to listOf("arm64", "aarch64", "v8a"),
        "armeabi-v7a" to listOf("armeabi", "armv7", "v7a", "arm32"),
        "x86_64" to listOf("x86_64", "x64", "amd64"),
        "x86" to listOf("x86", "i386", "i686"),
    )

    /** Elige el .apk (o .zip si es módulo) más adecuado: respeta el filtro regex y prefiere la ABI del dispositivo. */
    fun pick(assets: List<GhAsset>, kind: String, regex: String): GhAsset? {
        val ext = if (kind == Kind.APP) ".apk" else ".zip"
        var c = assets.filter { it.name.endsWith(ext, ignoreCase = true) }
        if (regex.isNotBlank()) {
            val re = runCatching { Regex(regex) }.getOrNull()
            if (re != null) c = c.filter { re.containsMatchIn(it.name) }
        }
        if (c.size <= 1 || kind != Kind.APP) return c.firstOrNull()
        val abis = Build.SUPPORTED_ABIS
        return c.maxByOrNull { score(it.name.lowercase(), abis) }
    }

    private fun score(name: String, abis: Array<String>): Int {
        abis.forEachIndexed { i, abi ->
            if (archTokens[abi].orEmpty().any { name.contains(it) }) return 100 - i * 10
        }
        val mentionsOtherArch = archTokens.values.flatten().any { name.contains(it) }
        return when {
            mentionsOtherArch -> -100
            name.contains("universal") -> 20
            else -> 10
        }
    }
}
