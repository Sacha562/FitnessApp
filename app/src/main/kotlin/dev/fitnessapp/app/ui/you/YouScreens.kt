package dev.fitnessapp.app.ui.you

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.BuildConfig
import dev.fitnessapp.app.ui.AppViewModel
import dev.fitnessapp.app.ui.auth.mask
import dev.fitnessapp.app.ui.common.SectionLabel
import dev.fitnessapp.app.ui.equipment.EquipmentContent
import dev.fitnessapp.app.ui.equipment.EquipmentViewModel
import dev.fitnessapp.app.ui.reliability.ReliabilityContent
import dev.fitnessapp.app.ui.today.WeighInDialog
import dev.fitnessapp.app.ui.today.pendingLine
import dev.fitnessapp.data.sync.PendingStatus
import dev.fitnessapp.data.sync.SyncState
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun YouRoute(
    c: AppContainer,
    appVm: AppViewModel,
    modifier: Modifier,
    onDataSync: () -> Unit,
    onEquipment: () -> Unit,
    onReliability: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val syncState by c.sync.state.collectAsState()
    val outbox by c.sync.observeOutbox().collectAsState(initial = 0 to null)
    val dead by c.sync.observeDeadLetters().collectAsState(initial = 0)
    val pending = PendingStatus.of(syncState, outbox.first, outbox.second, System.currentTimeMillis(), c.platformConfig.sync)
    var weighIn by remember { mutableStateOf(false) }
    var signOutBlocked by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("You", style = MaterialTheme.typography.headlineMedium)
        SectionLabel("Settings")
        NavRow("Data and sync", when {
            dead > 0 -> "$dead could not be uploaded"
            pending is PendingStatus.Offline -> "Offline · ${pending.count} saved on this phone"
            pending is PendingStatus.Long -> "Upload retrying · oldest ${pending.oldestAgeH} h"
            else -> "Everything uploaded"
        }, onDataSync)
        NavRow("Equipment", "Your gym, plates, dumbbells, machines, dip belt", onEquipment)
        NavRow("Rest timer reliability", "Permissions, phone settings, alarm test", onReliability)
        NavRow("Log bodyweight", "Used for pull-ups, dips and assisted machines", { weighIn = true })
        SectionLabel("Account")
        Text("Signed in as " + ((c.supabase.auth.currentUserOrNull()?.email ?: c.account.email())?.let(::mask) ?: "—"), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = {
            scope.launch { if (appVm.canSignOut()) appVm.signOut() else signOutBlocked = true }
        }, modifier = Modifier.padding(top = 8.dp)) { Text("Sign out") }
        SectionLabel("About")
        Text("App ${BuildConfig.VERSION_NAME} · engine ${EngineVersion.ENGINE_VERSION} · catalog ${c.catalog.version}", style = MaterialTheme.typography.bodySmall)
        Text("This app does not give medical advice. If something hurts, stop and get it checked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (weighIn) WeighInDialog(onDismiss = { weighIn = false }) { kg ->
        weighIn = false
        scope.launch { c.bodyweight.logWeighIn(kg) }
    }
    if (signOutBlocked) AlertDialog(
        onDismissRequest = { signOutBlocked = false },
        title = { Text("Not uploaded yet") },
        text = { Text("Some changes are not on the server yet. Signing out now would lose them. Sync first (You > Data and sync).") },
        confirmButton = { TextButton(onClick = { signOutBlocked = false; c.sync.syncNow() }) { Text("Sync now") } },
        dismissButton = { TextButton(onClick = { signOutBlocked = false }) { Text("Cancel") } },
    )
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubScreen(title: String, onBack: () -> Unit, bottom: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        },
        bottomBar = { bottom?.invoke() },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/** You > Data and sync (ANDROID.md 9.7, D17). */
@Composable
fun DataSyncRoute(c: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state by c.sync.state.collectAsState()
    val outbox by c.sync.observeOutbox().collectAsState(initial = 0 to null)
    val dead by c.sync.observeDeadLetters().collectAsState(initial = 0)
    val pending = PendingStatus.of(state, outbox.first, outbox.second, System.currentTimeMillis(), c.platformConfig.sync)
    var confirmRepush by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    SubScreen("Data and sync", onBack) {
        Text(describe(state), style = MaterialTheme.typography.titleMedium)
        Text(
            (pendingLine(pending) ?: "All changes uploaded") + if (dead > 0) " · $dead could not be uploaded" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Everything is saved on this phone first and uploaded when there is a connection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { c.sync.syncNow() }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Sync now") }
        if (dead > 0) OutlinedButton(onClick = { scope.launch { c.sync.retryDeadLetters() } }, modifier = Modifier.fillMaxWidth()) { Text("Retry failed uploads") }
        SectionLabel("Recovery")
        Text("If the server lost data (for example after a restore), upload every row on this phone again. Rows the server already has are left as they are.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { confirmRepush = true }, modifier = Modifier.fillMaxWidth()) { Text("Re-push all data") }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    if (confirmRepush) AlertDialog(
        onDismissRequest = { confirmRepush = false },
        title = { Text("Re-push all data?") },
        text = { Text("Every local row is queued for upload again, in order. This is safe: the server keeps the newest version of each row.") },
        confirmButton = {
            TextButton(onClick = {
                confirmRepush = false
                scope.launch { message = "${c.sync.rePushAll()} rows queued." }
            }) { Text("Re-push") }
        },
        dismissButton = { TextButton(onClick = { confirmRepush = false }) { Text("Cancel") } },
    )
}

private fun describe(s: SyncState): String = when (s) {
    SyncState.Idle -> "Not synced yet in this run"
    SyncState.Running -> "Syncing…"
    is SyncState.Synced -> "Synced at " + DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(s.atMs).atZone(ZoneId.of("Europe/Amsterdam")))
    SyncState.Offline -> "Offline, will retry"
    is SyncState.Unreachable -> "Server unreachable, the Supabase project may be paused"
    SyncState.NeedsSignIn -> "Sign in again to upload"
    SyncState.NotAllowed -> "This account is not enabled for the fitness app"
    SyncState.SchemaNotExposed -> "Server not configured: the fitness schema is not exposed"
    is SyncState.SchemaDrift -> "Server update needed (${s.table})"
    SyncState.ServerReadOnly -> "Server storage is full (read-only). Your data is safe on this phone."
}

@Composable
fun EquipmentRoute(c: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: EquipmentViewModel = viewModel(factory = viewModelFactory { initializer { EquipmentViewModel(c) } })
    val state by vm.state.collectAsState()
    SubScreen("Equipment", onBack, bottom = {
        Button(onClick = {
            context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().putBoolean("equipment_later", false).apply()
            vm.save(confirmed = true, done = onBack)
        }, modifier = Modifier.fillMaxWidth().safeDrawingPadding().padding(16.dp).height(56.dp)) { Text("Save equipment") }
    }) {
        state?.let { EquipmentContent(it, vm) }
    }
}

@Composable
fun ReliabilityRoute(c: AppContainer, onBack: () -> Unit) {
    SubScreen("Rest timer reliability", onBack) { ReliabilityContent(c) }
}
