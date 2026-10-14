package com.kherio.gitwatch.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object Kind {
    const val APP = "APP"
    const val MODULE = "MODULE"
}

/**
 * Elemento vigilado: una app (identifier = paquete) o un módulo root (identifier = id del module.prop).
 * Si se añade a mano sin conocer el id, identifier = "owner/repo" (marcador) y se corrige al instalar por primera vez.
 */
@Entity(tableName = "tracked", indices = [Index(value = ["kind", "identifier"], unique = true)])
data class TrackedItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val identifier: String,
    val displayName: String,
    val owner: String,
    val repo: String,
    val includePrereleases: Boolean = false,
    val assetRegex: String = "",
    val versionRegex: String = "",
    val etag: String? = null,
    val latestTag: String? = null,
    val latestVersion: String? = null,
    val latestUrl: String? = null,
    val latestAssetName: String? = null,
    val latestAssetUrl: String? = null,
    val latestAssetDigest: String? = null,
    val latestAssetSize: Long = 0,
    val latestNotes: String? = null,
    val lastChecked: Long = 0,
    val lastError: String? = null,
    val notifiedTag: String? = null,
    val ignoredTag: String? = null,
) {
    val isPlaceholderId: Boolean get() = identifier.contains('/')
}
