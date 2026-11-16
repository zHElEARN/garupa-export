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
import com.zhelearn.garupaexport.shizuku.ShizukuProxySession
import com.zhelearn.garupaexport.shizuku.ShizukuStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureService : Service() {
    private val container get() = (application as GarupaExportApplication).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private lateinit var notifications: CaptureNotifications
    private var proxy: ProxyServer? = null
    private var systemProxy: ShizukuProxySession? = null
    private var stopped = false
    private var starting = false

    override fun onCreate() {
        super.onCreate()
        notifications = CaptureNotifications(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture(manuallyCleared = false)
            ACTION_STOP_MANUAL -> stopCapture(manuallyCleared = true)
            else -> startCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        if (starting || proxy != null) return
        if (!container.catalog.state.value.available) {
            container.captures.failed(getString(R.string.catalog_required))
            stopSelf()
            return
        }
        starting = true
        container.beginCapture()
        notifications.clearResult()
        try {
            val notification = notifications.foreground()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(CaptureNotifications.SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(CaptureNotifications.SERVICE_NOTIFICATION_ID, notification)
        } catch (error: Exception) {
            fail(error)
            return
        }
        scope.launch {
            try {
                val session = CaptureSession { capture ->
                    container.onCaptured(capture)
                    synchronized(lock) { if (!stopped) notifications.captureComplete() }
                }
                val authority = withContext(Dispatchers.IO) {
                    CertificateAuthority(
                        assets.open("sniffer-ca/ca_cert.pem").bufferedReader().use { it.readText() },
                        assets.open("sniffer-ca/ca_key.pem").bufferedReader().use { it.readText() },
                    )
                }
                val server = ProxyServer(
                    authority = authority,
                    log = { Log.d("CaptureProxy", it) },
                    capture = session::begin,
                )
                container.captures.sessionCreated(session.sessionId)
                withContext(Dispatchers.IO) {
                    synchronized(lock) {
                        if (stopped) {
                            server.close()
                            throw CancellationException("Capture service stopped")
                        }
                        server.start()
                        proxy = server
                    }
                }
                container.shizuku.refresh()
                val setup = enableSystemProxy()
                container.captures.running(
                    session.sessionId, automaticProxy = systemProxy != null,
                    error = setup.error, clearFailed = setup.clearFailed,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    private suspend fun enableSystemProxy(): ProxySetup {
        if (container.shizuku.status.value != ShizukuStatus.Authorized) return ProxySetup()
        val settings = ShizukuProxySession(this)
        systemProxy = settings
        try {
            settings.enable()
            return ProxySetup()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("CaptureService", "Automatic proxy setup failed", error)
            try {
                settings.clear()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (clearError: Exception) {
                Log.e("CaptureService", "Proxy setup cleanup failed", clearError)
                return ProxySetup(getString(R.string.proxy_clear_failed), clearFailed = true)
            }
            settings.close()
            systemProxy = null
            return ProxySetup(getString(R.string.proxy_setup_failed))
        }
    }

    private fun stopCapture(manuallyCleared: Boolean) {
        if (!starting && proxy == null) {
            stopSelf()
            return
        }
        if (container.captures.state.value.status != ProxyStatus.Running) return
        container.captures.stopping()
        scope.launch {
            try {
                if (!manuallyCleared) systemProxy?.clear()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("CaptureService", "System proxy clearing failed", error)
                container.captures.stopFailed(getString(R.string.proxy_clear_failed))
                return@launch
            }
            systemProxy?.close()
            systemProxy = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        synchronized(lock) {
            stopped = true
            proxy?.close()
            proxy = null
            container.captures.stopped()
        }
        systemProxy?.close()
        systemProxy = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun fail(error: Exception) {
        Log.e("CaptureService", "Proxy startup failed", error)
        container.captures.failed(getString(R.string.proxy_start_failed))
        stopSelf()
    }

    private class ProxySetup(val error: String? = null, val clearFailed: Boolean = false)

    companion object {
        const val ACTION_STOP = "com.zhelearn.garupaexport.STOP_CAPTURE"
        const val ACTION_STOP_MANUAL = "com.zhelearn.garupaexport.STOP_CAPTURE_MANUAL"
    }
}
