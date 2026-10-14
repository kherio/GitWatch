package com.kherio.gitwatch.domain

import kotlin.math.max

object Versions {
    private val core = Regex("""\d+(?:\.\d+)*""")
    private val pre = Regex("""^[\s._+-]*(dev|snapshot|nightly|alpha|beta|preview|pre|rc)\.?(\d*)""")

    /** Aplica el regex del usuario (grupo 1 si existe) y quita el prefijo "v". */
    fun normalize(tag: String, regex: String = ""): String {
        var s = tag.trim()
        if (regex.isNotBlank()) {
            val m = runCatching { Regex(regex).find(s) }.getOrNull()
            if (m != null) s = if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.value
        }
        return s.removePrefix("v").removePrefix("V")
    }

    /** >0 si a es más nueva que b. Tolera "1.2.3 (456)", "2026.10.01", "1.0-rc2", ceros a la derecha, etc. */
    fun compare(a: String, b: String): Int {
        val ma = core.find(a)
        val mb = core.find(b)
        if (ma == null || mb == null) return a.compareTo(b)
        val pa = ma.value.split('.').map { it.toLongOrNull() ?: 0L }
        val pb = mb.value.split('.').map { it.toLongOrNull() ?: 0L }
        for (i in 0 until max(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0L }.compareTo(pb.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        val qa = preRank(a.substring(ma.range.last + 1).lowercase())
        val qb = preRank(b.substring(mb.range.last + 1).lowercase())
        return when {
            qa == null && qb == null -> 0
            qa == null -> 1          // a es estable, b es pre-release
            qb == null -> -1
            else -> compareValues(qa.first * 1000 + qa.second, qb.first * 1000 + qb.second)
        }
    }

    /** (rango, número) si el sufijo indica pre-release; null si es estable o un simple sufijo de build. */
    private fun preRank(suffix: String): Pair<Int, Int>? {
        val m = pre.find(suffix) ?: return null
        val rank = when (m.groupValues[1]) {
            "dev", "snapshot", "nightly" -> 0
            "alpha" -> 1
            "beta", "preview", "pre" -> 2
            else -> 3 // rc
        }
        return rank to (m.groupValues[2].toIntOrNull() ?: 0)
    }
}
