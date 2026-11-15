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

@Composable
internal fun ExportScreen(
    state: ExportUiState,
    onStartProxy: () -> Unit,
    onStopProxy: () -> Unit,
    onProxySettings: () -> Unit,
    onRetry: () -> Unit,
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
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.proxy_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when {
                        state.capture.status == ProxyStatus.Starting -> R.string.proxy_starting
                        state.capture.status == ProxyStatus.Stopped -> R.string.proxy_stopped
                        state.capture.captured -> R.string.capture_complete
                        else -> R.string.proxy_running
                    }))
                    SelectionContainer { Text(stringResource(R.string.proxy_address)) }
                    state.capture.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { if (state.capture.status == ProxyStatus.Stopped) onStartProxy() else confirmStop = true },
                            enabled = state.capture.status != ProxyStatus.Starting,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(if (state.capture.status == ProxyStatus.Stopped) R.string.start_proxy else R.string.stop_proxy))
                        }
                        OutlinedButton(onClick = onProxySettings, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.proxy_settings))
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
                TextButton(onClick = { confirmStop = false; onStopProxy() }) {
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
