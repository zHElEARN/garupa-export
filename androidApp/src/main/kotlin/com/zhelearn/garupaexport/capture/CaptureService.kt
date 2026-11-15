package com.zhelearn.garupaexport.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.zhelearn.garupaexport.MainActivity
import com.zhelearn.garupaexport.R
import com.zhelearn.garupaexport.app.GarupaExportApplication
import com.zhelearn.garupaexport.capture.data.CaptureSession
import com.zhelearn.garupaexport.capture.proxy.CertificateAuthority
import com.zhelearn.garupaexport.capture.proxy.ProxyServer
import java.util.concurrent.Executors

class CaptureService : Service() {
    private val container get() = (application as GarupaExportApplication).container
    private val startup = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var proxy: ProxyServer? = null
    private var stopped = false
    private var starting = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.proxy_title), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lock) {
            if (starting || proxy != null) return START_NOT_STICKY
            starting = true
        }
        container.captures.starting()
        try {
            val notification = notification(getString(R.string.proxy_starting))
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION, notification)
        } catch (error: Exception) {
            fail(error)
            return START_NOT_STICKY
        }
        startup.execute {
            try {
                val session = CaptureSession { capture ->
                    container.onCaptured(capture)
                    updateNotification(getString(R.string.capture_complete))
                }
                val authority = CertificateAuthority(
                    assets.open("sniffer-ca/ca_cert.pem").bufferedReader().use { it.readText() },
                    assets.open("sniffer-ca/ca_key.pem").bufferedReader().use { it.readText() },
                )
                val server = ProxyServer(
                    authority = authority,
                    log = { Log.d("CaptureProxy", it) },
                    capture = session::begin,
                )
                synchronized(lock) {
                    if (stopped) {
                        server.close()
                        return@execute
                    }
                    container.captures.sessionCreated(session.sessionId)
                    server.start()
                    proxy = server
                    container.captures.running(session.sessionId)
                    updateNotification(getString(R.string.proxy_running))
                }
            } catch (error: Exception) {
                synchronized(lock) { if (!stopped) fail(error) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(lock) {
            stopped = true
            proxy?.close()
            proxy = null
            container.captures.stopped()
        }
        startup.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun fail(error: Exception) {
        Log.e("CaptureService", "Proxy startup failed", error)
        container.captures.failed(getString(R.string.proxy_start_failed))
        stopSelf()
    }

    private fun updateNotification(message: String) = synchronized(lock) {
        if (!stopped) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message))
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()
    }

    private companion object {
        const val CHANNEL = "capture_proxy"
        const val NOTIFICATION = 1001
    }
}
