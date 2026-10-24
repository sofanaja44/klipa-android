package com.example.klipa.feature.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps Klipa in the foreground while an export runs, so Android neither freezes nor ends
 * it when the user switches apps, and shows the progress in a notification. When the
 * export ends the service stops, leaving a notification that the video was saved (tap to
 * play it) or that the export failed.
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        // Android requires the foreground notification right away.
        ServiceCompat.startForeground(this, PROGRESS_ID, progress(null, saving = false), foregroundType())
        if (watching == null) {
            val exporter = (application as ExportHost).exporter
            watching = scope.launch {
                var shown: Int? = -1
                exporter.state.collect { state ->
                    when (state) {
                        is ExportState.Running -> {
                            val percent = state.progress?.let { (it * 100).roundToInt() }
                            if (percent != shown) {
                                shown = percent
                                notifications.notify(PROGRESS_ID, progress(state.progress, saving = false))
                            }
                        }
                        ExportState.Saving -> notifications.notify(PROGRESS_ID, progress(1f, saving = true))
                        else -> finish(state)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish(state: ExportState) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        result(state)?.let { notifications.notify(RESULT_ID, it) }
        watching?.cancel()
        watching = null
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, getString(R.string.export_notification_channel), NotificationManager.IMPORTANCE_LOW)
        notifications.createNotificationChannel(channel)
    }

    private fun foregroundType(): Int = when {
        Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    private fun progress(progress: Float?, saving: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_klipa_export)
            .setContentTitle(getString(if (saving) R.string.export_saving else R.string.export_notification_running))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (progress == null) {
            builder.setProgress(0, 0, true)
        } else {
            val percent = (progress * 100).roundToInt()
            builder.setProgress(100, percent, false).setContentText("$percent%")
        }
        return builder.build()
    }

    private fun result(state: ExportState): Notification? {
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_klipa_export)
            .setAutoCancel(true)
        when (state) {
            is ExportState.Done -> {
                val video = state.video
                val size = Formatter.formatShortFileSize(this, video.sizeBytes)
                builder
                    .setContentTitle(getString(R.string.export_notification_done))
                    .setContentText(getString(if (video.inGallery) R.string.export_notification_done_gallery else R.string.export_notification_done_app, size))
                    .setContentIntent(play(video))
            }
            is ExportState.Failed -> builder
                .setContentTitle(getString(R.string.export_failed))
                .setContentText(getString(state.failure.message))
                .setContentIntent(openApp())
            // Cancelled: nothing to report.
            else -> return null
        }
        return builder.build()
    }

    private fun openApp(): PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let {
        PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun play(video: ExportedVideo): PendingIntent {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(video.uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this, 1, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private const val CHANNEL = "exports"
        private const val PROGRESS_ID = 1
        private const val RESULT_ID = 2

        /** Starts the service for an export that has just begun; call while Klipa is in the foreground. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
        }
    }
}
