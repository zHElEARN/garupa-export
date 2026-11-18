package com.zhelearn.garupaexport.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.zhelearn.garupaexport.MainActivity
import com.zhelearn.garupaexport.R
import com.zhelearn.garupaexport.capture.guide.GuideStage

internal class CaptureNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var proxyReadyAlertPosted = false

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannels(listOf(
                NotificationChannel(SERVICE_CHANNEL, context.getString(R.string.guide_session_channel), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(RESULT_CHANNEL, context.getString(R.string.capture_result_channel), NotificationManager.IMPORTANCE_HIGH),
            ))
        }
    }

    fun foreground(stage: GuideStage, sessionId: String?, receivingPercent: Int? = null): Notification {
        val builder = NotificationCompat.Builder(context, SERVICE_CHANNEL)
            .setContentTitle(context.getString(R.string.capture_service_running))
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentIntent(openGuide(sessionId))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
        if (stage == GuideStage.Receiving) {
            if (receivingPercent == null) builder.setProgress(0, 0, true)
            else builder.setProgress(100, receivingPercent, false)
        }
        if (stage == GuideStage.ConfigureProxy) {
            builder.addAction(0, context.getString(R.string.guide_wifi_settings), PendingIntent.getActivity(
                context, 2, Intent(Settings.ACTION_WIFI_SETTINGS), PendingIntent.FLAG_IMMUTABLE,
            ))
            val verify = Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_RECHECK)
                .putExtra(EXTRA_SESSION, sessionId)
            builder.addAction(0, context.getString(R.string.guide_verify), PendingIntent.getService(
                context, 3, verify, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
        } else {
            builder.addAction(0, context.getString(R.string.capture_return_to_app), openGuide(sessionId))
        }
        return builder.build()
    }

    fun update(stage: GuideStage, sessionId: String?, receivingPercent: Int?) {
        manager.notify(SERVICE_NOTIFICATION_ID, foreground(stage, sessionId, receivingPercent))
    }

    fun milestone(stage: GuideStage, sessionId: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val title = if (stage == GuideStage.OpenGame) R.string.capture_proxy_ready else stage.title
        val body = when (stage) {
            GuideStage.OpenGame -> R.string.capture_view_next_step
            GuideStage.StartFailed -> R.string.capture_view_failure
            else -> stage.description
        }
        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(body))
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentIntent(openGuide(sessionId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, notification)
        proxyReadyAlertPosted = stage == GuideStage.OpenGame
    }

    fun clearProxyReady() {
        if (proxyReadyAlertPosted) clearResult()
    }

    fun clearResult() {
        manager.cancel(RESULT_NOTIFICATION_ID)
        proxyReadyAlertPosted = false
    }

    private fun openGuide(sessionId: String?): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).setAction(ACTION_VIEW_GUIDE)
            .putExtra(EXTRA_SESSION, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val SERVICE_NOTIFICATION_ID = 1001
        const val ACTION_VIEW_GUIDE = "com.zhelearn.garupaexport.VIEW_GUIDE"
        const val EXTRA_SESSION = "capture_session"
        private const val RESULT_NOTIFICATION_ID = 1002
        private const val SERVICE_CHANNEL = "capture_proxy"
        private const val RESULT_CHANNEL = "capture_result"
    }
}
