package com.zhelearn.garupaexport.export

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zhelearn.garupaexport.R
import com.zhelearn.garupaexport.capture.CaptureState
import com.zhelearn.garupaexport.capture.ProxyStatus
import com.zhelearn.garupaexport.catalog.CatalogState
import com.zhelearn.garupaexport.profile.ExportedProfile
import com.zhelearn.garupaexport.profile.ProfileState
import com.zhelearn.garupaexport.shizuku.ShizukuStatus
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportScreen(
    state: ExportUiState,
    snackbarHostState: SnackbarHostState,
    focusRequest: Int,
    onStartProxy: () -> Unit,
    onStopProxy: (manuallyCleared: Boolean) -> Unit,
    onVerifyProxy: () -> Unit,
    onProxySettings: () -> Unit,
    onCopyProxyHost: () -> Unit,
    onAuthorizeShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onNotificationSettings: () -> Unit,
    onRetry: () -> Unit,
    onRefreshAndRetry: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onCopy: (ExportedProfile) -> Unit,
    onSave: (ExportedProfile) -> Unit,
    onShare: (ExportedProfile) -> Unit,
) {
    var settings by rememberSaveable { mutableStateOf(false) }
    var manualProxy by rememberSaveable(state.capture.sessionId) { mutableStateOf(false) }
    var warnings by rememberSaveable { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val settingsListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val warningsListState = androidx.compose.foundation.lazy.rememberLazyListState()
    BackHandler(enabled = settings || warnings) { settings = false; warnings = false }
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            settings = false
            warnings = false
            listState.animateScrollToItem(0)
        }
    }
    LaunchedEffect(state.profile) {
        if (state.profile is ProfileState.Ready || state.profile is ProfileState.Failed) {
            listState.animateScrollToItem(0)
        }
        if (state.profile !is ProfileState.Ready) warnings = false
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (settings) R.string.settings else if (warnings)
                    R.string.profile_warning_details else R.string.app_name)) },
                navigationIcon = {
                    if (settings || warnings) IconButton(onClick = { settings = false; warnings = false }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (!settings && !warnings) TextButton(onClick = { settings = true }) { Text(stringResource(R.string.settings)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = if (settings) settingsListState else if (warnings) warningsListState else listState,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when {
                    settings -> {
                        item { CatalogSection(state.catalog, onRefreshCatalog) }
                        item { HorizontalDivider() }
                        item { ShizukuSection(state.shizuku, onAuthorizeShizuku, onOpenShizuku) }
                        item { HorizontalDivider() }
                        item {
                            Text(stringResource(R.string.notifications), style = MaterialTheme.typography.titleMedium)
                            ListItem(headlineContent = { Text(stringResource(if (state.notificationsEnabled)
                                R.string.enabled else R.string.disabled)) },
                                trailingContent = { TextButton(onClick = onNotificationSettings) { Text(stringResource(R.string.settings)) } })
                        }
                        if (state.capture.status == ProxyStatus.Running) item {
                            Text(stringResource(R.string.proxy_status), style = MaterialTheme.typography.titleMedium)
                            ListItem(headlineContent = { Text(stringResource(if (state.capture.proxyVerified)
                                R.string.proxy_connected else R.string.proxy_unverified)) })
                            state.capture.verificationError?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            TextButton(onClick = onVerifyProxy) { Text(stringResource(R.string.guide_verify_again)) }
                        }
                    }
                    warnings -> {
                        val profile = (state.profile as? ProfileState.Ready)?.profile
                        profile?.warnings?.forEach { warning -> item { SelectionContainer { Text(warning) } } }
                    }
                    else -> {
                        if (state.profile is ProfileState.Ready || state.profile is ProfileState.Failed) {
                            item(key = "profile") {
                                ProfileSection(state.profile, state.catalog, onRetry, onRefreshAndRetry,
                                    { warnings = true }, onCopy, onSave, onShare)
                            }
                        } else item(key = "guide") {
                            CaptureGuide(state, onStartProxy, onVerifyProxy, onProxySettings,
                                onCopyProxyHost, onAuthorizeShizuku, onOpenShizuku,
                                manualProxy, { manualProxy = it })
                        }
                        if (state.capture.status != ProxyStatus.Stopped) item(key = "session") {
                            SessionControls(state.capture, onStopProxy, onProxySettings)
                        } else if (state.profile is ProfileState.Ready || state.profile is ProfileState.Failed) item {
                            OutlinedButton(onClick = onStartProxy, enabled = state.canStart) {
                                Text(stringResource(R.string.guide_start_another))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogSection(state: CatalogState, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.catalog_title), style = MaterialTheme.typography.titleMedium)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        ListItem(headlineContent = { Text(stringResource(R.string.status)) }, supportingContent = {
            Text(stringResource(when {
                state.loading -> R.string.catalog_loading
                state.upToDate -> R.string.catalog_up_to_date
                state.available -> R.string.catalog_cached
                else -> R.string.catalog_empty
            }))
        })
        state.cache?.let {
            ListItem(headlineContent = { Text(stringResource(R.string.catalog_count, it.cardCount)) },
                supportingContent = { Text("${stringResource(R.string.catalog_updated)}：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.updatedAtMillis))}") })
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onRefresh, enabled = !state.loading) {
            Text(stringResource(if (state.available) R.string.catalog_refresh else R.string.catalog_download))
        }
    }
}

@Composable
private fun ShizukuSection(status: ShizukuStatus, onAuthorize: () -> Unit, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.shizuku_title), style = MaterialTheme.typography.titleMedium)
        ListItem(headlineContent = { Text(stringResource(when (status) {
            ShizukuStatus.NotInstalled -> R.string.shizuku_not_installed
            ShizukuStatus.NotRunning -> R.string.shizuku_not_running
            ShizukuStatus.Unauthorized -> R.string.shizuku_unauthorized
            ShizukuStatus.Authorized -> R.string.shizuku_authorized
        })) })
        if (status == ShizukuStatus.Unauthorized) TextButton(onClick = onAuthorize) { Text(stringResource(R.string.shizuku_authorize)) }
        if (status != ShizukuStatus.NotInstalled) TextButton(onClick = onOpen) { Text(stringResource(R.string.shizuku_open)) }
    }
}

@Composable
private fun SessionControls(state: CaptureState, onStop: (Boolean) -> Unit, onSettings: () -> Unit) {
    var confirmStop by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    val stopping = state.status == ProxyStatus.Stopping
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.clearFailed) {
            Text(state.error ?: stringResource(R.string.proxy_clear_failed), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { onStop(false) }, enabled = !stopping) { Text(stringResource(R.string.retry_finish)) }
        }
        TextButton(onClick = {
            if (state.clearFailed || (state.status == ProxyStatus.Running && !state.automaticProxy)) confirmStop = true
            else onStop(false)
        }, enabled = !stopping) {
            Text(stringResource(if (stopping) R.string.proxy_stopping else if (state.status == ProxyStatus.Starting)
                R.string.cancel_capture else R.string.finish_capture))
        }
    }
    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text(stringResource(R.string.finish_capture)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.guide_stop_confirmation))
                TextButton(onClick = onSettings) { Text(stringResource(R.string.guide_wifi_settings)) }
                if (state.environment?.usesLocalProxy == true) Text(stringResource(R.string.proxy_still_configured))
            }
        },
        confirmButton = {
            TextButton(onClick = { confirmStop = false; onStop(true) }, enabled = state.environment?.usesLocalProxy != true) {
                Text(stringResource(R.string.guide_confirm_stop))
            }
        },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ProfileSection(
    state: ProfileState,
    catalog: CatalogState,
    onRetry: () -> Unit,
    onRefreshAndRetry: () -> Unit,
    onWarnings: () -> Unit,
    onCopy: (ExportedProfile) -> Unit,
    onSave: (ExportedProfile) -> Unit,
    onShare: (ExportedProfile) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (state) {
            is ProfileState.Failed -> {
                Text(stringResource(R.string.profile_failed), style = MaterialTheme.typography.headlineSmall)
                SelectionContainer { Text(state.message, color = MaterialTheme.colorScheme.error) }
                catalog.error?.takeIf { it != state.message }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = if (state.refreshSuggested) onRefreshAndRetry else onRetry, enabled = !catalog.loading) {
                    Text(stringResource(if (state.refreshSuggested) R.string.refresh_and_retry else R.string.retry_export))
                }
                TextButton(onClick = if (state.refreshSuggested) onRetry else onRefreshAndRetry, enabled = !catalog.loading) {
                    Text(stringResource(if (state.refreshSuggested) R.string.retry_export else R.string.refresh_and_retry))
                }
                if (catalog.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is ProfileState.Ready -> {
                val profile = state.profile
                Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineSmall)
                ListItem(headlineContent = { SelectionContainer { Text(profile.name) } },
                    supportingContent = { Text(stringResource(R.string.profile_summary, profile.cardCount, profile.areaItemCount, profile.potentialCount)) })
                Button(onClick = { onCopy(profile) }) { Text(stringResource(R.string.copy_profile)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSave(profile) }) { Text(stringResource(R.string.save_profile)) }
                    TextButton(onClick = { onShare(profile) }) { Text(stringResource(R.string.share_profile)) }
                }
                if (profile.warnings.isNotEmpty()) ListItem(
                    headlineContent = { Text(stringResource(R.string.profile_warnings, profile.warnings.size)) },
                    trailingContent = { TextButton(onClick = onWarnings) { Text(stringResource(R.string.view)) } },
                )
            }
            ProfileState.Parsing, ProfileState.Empty -> Unit
        }
    }
}
