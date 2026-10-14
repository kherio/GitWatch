package com.kherio.gitwatch.domain

import com.kherio.gitwatch.data.TrackedItem

/** El orden de los valores es el orden de aparición en la lista principal. */
enum class Status { UPDATE, NOT_INSTALLED, ERROR, UNKNOWN, IGNORED, UPTODATE }

data class ItemUi(val item: TrackedItem, val installed: String?) {
    val status: Status
        get() {
            val latest = item.latestVersion
            val inst = installed
            return when {
                latest == null -> if (item.lastError != null) Status.ERROR else Status.UNKNOWN
                inst == null -> Status.NOT_INSTALLED
                Versions.compare(latest, Versions.normalize(inst)) > 0 ->
                    if (item.ignoredTag != null && item.ignoredTag == item.latestTag) Status.IGNORED else Status.UPDATE
                else -> Status.UPTODATE
            }
        }
}
