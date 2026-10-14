package com.kherio.gitwatch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.kherio.gitwatch.work.UpdateWorker
import com.topjohnwu.superuser.Shell

class GitWatchApp : Application() {
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).setTimeout(30))
        repo = Repository(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "Actualizaciones", NotificationManager.IMPORTANCE_DEFAULT),
        )
        UpdateWorker.schedule(this, repo.prefs.intervalHours)
    }

    companion object {
        const val CHANNEL_UPDATES = "updates"
    }
}
