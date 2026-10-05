package com.kolnovel.reader.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kolnovel.reader.MainActivity
import com.kolnovel.reader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps downloads going with the screen off or the app in the background, with a progress notification. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "تنزيل الفصول", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) Downloads.pause()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ONGOING_ID, progressNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ONGOING_ID, progressNotification())
            }
        }.isSuccess
        if (!started) {
            // Android refused a background start; the queue still runs while the app is open.
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher == null) {
            watcher = scope.launch {
                // Give the worker a moment to start before judging that it has stopped.
                delay(1500)
                while (Downloads.running) {
                    runCatching { NotificationManagerCompat.from(this@DownloadService).notify(ONGOING_ID, progressNotification()) }
                    delay(1000)
                }
                finish()
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        val jobs = Downloads.jobs
        val text = when {
            Downloads.notice != null -> Downloads.notice!!
            Downloads.paused -> "التنزيل متوقف مؤقتًا."
            else -> {
                val done = jobs.sumOf { it.done }
                val failed = jobs.sumOf { it.failed.size }
                if (failed > 0) "اكتمل: $done فصل محفوظ، وتعذر $failed." else "اكتمل التنزيل: $done فصل محفوظ للقراءة بدون نت."
            }
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(
                DONE_ID,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_download)
                    .setContentTitle("تنزيل الفصول")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build(),
            )
        }
        stopSelf()
    }

    private fun progressNotification(): android.app.Notification {
        val job = Downloads.jobs.firstOrNull { !it.finished }
        val pendingTotal = Downloads.jobs.sumOf { it.pending.size }
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .addAction(0, "إيقاف مؤقت", pauseIntent())
        if (job == null) {
            builder.setContentTitle("تنزيل الفصول").setProgress(0, 0, true)
        } else {
            val handled = job.done + job.failed.size
            builder.setContentTitle(job.title)
                .setContentText("${Downloads.current?.label ?: ""}  •  $handled/${job.total}  •  متبقي $pendingTotal")
                .setProgress(job.total.coerceAtLeast(1), handled, false)
        }
        return builder.build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun pauseIntent(): PendingIntent = PendingIntent.getService(
        this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "downloads"
        const val ONGOING_ID = 41
        const val DONE_ID = 42
        const val ACTION_PAUSE = "com.kolnovel.reader.PAUSE"
    }
}
