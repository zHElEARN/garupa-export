package com.zhelearn.garupaexport.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhelearn.garupaexport.R
import com.zhelearn.garupaexport.capture.ProxyStatus
import com.zhelearn.garupaexport.profile.ExportedProfile
import com.zhelearn.garupaexport.profile.ProfileState
import com.zhelearn.garupaexport.shizuku.ShizukuStatus
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ExportScreen(
    state: ExportUiState,
    onStartProxy: () -> Unit,
    onStopProxy: (manuallyCleared: Boolean) -> Unit,
    onProxySettings: () -> Unit,
    onAuthorizeShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRetry: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onCopy: (ExportedProfile) -> Unit,
    onSave: (ExportedProfile) -> Unit,
    onShare: (ExportedProfile) -> Unit,
) {
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.catalog_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.catalog_description))
                    val cached = state.catalog.cache
                    if (cached != null) {
                        Text(stringResource(R.string.catalog_summary, cached.cardCount))
                        Text(stringResource(R.string.catalog_updated,
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(cached.updatedAtMillis))))
                    } else {
                        Text(stringResource(R.string.catalog_required))
                    }
                    if (state.catalog.loading) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.catalog_loading))
                        }
                    }
                    state.catalog.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.catalog.upToDate) Text(stringResource(R.string.catalog_up_to_date))
                    OutlinedButton(onClick = onRefreshCatalog, enabled = !state.catalog.loading) {
                        Text(stringResource(if (cached == null) R.string.catalog_download else R.string.catalog_refresh))
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.shizuku_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when (state.shizuku) {
                        ShizukuStatus.NotInstalled -> R.string.shizuku_not_installed
                        ShizukuStatus.NotRunning -> R.string.shizuku_not_running
                        ShizukuStatus.Unauthorized -> R.string.shizuku_unauthorized
                        ShizukuStatus.Authorized -> R.string.shizuku_authorized
                    }))
                    if (state.shizuku == ShizukuStatus.Unauthorized || state.shizuku == ShizukuStatus.NotRunning) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (state.shizuku == ShizukuStatus.Unauthorized) {
                                Button(onClick = onAuthorizeShizuku) { Text(stringResource(R.string.shizuku_authorize)) }
                            }
                            OutlinedButton(onClick = onOpenShizuku) { Text(stringResource(R.string.shizuku_open)) }
                        }
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.proxy_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when {
                        state.capture.status == ProxyStatus.Starting -> R.string.proxy_starting
                        state.capture.status == ProxyStatus.Stopping -> R.string.proxy_stopping
                        state.capture.status == ProxyStatus.Stopped -> R.string.proxy_stopped
                        state.capture.captured -> R.string.capture_complete
                        else -> R.string.proxy_running
                    }))
                    SelectionContainer { Text(stringResource(R.string.proxy_address)) }
                    state.capture.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                when {
                                    state.capture.status == ProxyStatus.Stopped -> onStartProxy()
                                    state.capture.automaticProxy -> onStopProxy(false)
                                    else -> confirmStop = true
                                }
                            },
                            enabled = (state.capture.status == ProxyStatus.Stopped && state.catalog.available) ||
                                state.capture.status == ProxyStatus.Running,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(if (state.capture.status == ProxyStatus.Stopped) R.string.start_proxy else R.string.stop_proxy))
                        }
                        OutlinedButton(onClick = onProxySettings, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.proxy_settings))
                        }
                    }
                    if (state.capture.clearFailed) {
                        TextButton(onClick = { confirmStop = true }) {
                            Text(stringResource(R.string.proxy_manually_cleared))
                        }
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.titleMedium)
                    when (val profile = state.profile) {
                        ProfileState.Parsing -> {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.profile_loading))
                        }
                        ProfileState.Empty -> Text(stringResource(R.string.profile_empty))
                        is ProfileState.Failed -> {
                            Text(profile.message, color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry_export)) }
                        }
                        is ProfileState.Ready -> ProfileContent(profile.profile, onCopy, onSave, onShare)
                    }
                }
            }
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.confirm_stop_proxy)) },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; onStopProxy(true) }) {
                    Text(stringResource(R.string.stop_proxy))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ProfileContent(
    profile: ExportedProfile,
    onCopy: (ExportedProfile) -> Unit,
    onSave: (ExportedProfile) -> Unit,
    onShare: (ExportedProfile) -> Unit,
) {
    Text(profile.name, style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.profile_summary, profile.cardCount, profile.areaItemCount, profile.potentialCount))
    profile.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    Button(onClick = { onCopy(profile) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.copy_profile))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { onSave(profile) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.save_profile))
        }
        OutlinedButton(onClick = { onShare(profile) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.share_profile))
        }
    }
}
