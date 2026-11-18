package com.zhelearn.garupaexport.export

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zhelearn.garupaexport.R
import com.zhelearn.garupaexport.capture.guide.GuideStage
import com.zhelearn.garupaexport.shizuku.ShizukuStatus

@Composable
internal fun CaptureGuide(
    state: ExportUiState,
    onStart: () -> Unit,
    onVerify: () -> Unit,
    onSettings: () -> Unit,
    onCopyHost: () -> Unit,
    onAuthorizeShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    manualProxy: Boolean,
    onManualProxyChange: (Boolean) -> Unit,
) {
    val stage = state.stage
    val context = LocalContext.current
    val copyHostLabel = stringResource(R.string.copy_proxy_host)
    if (stage.isIndeterminateProgress) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(Modifier.size(32.dp))
            Text(
                stringResource(if (stage == GuideStage.Generating && state.catalog.loading)
                    R.string.catalog_refreshing else stage.title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(stage.title), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
        val description = stringResource(stage.description)
        if (description.isNotBlank()) {
            Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (stage) {
            GuideStage.Welcome, GuideStage.StartFailed -> {
                state.capture.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = onStart, enabled = state.canStart, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (stage == GuideStage.Welcome) R.string.guide_start else R.string.guide_start_retry))
                }
            }
            GuideStage.ConfigureProxy -> {
                val offerAuthorization = state.shizuku == ShizukuStatus.Unauthorized && !manualProxy
                state.capture.error?.takeUnless { state.capture.clearFailed }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (offerAuthorization) {
                    Button(onClick = onAuthorizeShizuku, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.guide_shizuku_authorize))
                    }
                    TextButton(onClick = { onManualProxyChange(true) }) { Text(stringResource(R.string.guide_manual_setup)) }
                } else {
                    if (state.capture.environment?.wifi == false) {
                        Text(stringResource(R.string.guide_wifi_required), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.guide_manual_instructions))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.proxy_host)) },
                        supportingContent = { SelectionContainer { Text(stringResource(R.string.proxy_host_value)) } },
                        trailingContent = { TextButton(onClick = onCopyHost, modifier = Modifier.semantics {
                            contentDescription = copyHostLabel
                        }) { Text(stringResource(R.string.copy)) } },
                    )
                    ListItem(headlineContent = { Text(stringResource(R.string.proxy_port)) },
                        supportingContent = { SelectionContainer { Text("8888") } })
                    state.capture.verificationError?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.guide_wifi_settings))
                    }
                    if (state.capture.verificationError != null) {
                        OutlinedButton(onClick = onVerify) { Text(stringResource(R.string.guide_verify_again)) }
                    }
                    when (state.shizuku) {
                        ShizukuStatus.Unauthorized -> TextButton(onClick = { onManualProxyChange(false); onAuthorizeShizuku() }) {
                            Text(stringResource(R.string.guide_shizuku_authorize))
                        }
                        ShizukuStatus.NotRunning -> TextButton(onClick = onOpenShizuku) {
                            Text(stringResource(R.string.shizuku_open))
                        }
                        else -> Unit
                    }
                }
            }
            GuideStage.OpenGame -> {
                state.capture.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!state.notificationsEnabled) Text(stringResource(R.string.guide_notifications_disabled),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GuideStage.Receiving -> {
                val progress = state.capture.progress
                val total = progress?.totalBytes
                if (progress != null && total != null && total > 0) {
                    LinearProgressIndicator(progress = { (progress.receivedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.guide_receiving_size_total,
                        Formatter.formatShortFileSize(context, progress.receivedBytes.toLong()),
                        Formatter.formatShortFileSize(context, total.toLong())))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (progress != null) Text(stringResource(R.string.guide_receiving_size,
                        Formatter.formatShortFileSize(context, progress.receivedBytes.toLong())))
                }
            }
            GuideStage.Preparing, GuideStage.Starting, GuideStage.SettingProxy, GuideStage.Verifying,
            GuideStage.Generating, GuideStage.Stopping, GuideStage.Complete, GuideStage.ConversionFailed -> Unit
        }
    }
}
