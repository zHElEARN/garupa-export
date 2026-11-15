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
import androidx.core.app.NotificationCompat
import com.zhelearn.garupaexport.MainActivity
import com.zhelearn.garupaexport.R

internal class CaptureNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannels(listOf(
                NotificationChannel(SERVICE_CHANNEL, context.getString(R.string.proxy_title), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(RESULT_CHANNEL, context.getString(R.string.capture_result_channel), NotificationManager.IMPORTANCE_HIGH),
            ))
        }
    }

    fun foreground(): Notification = NotificationCompat.Builder(context, SERVICE_CHANNEL)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.proxy_notification_description))
        .setSmallIcon(R.drawable.ic_stat_capture)
        .setContentIntent(openApp())
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOnlyAlertOnce(true)
        .build()

    fun captureComplete() {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL)
            .setContentTitle(context.getString(R.string.capture_notification_title))
            .setContentText(context.getString(R.string.capture_notification_text))
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    fun clearResult() = manager.cancel(RESULT_NOTIFICATION_ID)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val SERVICE_NOTIFICATION_ID = 1001
        private const val RESULT_NOTIFICATION_ID = 1002
        private const val SERVICE_CHANNEL = "capture_proxy"
        private const val RESULT_CHANNEL = "capture_result"
    }
}
