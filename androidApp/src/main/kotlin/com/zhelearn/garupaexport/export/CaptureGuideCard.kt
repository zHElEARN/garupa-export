package com.zhelearn.garupaexport.export

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zhelearn.garupaexport.R
import com.zhelearn.garupaexport.capture.ProxyStatus
import com.zhelearn.garupaexport.capture.guide.GuideStage
import com.zhelearn.garupaexport.shizuku.ShizukuStatus

@Composable
internal fun CaptureGuideCard(
    state: ExportUiState,
    onStart: () -> Unit,
    onVerify: () -> Unit,
    onSettings: () -> Unit,
    onCopyHost: () -> Unit,
    onAuthorizeShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val stage = state.stage
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (stage.step > 0) {
                Text(stringResource(R.string.guide_step, stage.step), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text(stringResource(stage.title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            Text(stringResource(stage.description), style = MaterialTheme.typography.bodyLarge)
            when (stage) {
                GuideStage.Welcome, GuideStage.StartFailed -> {
                    if (stage == GuideStage.StartFailed) {
                        state.capture.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    Text(stringResource(R.string.guide_notification_rationale),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onStart) {
                        Text(stringResource(if (stage == GuideStage.Welcome) R.string.guide_start else R.string.guide_start_retry))
                    }
                }
                GuideStage.Preparing, GuideStage.Starting, GuideStage.SettingProxy,
                GuideStage.Verifying, GuideStage.Generating, GuideStage.Stopping -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                GuideStage.ConfigureProxy -> {
                    if (state.capture.environment?.wifi == false) {
                        Text(stringResource(R.string.guide_wifi_required), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.guide_manual_instructions), style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.guide_host_value), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.guide_port_value), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    state.capture.verificationError?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.capture.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSettings) { Text(stringResource(R.string.guide_wifi_settings)) }
                        OutlinedButton(onClick = onVerify) { Text(stringResource(R.string.guide_verify)) }
                        TextButton(onClick = onCopyHost) { Text(stringResource(R.string.copy_proxy_host)) }
                    }
                    when (state.shizuku) {
                        ShizukuStatus.Unauthorized -> {
                            Text(stringResource(R.string.guide_shizuku_permission), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onAuthorizeShizuku) { Text(stringResource(R.string.guide_shizuku_authorize)) }
                        }
                        ShizukuStatus.NotRunning -> {
                            TextButton(onClick = onOpenShizuku) { Text(stringResource(R.string.guide_shizuku_optional)) }
                        }
                        else -> Unit
                    }
                }
                GuideStage.OpenGame -> {
                    Text(stringResource(R.string.guide_game_instructions), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onVerify) { Text(stringResource(R.string.guide_verify_again)) }
                }
                GuideStage.Receiving -> {
                    val progress = state.capture.progress
                    val total = progress?.totalBytes
                    if (progress != null && total != null) {
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
                GuideStage.Complete, GuideStage.ConversionFailed -> {
                    if (state.capture.status == ProxyStatus.Stopped) {
                        OutlinedButton(onClick = onStart) { Text(stringResource(R.string.guide_start_another)) }
                    }
                }
            }
            if (!state.notificationsEnabled && stage !in setOf(GuideStage.Welcome, GuideStage.StartFailed)) {
                Text(stringResource(R.string.guide_notifications_disabled), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.capture.captured && state.capture.verificationError != null) {
                Text(stringResource(R.string.guide_network_changed_after_capture), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
