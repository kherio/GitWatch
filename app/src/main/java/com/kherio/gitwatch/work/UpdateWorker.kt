package com.kherio.gitwatch.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kherio.gitwatch.GitWatchApp
import com.kherio.gitwatch.MainActivity
import com.kherio.gitwatch.R
import com.kherio.gitwatch.data.Kind
import com.kherio.gitwatch.data.TrackedItem
import com.kherio.gitwatch.domain.ItemUi
import com.kherio.gitwatch.domain.Outcome
import com.kherio.gitwatch.domain.Status
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as GitWatchApp).repo
        repo.refreshModules()

        val checked = mutableListOf<TrackedItem>()
        for (item in repo.dao.all()) {
            checked += repo.check(item)
            delay(200) // trato amable con la API
        }

        val mods = repo.modules.value
        val pending = checked
            .map { ItemUi(it, repo.installedVersion(it, mods)) }
            .filter { it.status == Status.UPDATE && it.item.notifiedTag != it.item.latestTag }
        if (pending.isEmpty()) return Result.success()

        // Instalación automática (solo apps, solo con root y solo si el usuario lo ha activado).
        val autoApps = repo.prefs.rootEnabled && repo.prefs.autoInstall
        val installed = mutableListOf<String>()
        val manual = mutableListOf<ItemUi>()
        for (ui in pending) {
            if (autoApps && ui.item.kind == Kind.APP) {
                val out = runCatching { repo.install(ui.item) }.getOrNull()
                if (out is Outcome.Done) {
                    installed += ui.item.displayName
                    continue
                }
            }
            manual += ui
        }

        manual.forEach { repo.dao.update(it.item.copy(notifiedTag = it.item.latestTag)) }

        if (repo.prefs.notify) {
            if (manual.isNotEmpty()) {
                val names = manual.joinToString(", ") { "${it.item.displayName} ${it.item.latestVersion}" }
                val title = if (manual.size == 1) "1 actualización disponible" else "${manual.size} actualizaciones disponibles"
                notifyUser(1, title, names)
            }
            if (installed.isNotEmpty()) {
                notifyUser(2, "Actualizadas automáticamente", installed.joinToString(", "))
            }
        }
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private fun notifyUser(id: Int, title: String, text: String) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, GitWatchApp.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    companion object {
        private const val NAME = "gitwatch-check"

        fun schedule(ctx: Context, hours: Int, replace: Boolean = false) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                NAME,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}
