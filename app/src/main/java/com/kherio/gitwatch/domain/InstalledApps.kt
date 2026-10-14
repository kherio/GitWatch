package com.kherio.gitwatch.domain

import android.content.Context
import android.content.pm.ApplicationInfo

data class InstalledApp(
    val pkg: String,
    val name: String,
    val version: String,
    val system: Boolean,
    val installer: String?,
)

object InstalledApps {
    @Suppress("DEPRECATION")
    fun list(ctx: Context): List<InstalledApp> {
        val pm = ctx.packageManager
        return pm.getInstalledPackages(0).map { pi ->
            val ai = pi.applicationInfo
            val flags = ai?.flags ?: 0
            val pureSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            InstalledApp(
                pkg = pi.packageName,
                name = ai?.loadLabel(pm)?.toString() ?: pi.packageName,
                version = pi.versionName ?: "?",
                system = pureSystem,
                installer = runCatching { pm.getInstallSourceInfo(pi.packageName).installingPackageName }.getOrNull(),
            )
        }.sortedBy { it.name.lowercase() }
    }
}
