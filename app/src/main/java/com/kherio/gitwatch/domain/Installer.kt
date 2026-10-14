package com.kherio.gitwatch.domain

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.kherio.gitwatch.data.Kind
import com.kherio.gitwatch.data.Prefs
import com.kherio.gitwatch.data.TrackedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

sealed interface Outcome {
    val message: String

    data class Done(override val message: String) : Outcome
    data class Pending(override val message: String) : Outcome
    data class Failed(override val message: String) : Outcome
}

class Installer(
    private val ctx: Context,
    private val client: OkHttpClient,
    private val prefs: Prefs,
) {
    /** Descarga el asset elegido y, si GitHub publica su SHA-256, lo verifica. */
    suspend fun download(item: TrackedItem, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val url = requireNotNull(item.latestAssetUrl) { "La release no tiene archivo instalable" }
        require(url.startsWith("https://github.com/")) { "URL de descarga no válida" }
        val dir = File(ctx.cacheDir, "dl").apply { mkdirs() }
        val file = File(dir, (item.latestAssetName ?: "asset").replace(Regex("[^A-Za-z0-9._-]"), "_"))

        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "Descarga fallida (HTTP ${resp.code})" }
            val body = checkNotNull(resp.body) { "Respuesta vacía" }
            val total = body.contentLength()
            val md = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            val expected = item.latestAssetDigest
            if (expected != null && expected.startsWith("sha256:") && !expected.substring(7).equals(hex, true)) {
                file.delete()
                error("El SHA-256 descargado no coincide con el publicado por GitHub")
            }
        }
        file
    }

    /** Devuelve el paquete (APK) o el id (módulo) que realmente contiene el archivo, o null si no es válido. */
    @Suppress("DEPRECATION")
    fun inspect(file: File, kind: String): String? = runCatching {
        if (kind == Kind.APP) {
            ctx.packageManager.getPackageArchiveInfo(file.path, 0)?.packageName
        } else {
            ZipFile(file).use { z ->
                val entry = z.getEntry("module.prop") ?: return@use null
                Root.parseProp(z.getInputStream(entry).bufferedReader().readLines())["id"]
            }
        }
    }.getOrNull()

    suspend fun install(item: TrackedItem, file: File): Outcome {
        val useRoot = prefs.rootEnabled && Root.available()
        return if (item.kind == Kind.APP) {
            if (useRoot) {
                val (ok, text) = Root.installApk(file.path)
                if (ok) Outcome.Done("${item.displayName} actualizada") else Outcome.Failed(text.ifBlank { "Falló la instalación" })
            } else {
                withContext(Dispatchers.IO) { installViaSession(item, file) }
            }
        } else {
            if (useRoot) {
                val (ok, text) = Root.installModule(file.path)
                if (ok) Outcome.Done("${item.displayName} instalado. Reinicia para aplicar.") else Outcome.Failed(text.ifBlank { "Falló la instalación" })
            } else {
                withContext(Dispatchers.IO) { saveToDownloads(file) }
            }
        }
    }

    private fun installViaSession(item: TrackedItem, file: File): Outcome = try {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(item.identifier)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sid = pi.createSession(params)
        pi.openSession(sid).use { s ->
            file.inputStream().use { input ->
                s.openWrite("base.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    s.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName)
            val pending = PendingIntent.getBroadcast(
                ctx, sid, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(pending.intentSender)
        }
        Outcome.Pending("Confirma la instalación en el diálogo del sistema")
    } catch (e: Exception) {
        Outcome.Failed("No se pudo iniciar la instalación: ${e.message}")
    }

    private fun saveToDownloads(file: File): Outcome = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = checkNotNull(ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        ctx.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        Outcome.Pending("Sin root: guardado en Descargas (${file.name}). Instálalo desde tu gestor de módulos.")
    } catch (e: Exception) {
        Outcome.Failed("No se pudo guardar en Descargas: ${e.message}")
    }
}
