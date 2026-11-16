package com.zhelearn.garupaexport.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
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
    onRetry: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onCopy: (ExportedProfile) -> Unit,
    onSave: (ExportedProfile) -> Unit,
    onShare: (ExportedProfile) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var details by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(focusRequest) {
        if (focusRequest > 0) listState.animateScrollToItem(0)
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }, scrollBehavior = scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "guide") {
                    CaptureGuideCard(state, onStartProxy, onVerifyProxy, onProxySettings,
                        onCopyProxyHost, onAuthorizeShizuku, onOpenShizuku)
                }
                if (state.profile != ProfileState.Empty && state.profile != ProfileState.Parsing) {
                    item(key = "profile") { ProfileSection(state.profile, state.catalog, onRetry,
                        onRefreshCatalog, onCopy, onSave, onShare) }
                }
                if (state.capture.status == ProxyStatus.Running) {
                    item(key = "controls") { SessionControls(state.capture, onStopProxy) }
                }
                item(key = "details_toggle") {
                    TextButton(onClick = { details = !details }) {
                        Text(stringResource(if (details) R.string.guide_hide_details else R.string.guide_show_details))
                    }
                }
                if (details) {
                    item(key = "catalog") { CatalogSection(state.catalog, onRefreshCatalog) }
                    item(key = "shizuku") { ShizukuSection(state.shizuku, onAuthorizeShizuku, onOpenShizuku) }
                }
            }
        }
    }
}

@Composable
private fun CatalogSection(state: CatalogState, onRefresh: () -> Unit) {
    SettingsSection(stringResource(R.string.catalog_title)) {
        val cached = state.cache
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        ValueRow(stringResource(R.string.status), stringResource(when {
            state.loading && cached != null -> R.string.catalog_refreshing
            state.loading -> R.string.catalog_loading
            state.upToDate -> R.string.catalog_up_to_date
            cached != null -> R.string.catalog_cached
            else -> R.string.catalog_empty
        }))
        if (cached != null) {
            ValueRow(stringResource(R.string.catalog_local), stringResource(R.string.catalog_count, cached.cardCount))
            ValueRow(stringResource(R.string.catalog_updated),
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(cached.updatedAtMillis)))
        }
        state.error?.let { ErrorRow(it) }
        SectionActions {
            TextButton(onClick = onRefresh, enabled = !state.loading) {
                Text(stringResource(if (cached == null) R.string.catalog_download else R.string.catalog_refresh))
            }
        }
    }
}

@Composable
private fun ShizukuSection(status: ShizukuStatus, onAuthorize: () -> Unit, onOpen: () -> Unit) {
    SettingsSection(stringResource(R.string.shizuku_title)) {
        ValueRow(stringResource(R.string.status), stringResource(when (status) {
            ShizukuStatus.NotInstalled -> R.string.shizuku_not_installed
            ShizukuStatus.NotRunning -> R.string.shizuku_not_running
            ShizukuStatus.Unauthorized -> R.string.shizuku_unauthorized
            ShizukuStatus.Authorized -> R.string.shizuku_authorized
        }))
        if (status == ShizukuStatus.Unauthorized || status == ShizukuStatus.NotRunning) {
            SectionActions {
                if (status == ShizukuStatus.Unauthorized) {
                    TextButton(onClick = onAuthorize) { Text(stringResource(R.string.shizuku_authorize)) }
                }
                TextButton(onClick = onOpen) { Text(stringResource(R.string.shizuku_open)) }
            }
        }
    }
}

@Composable
private fun SessionControls(state: CaptureState, onStop: (Boolean) -> Unit) {
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.guide_session_running), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            if (state.automaticProxy && !state.clearFailed) onStop(false) else confirmStop = true
        }) { Text(stringResource(R.string.stop_proxy)) }
        if (state.clearFailed) ErrorRow(state.error ?: stringResource(R.string.proxy_clear_failed))
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.confirm_stop_proxy)) },
            text = { Text(stringResource(R.string.guide_stop_confirmation)) },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; onStop(true) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.guide_confirm_stop))
                }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ProfileSection(
    state: ProfileState,
    catalog: CatalogState,
    onRetry: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onCopy: (ExportedProfile) -> Unit,
    onSave: (ExportedProfile) -> Unit,
    onShare: (ExportedProfile) -> Unit,
) {
    SettingsSection(stringResource(R.string.profile_title)) {
        when (state) {
            ProfileState.Empty -> ValueRow(stringResource(R.string.status), stringResource(R.string.profile_empty))
            ProfileState.Parsing -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                ValueRow(stringResource(R.string.status), stringResource(R.string.profile_loading))
            }
            is ProfileState.Failed -> {
                ValueRow(stringResource(R.string.status), stringResource(R.string.profile_failed))
                ErrorRow(state.message)
                catalog.error?.let { ErrorRow(it) }
                SectionActions {
                    TextButton(onClick = onRefreshCatalog, enabled = !catalog.loading) { Text(stringResource(R.string.catalog_refresh)) }
                    TextButton(onClick = onRetry, enabled = !catalog.loading) { Text(stringResource(R.string.retry_export)) }
                }
                if (catalog.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is ProfileState.Ready -> {
                val profile = state.profile
                ValueRow(stringResource(R.string.status), stringResource(R.string.profile_ready))
                ListItem(
                    headlineContent = { SelectionContainer { Text(profile.name) } },
                    supportingContent = { Text(stringResource(R.string.profile_summary, profile.cardCount, profile.areaItemCount, profile.potentialCount)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                if (profile.warnings.isNotEmpty()) {
                    Text(stringResource(R.string.profile_warnings, profile.warnings.size),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    profile.warnings.forEach { warning ->
                        SelectionContainer {
                            Text(warning, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
                SectionActions {
                    TextButton(onClick = { onCopy(profile) }) { Text(stringResource(R.string.copy_profile)) }
                    TextButton(onClick = { onSave(profile) }) { Text(stringResource(R.string.save_profile)) }
                    TextButton(onClick = { onShare(profile) }) { Text(stringResource(R.string.share_profile)) }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp))
        Card(modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { SelectionContainer { Text(value) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun ErrorRow(message: String) {
    SelectionContainer {
        Text(message, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun SectionActions(content: @Composable () -> Unit) {
    FlowRow(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
