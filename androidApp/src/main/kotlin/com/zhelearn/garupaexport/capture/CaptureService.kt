package com.zhelearn.garupaexport.capture

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
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
    private lateinit var notifications: CaptureNotifications
    private var proxy: ProxyServer? = null
    private var stopped = false
    private var starting = false

    override fun onCreate() {
        super.onCreate()
        notifications = CaptureNotifications(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lock) {
            if (starting || proxy != null) return START_NOT_STICKY
            starting = true
        }
        container.captures.starting()
        notifications.clearResult()
        try {
            val notification = notifications.foreground()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(CaptureNotifications.SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(CaptureNotifications.SERVICE_NOTIFICATION_ID, notification)
        } catch (error: Exception) {
            fail(error)
            return START_NOT_STICKY
        }
        startup.execute {
            try {
                val session = CaptureSession { capture ->
                    container.onCaptured(capture)
                    synchronized(lock) { if (!stopped) notifications.captureComplete() }
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
}
