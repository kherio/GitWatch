package com.kherio.gitwatch.domain

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Todo lo que requiere root: leer módulos KernelSU/ReSukiSU/Magisk/APatch e instalar sin diálogos. */
object Root {
    data class Module(
        val id: String,
        val name: String,
        val version: String,
        val versionCode: Long,
        val author: String,
        val description: String,
        val updateJson: String,
        val disabled: Boolean,
    )

    suspend fun available(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Shell.getShell().isRoot }.getOrDefault(false)
    }

    fun parseProp(lines: List<String>): Map<String, String> = lines.mapNotNull { l ->
        val i = l.indexOf('=')
        if (i > 0) l.substring(0, i).trim() to l.substring(i + 1).trim() else null
    }.toMap()

    /** Lee todos los module.prop con una sola llamada al shell. */
    suspend fun modules(): List<Module> = withContext(Dispatchers.IO) {
        if (!available()) return@withContext emptyList()
        val script = "for d in /data/adb/modules/*/; do " +
            "echo \"@@MOD \$(basename \"\$d\")\"; " +
            "[ -e \"\$d/disable\" ] && echo @@DISABLED; " +
            "cat \"\$d/module.prop\" 2>/dev/null; echo; done"
        val lines = Shell.cmd(script).exec().out

        val result = mutableListOf<Module>()
        var dir: String? = null
        var disabled = false
        var buf = mutableListOf<String>()

        fun flush() {
            val d = dir ?: return
            if (d == "*") return
            val p = parseProp(buf)
            val id = p["id"]?.takeIf { it.isNotBlank() } ?: d
            result += Module(
                id = id,
                name = p["name"] ?: id,
                version = p["version"] ?: "",
                versionCode = p["versionCode"]?.toLongOrNull() ?: 0L,
                author = p["author"] ?: "",
                description = p["description"] ?: "",
                updateJson = p["updateJson"] ?: "",
                disabled = disabled,
            )
        }

        for (l in lines) {
            when {
                l.startsWith("@@MOD ") -> {
                    flush()
                    dir = l.removePrefix("@@MOD ").trim()
                    disabled = false
                    buf = mutableListOf()
                }
                l == "@@DISABLED" -> disabled = true
                else -> buf += l
            }
        }
        flush()
        result.sortedBy { it.name.lowercase() }
    }

    /** Instala un ZIP de módulo con el gestor que haya (ksud, apd o magisk). Requiere reinicio. */
    suspend fun installModule(path: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val tool = listOf(
            "/data/adb/ksud module install",
            "/data/adb/apd module install",
            "magisk --install-module",
        ).firstOrNull { cmd ->
            val bin = cmd.substringBefore(' ')
            if (bin.startsWith("/")) Shell.cmd("test -x $bin").exec().isSuccess
            else Shell.cmd("command -v $bin").exec().isSuccess
        } ?: return@withContext false to "No se encontró ksud, apd ni magisk"
        val r = Shell.cmd("$tool '$path'").exec()
        r.isSuccess to (r.out + r.err).takeLast(6).joinToString("\n")
    }

    /** Instalación silenciosa de APK. Android sigue exigiendo que la firma coincida con la instalada. */
    suspend fun installApk(path: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val r = Shell.cmd("pm install -r '$path'").exec()
        val text = (r.out + r.err).joinToString("\n").trim()
        (r.isSuccess && text.contains("Success")) to text
    }
}
