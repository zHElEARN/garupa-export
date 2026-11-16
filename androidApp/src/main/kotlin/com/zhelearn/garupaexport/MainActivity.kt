package com.zhelearn.garupaexport

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhelearn.garupaexport.app.GarupaExportApplication
import com.zhelearn.garupaexport.capture.CaptureService
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
        startProxy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            GarupaTheme {
                ExportScreen(
                    state = state,
                    onStartProxy = ::requestStartProxy,
                    onStopProxy = { manuallyCleared ->
                        startService(Intent(this, CaptureService::class.java).setAction(
                            if (manuallyCleared) CaptureService.ACTION_STOP_MANUAL else CaptureService.ACTION_STOP,
                        ))
                    },
                    onProxySettings = { performAction { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } },
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
        container.shizuku.refresh()
    }

    private fun requestStartProxy() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
                successMessage?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("MainActivity", "Profile action failed", error)
                Toast.makeText(this@MainActivity, R.string.action_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
