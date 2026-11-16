package com.zhelearn.garupaexport

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.app.NotificationManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.zhelearn.garupaexport.app.GarupaExportApplication
import com.zhelearn.garupaexport.capture.CaptureService
import com.zhelearn.garupaexport.capture.CaptureNotifications
import com.zhelearn.garupaexport.capture.ProxyStatus
import com.zhelearn.garupaexport.export.ExportScreen
import com.zhelearn.garupaexport.export.ExportViewModel
import com.zhelearn.garupaexport.export.ProfileActions
import com.zhelearn.garupaexport.profile.ProfileState
import com.zhelearn.garupaexport.shizuku.ShizukuRepository
import com.zhelearn.garupaexport.ui.theme.GarupaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as GarupaExportApplication).container
    private val profileActions by lazy { ProfileActions(this) }
    private var focusRequest by mutableIntStateOf(0)
    private var notificationPermissionRequested = false
    private val model by lazy {
        ViewModelProvider(this, viewModelFactory {
            initializer { ExportViewModel(container.captures, container.profiles, container.shizuku, container.catalog) }
        })[ExportViewModel::class.java]
    }
    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val profile = model.takePendingSave()
        if (uri != null && profile != null) performAction(R.string.profile_saved, R.string.save_failed) {
            profileActions.save(profile.json, uri)
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateNotificationPermission()
        startProxy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.action == CaptureNotifications.ACTION_VIEW_GUIDE) focusRequest++
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }
            GarupaTheme {
                ExportScreen(
                    state = state,
                    snackbarHostState = snackbar,
                    focusRequest = focusRequest,
                    onStartProxy = ::requestStartProxy,
                    onStopProxy = { manuallyCleared ->
                        performAction(failureMessage = R.string.finish_failed) {
                            startService(Intent(this, CaptureService::class.java).setAction(
                                if (manuallyCleared) CaptureService.ACTION_STOP_MANUAL else CaptureService.ACTION_STOP,
                            ))
                        }
                    },
                    onProxySettings = { performAction(failureMessage = R.string.settings_failed) { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } },
                    onVerifyProxy = ::recheckProxy,
                    onCopyProxyHost = { performAction(clipboardFeedback(R.string.proxy_host_copied), R.string.copy_failed) { profileActions.copyProxyHost() } },
                    onAuthorizeShizuku = { performAction(failureMessage = R.string.shizuku_authorize_failed) { container.shizuku.requestPermission() } },
                    onOpenShizuku = { performAction(failureMessage = R.string.shizuku_open_failed) {
                        val intent = packageManager.getLaunchIntentForPackage(ShizukuRepository.MANAGER_PACKAGE)
                            ?: error("无法打开 Shizuku")
                        startActivity(intent)
                    } },
                    onNotificationSettings = { performAction(failureMessage = R.string.settings_failed) {
                        startActivity(if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                    } },
                    onRetry = model::retryExport,
                    onRefreshAndRetry = model::refreshAndRetryExport,
                    onRefreshCatalog = model::refreshCatalog,
                    onCopy = { profile -> performAction(clipboardFeedback(R.string.profile_copied), R.string.copy_failed) { profileActions.copy(profile.json) } },
                    onSave = { profile ->
                        performAction(failureMessage = R.string.save_failed) {
                            if (model.prepareSave(profile)) {
                                try { saveDocument.launch(profile.fileName) }
                                catch (error: Exception) { model.takePendingSave(); throw error }
                            }
                        }
                    },
                    onShare = { profile -> performAction(failureMessage = R.string.share_failed) { profileActions.share(profile) } },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        container.appVisible = true
        container.shizuku.refresh()
        updateNotificationPermission()
        recheckProxy()
    }

    override fun onPause() {
        container.appVisible = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == CaptureNotifications.ACTION_VIEW_GUIDE) focusRequest++
    }

    private fun updateNotificationPermission() {
        val manager = getSystemService(NotificationManager::class.java)
        val channelsEnabled = Build.VERSION.SDK_INT < 26 ||
            manager.getNotificationChannel("capture_result")?.importance != NotificationManager.IMPORTANCE_NONE
        model.updateNotificationPermission(NotificationManagerCompat.from(this).areNotificationsEnabled() && channelsEnabled)
    }

    private fun recheckProxy() {
        if (container.captures.state.value.status != ProxyStatus.Running) return
        performAction {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_RECHECK))
        }
    }

    private fun requestStartProxy() {
        if (container.captures.state.value.status != ProxyStatus.Stopped || container.profiles.state.value == ProfileState.Parsing) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionRequested) {
            notificationPermissionRequested = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startProxy()
    }

    private fun startProxy() {
        if (container.captures.state.value.status != ProxyStatus.Stopped || container.profiles.state.value == ProfileState.Parsing) return
        container.beginCapture()
        try {
            val intent = Intent(this, CaptureService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (error: Exception) {
            Log.e("MainActivity", "Unable to start capture service", error)
            container.captures.failed(getString(R.string.proxy_start_failed))
        }
    }

    private fun clipboardFeedback(message: Int): Int? = if (Build.VERSION.SDK_INT >= 33) null else message

    private fun performAction(successMessage: Int? = null, failureMessage: Int = R.string.action_failed, action: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                action()
                successMessage?.let { model.showMessage(getString(it)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("MainActivity", "Profile action failed", error)
                model.showMessage(getString(failureMessage))
            }
        }
    }
}
