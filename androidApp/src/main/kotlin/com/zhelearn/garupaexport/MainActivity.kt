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
import com.zhelearn.garupaexport.app.GarupaExportApplication
import com.zhelearn.garupaexport.capture.CaptureService
import com.zhelearn.garupaexport.capture.CaptureNotifications
import com.zhelearn.garupaexport.capture.ProxyStatus
import com.zhelearn.garupaexport.export.ExportScreen
import com.zhelearn.garupaexport.export.ExportViewModel
import com.zhelearn.garupaexport.export.ProfileActions
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
        if (uri != null && profile != null) performAction(R.string.profile_saved) {
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
                        startService(Intent(this, CaptureService::class.java).setAction(
                            if (manuallyCleared) CaptureService.ACTION_STOP_MANUAL else CaptureService.ACTION_STOP,
                        ))
                    },
                    onProxySettings = { performAction { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } },
                    onVerifyProxy = ::recheckProxy,
                    onCopyProxyHost = { performAction(R.string.proxy_host_copied) { profileActions.copyProxyHost() } },
                    onAuthorizeShizuku = { performAction { container.shizuku.requestPermission() } },
                    onOpenShizuku = { performAction {
                        val intent = packageManager.getLaunchIntentForPackage(ShizukuRepository.MANAGER_PACKAGE)
                            ?: error("无法打开 Shizuku")
                        startActivity(intent)
                    } },
                    onRetry = model::retryExport,
                    onRefreshCatalog = model::refreshCatalog,
                    onCopy = { profile -> performAction(R.string.profile_copied) { profileActions.copy(profile.json) } },
                    onSave = { profile ->
                        model.prepareSave(profile)
                        saveDocument.launch(profile.fileName)
                    },
                    onShare = { profile -> performAction { profileActions.share(profile) } },
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
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionRequested) {
            notificationPermissionRequested = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startProxy()
    }

    private fun startProxy() {
        try {
            val intent = Intent(this, CaptureService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (error: Exception) {
            Log.e("MainActivity", "Unable to start capture service", error)
            container.captures.failed(getString(R.string.proxy_start_failed))
        }
    }

    private fun performAction(successMessage: Int? = null, action: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                action()
                successMessage?.let { model.showMessage(getString(it)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("MainActivity", "Profile action failed", error)
                model.showMessage(getString(R.string.action_failed))
            }
        }
    }
}
