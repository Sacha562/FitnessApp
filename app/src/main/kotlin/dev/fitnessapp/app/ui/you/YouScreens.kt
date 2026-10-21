package dev.fitnessapp.app.ui.you

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.BuildConfig
import dev.fitnessapp.app.ui.AppViewModel
import dev.fitnessapp.app.ui.auth.mask
import dev.fitnessapp.app.ui.common.DockedBar
import dev.fitnessapp.app.ui.common.FitScaffold
import dev.fitnessapp.app.ui.common.MoreSection
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.common.TonalButton
import dev.fitnessapp.app.ui.equipment.EquipmentContent
import dev.fitnessapp.app.ui.equipment.EquipmentViewModel
import dev.fitnessapp.app.ui.reliability.ReliabilityContent
import dev.fitnessapp.app.ui.today.WeighInDialog
import dev.fitnessapp.app.ui.today.pendingLine
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.data.export.ExportRepository
import dev.fitnessapp.data.sync.PendingStatus
import dev.fitnessapp.data.sync.SyncState
import io.github.jan.supabase.auth.auth
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun YouRoute(
    c: AppContainer,
    appVm: AppViewModel,
    modifier: Modifier,
    onDataSync: () -> Unit,
    onEquipment: () -> Unit,
    onReliability: () -> Unit,
    onHistory: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val history by remember { c.history.observeHistory() }.collectAsState(initial = emptyList())
    val syncState by c.sync.state.collectAsState()
    val outbox by c.sync.observeOutbox().collectAsState(initial = 0 to null)
    val dead by c.sync.observeDeadLetters().collectAsState(initial = 0)
    val pending = PendingStatus.of(syncState, outbox.first, outbox.second, System.currentTimeMillis(), c.platformConfig.sync)
    var weighIn by remember { mutableStateOf(false) }
    var signOutBlocked by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("You", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        val thisWeek = history.firstOrNull()?.session?.iso_week
        Tile(spacing = 0.dp, padding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
            NavRow(
                "History",
                thisWeek?.let { w -> "Week ${w.substringAfter("-W").trimStart('0')} · ${history.count { it.session.iso_week == w }} sessions" } ?: "No sessions yet",
                onHistory,
            )
        }
        Tile(spacing = 0.dp, padding = PaddingValues(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 6.dp)) {
            TileLabel("Settings", Modifier.padding(start = 10.dp, bottom = 4.dp))
            NavRow("Data and sync", when {
                dead > 0 -> "$dead could not be uploaded"
                pending is PendingStatus.Offline -> "Offline · ${pending.count} saved on this phone"
                pending is PendingStatus.Long -> "Upload retrying · oldest ${pending.oldestAgeH} h"
                else -> "Everything uploaded"
            }, onDataSync)
            TileDivider(Modifier.padding(horizontal = 10.dp))
            NavRow("Equipment", "Your gym and what it has", onEquipment)
            TileDivider(Modifier.padding(horizontal = 10.dp))
            NavRow("Rest timer reliability", "Permissions, phone settings, alarm test", onReliability)
            TileDivider(Modifier.padding(horizontal = 10.dp))
            NavRow("Log bodyweight", "Used for pull-ups, dips and assisted machines", { weighIn = true })
        }
        Tile(spacing = 8.dp) {
            TileLabel("Account")
            Text("Signed in as " + ((c.supabase.auth.currentUserOrNull()?.email ?: c.account.email())?.let(::mask) ?: "—"), style = MaterialTheme.typography.bodyMedium)
            TonalButton("Sign out", {
                scope.launch { if (appVm.canSignOut()) appVm.signOut() else signOutBlocked = true }
            })
        }
        Tile(spacing = 4.dp) {
            Text("This app does not give medical advice.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            MoreSection("About") {
                Text("App ${BuildConfig.VERSION_NAME} · engine ${EngineVersion.ENGINE_VERSION} · catalog ${c.catalog.version}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (weighIn) WeighInDialog(onDismiss = { weighIn = false }) { kg ->
        weighIn = false
        scope.launch { c.bodyweight.logWeighIn(kg) }
    }
    if (signOutBlocked) AlertDialog(
        onDismissRequest = { signOutBlocked = false },
        title = { Text("Not uploaded yet") },
        text = { Text("Some changes aren't uploaded yet and would be lost. Sync first.") },
        confirmButton = { TextButton(onClick = { signOutBlocked = false; c.sync.syncNow() }) { Text("Sync now") } },
        dismissButton = { TextButton(onClick = { signOutBlocked = false }) { Text("Cancel") } },
    )
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Fit.Muted)
    }
}

@Composable
private fun SubScreen(title: String, onBack: () -> Unit, bottom: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    FitScaffold(title, onBack, bottomBar = { bottom?.invoke() }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/** You > Data and sync (UX 15.11, ANDROID.md 9.7 and 20.4, D17). [autoExport]: opened from N-EXPORT, starts the monthly export. */
@Composable
fun DataSyncRoute(c: AppContainer, onBack: () -> Unit, autoExport: Boolean = false) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lastExport by remember { c.exports.observeLastFull() }.collectAsState(initial = null)
    var exportKind by remember { mutableStateOf(if (autoExport) "monthly_full" else "manual_full") }
    var exporting by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var reminder by remember { mutableStateOf(c.settings.exportReminderEnabled) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exporting = true
        scope.launch {
            exportMessage = runCatching {
                withContext(Dispatchers.IO) {
                    val built = c.exports.build(exportKind)
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "no output stream" }.use { it.write(built.bytes) }
                    c.exports.record(built, "device_storage")
                    "Export saved (${(built.bytes.size + 1023) / 1024} KB)."
                }
            }.getOrElse { "Export failed. Nothing was saved; try again." }
            exporting = false
        }
    }
    fun startExport(kind: String) {
        exportKind = kind
        launcher.launch(ExportRepository.fileName(LocalDate.now(ZoneId.of("Europe/Amsterdam")).toString()))
    }
    LaunchedEffect(autoExport) { if (autoExport) startExport("monthly_full") }
    val state by c.sync.state.collectAsState()
    val outbox by c.sync.observeOutbox().collectAsState(initial = 0 to null)
    val dead by c.sync.observeDeadLetters().collectAsState(initial = 0)
    val pending = PendingStatus.of(state, outbox.first, outbox.second, System.currentTimeMillis(), c.platformConfig.sync)
    var confirmRepush by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    SubScreen("Data and sync", onBack) {
        Tile(spacing = 8.dp) {
            TileLabel("Sync")
            Text(describe(state), style = MaterialTheme.typography.titleLarge)
            Text(
                (pendingLine(pending) ?: "All changes uploaded") + if (dead > 0) " · $dead could not be uploaded" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            PrimaryButton("Sync now", {
                scope.launch {
                    c.fgsEvent("sync_now_tapped", mapOf("outbox_depth" to outbox.first))
                    c.sync.syncNow()
                }
            }, Modifier.fillMaxWidth().padding(top = 4.dp))
            if (dead > 0) TonalButton("Retry failed uploads", { scope.launch { c.sync.retryDeadLetters() } }, Modifier.fillMaxWidth())
        }
        Tile(spacing = 8.dp) {
            TileLabel("Backup")
            Text("The export is your offline copy; signing in after a reinstall restores the rest.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            val last = lastExport?.period_end
            Text(
                if (last == null) "No export yet" else lastExportLine(last, System.currentTimeMillis()),
                style = MaterialTheme.typography.titleMedium,
            )
            TonalButton(if (exporting) "Exporting..." else "Export all data", { startExport("manual_full") }, Modifier.fillMaxWidth(), enabled = !exporting)
            exportMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Monthly reminder", style = MaterialTheme.typography.titleMedium)
                    Text("A notification when the last export is over ${c.platformConfig.exportReminderDays} days old.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                }
                Switch(checked = reminder, onCheckedChange = { on ->
                    val from = reminder
                    reminder = on
                    c.settings.exportReminderEnabled = on
                    scope.launch { c.fgsEvent("settings_changed", mapOf("key" to "export_reminder", "from" to from, "to" to on)) }
                })
            }
            Text("Storage on this phone: " + phoneStorage(context), style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
        }
        Tile(spacing = 8.dp) {
            MoreSection("Recovery") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Only if the server lost data: upload everything on this phone again.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                    TonalButton("Upload everything again", { confirmRepush = true }, Modifier.fillMaxWidth())
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    if (confirmRepush) AlertDialog(
        onDismissRequest = { confirmRepush = false },
        title = { Text("Upload everything again?") },
        text = { Text("Safe to do: the server keeps the newest version of everything.") },
        confirmButton = {
            TextButton(onClick = {
                confirmRepush = false
                scope.launch { message = "${c.sync.rePushAll()} changes queued for upload." }
            }) { Text("Upload") }
        },
        dismissButton = { TextButton(onClick = { confirmRepush = false }) { Text("Cancel") } },
    )
}

/** The database file with its journal files and the pre-migration copies (ANDROID.md 8.5), in MB. */
private fun phoneStorage(context: Context): String {
    val db = context.getDatabasePath(dev.fitnessapp.data.db.FitnessDatabase.FILE)
    val files = listOf("", "-wal", "-shm").map { java.io.File(db.path + it) } +
        (java.io.File(context.filesDir, "db-backups").listFiles()?.toList() ?: emptyList())
    val bytes = files.filter { it.exists() }.sumOf { it.length() }
    return java.math.BigDecimal(bytes / 1_048_576.0).setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString() + " MB"
}

/** "Last export: Sun 11 Oct 2026 (today)", counted in calendar days of the app's zone. */
internal fun lastExportLine(lastMs: Long, nowMs: Long): String {
    val zone = ZoneId.of("Europe/Amsterdam")
    val day = Instant.ofEpochMilli(lastMs).atZone(zone)
    val ago = when (val d = ChronoUnit.DAYS.between(day.toLocalDate(), Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate())) {
        0L -> "today"
        1L -> "yesterday"
        else -> "$d days ago"
    }
    return "Last export: " + DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH).format(day) + " ($ago)"
}

private fun describe(s: SyncState): String = when (s) {
    SyncState.Idle -> "Not synced yet"
    SyncState.Running -> "Syncing…"
    is SyncState.Synced -> "Synced at " + DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(s.atMs).atZone(ZoneId.of("Europe/Amsterdam")))
    SyncState.Offline -> "Offline, will retry"
    is SyncState.Unreachable -> "Can't reach the server; changes are kept on this phone."
    SyncState.NeedsSignIn -> "Sign in again to upload"
    SyncState.NotAllowed -> "This account can't use this app yet"
    SyncState.SchemaNotExposed -> "Server needs an update."
    is SyncState.SchemaDrift -> "Server needs an update."
    SyncState.ServerReadOnly -> "Server storage is full; your data is safe on this phone."
}

@Composable
fun EquipmentRoute(c: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: EquipmentViewModel = viewModel(factory = viewModelFactory { initializer { EquipmentViewModel(c) } })
    val state by vm.state.collectAsState()
    SubScreen("Equipment", onBack, bottom = {
        DockedBar {
            PrimaryButton("Save equipment", {
                context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().putBoolean("equipment_later", false).apply()
                vm.save(confirmed = true, done = onBack)
            }, Modifier.fillMaxWidth())
        }
    }) {
        state?.let { EquipmentContent(it, vm) }
    }
}

@Composable
fun ReliabilityRoute(c: AppContainer, onBack: () -> Unit) {
    SubScreen("Rest timer reliability", onBack) { ReliabilityContent(c, showStats = true) }
}
