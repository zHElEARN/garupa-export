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
import com.zhelearn.garupaexport.capture.guide.GuideStage
import com.zhelearn.garupaexport.capture.guide.guideStage
import com.zhelearn.garupaexport.capture.proxy.CertificateAuthority
import com.zhelearn.garupaexport.capture.proxy.ProxyProbe
import com.zhelearn.garupaexport.capture.proxy.ProxyServer
import com.zhelearn.garupaexport.shizuku.ShizukuProxySession
import com.zhelearn.garupaexport.shizuku.ShizukuStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns the in-memory session, including setup, system-proxy checks and background presentation. */
class CaptureService : Service() {
    private val container get() = (application as GarupaExportApplication).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private lateinit var notifications: CaptureNotifications
    private lateinit var verifier: SystemProxyVerifier
    private val probe = ProxyProbe()
    private var proxy: ProxyServer? = null
    private var systemProxy: ShizukuProxySession? = null
    private var verification: Job? = null
    private var stopped = false
    private var starting = false
    private var configuring = false
    private var automaticAttempted = false

    override fun onCreate() {
        super.onCreate()
        notifications = CaptureNotifications(this)
        verifier = SystemProxyVerifier(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture(manuallyCleared = false)
            ACTION_STOP_MANUAL -> stopCapture(manuallyCleared = true)
            ACTION_RECHECK -> {
                val session = intent.getStringExtra(CaptureNotifications.EXTRA_SESSION)
                if (session == null || session == container.captures.state.value.sessionId) checkProxy()
                if (!starting) stopSelf()
            }
            else -> startCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        if (starting) return
        starting = true
        container.beginCapture()
        notifications.clearResult()
        try {
            val notification = notifications.foreground(GuideStage.Preparing, null)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(CaptureNotifications.SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(CaptureNotifications.SERVICE_NOTIFICATION_ID, notification)
        } catch (error: Exception) {
            fail(error)
            return
        }
        observePresentation()
        scope.launch {
            try {
                container.catalog.ensureAvailable()
                container.captures.phase(CapturePhase.StartingProxy)
                val session = CaptureSession(container::onCaptureProgress, container::onCaptured)
                val authority = withContext(Dispatchers.IO) {
                    CertificateAuthority(
                        assets.open("sniffer-ca/ca_cert.pem").bufferedReader().use { it.readText() },
                        assets.open("sniffer-ca/ca_key.pem").bufferedReader().use { it.readText() },
                    )
                }
                val server = ProxyServer(authority, { Log.d("CaptureProxy", it) }, session::begin, probe)
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
                container.captures.phase(CapturePhase.SettingProxy)
                val setup = enableSystemProxy()
                container.captures.running(session.sessionId, systemProxy != null, setup.error, setup.clearFailed)
                checkProxy()
                observeEnvironment()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    private fun observePresentation() {
        scope.launch {
            combine(container.captures.state, container.profiles.state) { capture, profile ->
                guideStage(capture, profile) to capture.sessionId
            }.distinctUntilChanged().collectLatest { (stage, sessionId) ->
                // Coalesce brief setup/conversion transitions instead of flooding the notification manager.
                delay(250)
                Log.d("CaptureService", "Guide stage: $stage; app visible: ${container.appVisible}")
                notifications.update(stage, sessionId)
                if (!container.appVisible && stage in setOf(GuideStage.OpenGame, GuideStage.Complete,
                        GuideStage.ConversionFailed)) {
                    notifications.milestone(stage, sessionId)
                }
            }
        }
    }

    private fun observeEnvironment() {
        scope.launch {
            verifier.changes.catch { error ->
                Log.w("CaptureService", "Unable to observe network changes; foreground checks remain available", error)
            }.collect { if (!configuring) checkProxy() }
        }
        scope.launch {
            container.shizuku.status.collect { status ->
                val capture = container.captures.state.value
                if (status == ShizukuStatus.Authorized && systemProxy == null && !configuring && !automaticAttempted &&
                    capture.status == ProxyStatus.Running && !capture.proxyVerified && !capture.captured) {
                    configuring = true
                    verification?.cancel()
                    container.captures.phase(CapturePhase.SettingProxy)
                    val setup = enableSystemProxy()
                    container.captures.running(checkNotNull(capture.sessionId), systemProxy != null, setup.error, setup.clearFailed)
                    configuring = false
                    checkProxy()
                }
            }
        }
    }

    private fun checkProxy() {
        if (proxy == null || configuring || container.captures.state.value.status != ProxyStatus.Running) return
        verification?.cancel()
        verification = scope.launch {
            // Proxy broadcasts can precede ProxySelector updates in this process.
            delay(300)
            val environment = verifier.environment()
            val configurationError = verifier.configurationError(environment)
            if (configurationError != null) {
                container.captures.verified(environment, configurationError)
                return@launch
            }
            container.captures.checking(environment)
            val error = try {
                if (verifier.verify(probe)) null else getString(R.string.guide_probe_failed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("CaptureService", "System proxy verification failed", error)
                getString(R.string.guide_probe_failed)
            }
            // A network change during IO invalidates the result, even if the request succeeded.
            if (environment == verifier.environment()) container.captures.verified(environment, error)
            else checkProxy()
        }
    }

    private suspend fun enableSystemProxy(): ProxySetup {
        if (container.shizuku.status.value != ShizukuStatus.Authorized) return ProxySetup()
        automaticAttempted = true
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
        if (!starting) { stopSelf(); return }
        if (container.captures.state.value.status != ProxyStatus.Running || configuring) return
        verification?.cancel()
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
        Log.e("CaptureService", "Capture session startup failed", error)
        container.captures.failed(error.message ?: getString(R.string.proxy_start_failed))
        if (!container.appVisible) {
            notifications.milestone(GuideStage.StartFailed, container.captures.state.value.sessionId)
        }
        stopSelf()
    }

    private class ProxySetup(val error: String? = null, val clearFailed: Boolean = false)

    companion object {
        const val ACTION_STOP = "com.zhelearn.garupaexport.STOP_CAPTURE"
        const val ACTION_STOP_MANUAL = "com.zhelearn.garupaexport.STOP_CAPTURE_MANUAL"
        const val ACTION_RECHECK = "com.zhelearn.garupaexport.RECHECK_PROXY"
    }
}
