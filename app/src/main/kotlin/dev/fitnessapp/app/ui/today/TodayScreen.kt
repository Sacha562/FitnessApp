package dev.fitnessapp.app.ui.today

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.session.SessionService
import dev.fitnessapp.app.ui.common.SectionLabel
import dev.fitnessapp.app.ui.theme.AdvisoryAmber
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.sync.PendingStatus
import dev.fitnessapp.data.sync.SyncState
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Today in M1a (D22): start an empty session or resume the open one, the bodyweight line with a weigh-in
 * prompt when stale, the status row and recent sessions. The recommendation card arrives with M4.
 */
@Composable
fun TodayRoute(c: AppContainer, modifier: Modifier, onOpenSession: (id: String, resumed: Boolean) -> Unit, onOpenSync: () -> Unit, onOpenPast: (String) -> Unit = {}, onHistory: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by c.sessionRepository.observeActive().collectAsState(initial = null)
    val recent by c.sessionRepository.observeRecent(8).collectAsState(initial = emptyList())
    val latestBw by c.bodyweight.observeLatest().collectAsState(initial = null)
    val outbox by c.sync.observeOutbox().collectAsState(initial = 0 to null)
    val dead by c.sync.observeDeadLetters().collectAsState(initial = 0)
    val sync by c.sync.state.collectAsState()
    val profile by remember { c.profiles.observe() }.collectAsState(initial = null)
    var weighIn by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    val zone = ZoneId.of("Europe/Amsterdam")
    val today = LocalDate.now(zone)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)), style = MaterialTheme.typography.labelLarge)
        Text(profile?.display_name?.let { "Hi $it" } ?: "Today", style = MaterialTheme.typography.headlineMedium)

        StatusRow(c, PendingStatus.of(sync, outbox.first, outbox.second, System.currentTimeMillis(), c.platformConfig.sync), dead, sync, onOpenSync)

        val a = active
        if (a != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Session in progress", style = MaterialTheme.typography.titleMedium)
                    val mins = a.started_at?.let { (System.currentTimeMillis() - it) / 60_000 } ?: 0
                    Text("Started $mins min ago", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        SessionService.start(context)
                        onOpenSession(a.id, true)
                    }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Resume") }
                }
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Train today", style = MaterialTheme.typography.titleMedium)
                    Text("Start a session and add exercises as you go. Each set starts from what you did last time.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        if (starting) return@Button
                        starting = true
                        scope.launch {
                            val id = c.sessionRepository.start(c.params)
                            SessionService.start(context) // user action while the activity is resumed (ANDROID.md 11.3)
                            starting = false
                            onOpenSession(id, false)
                        }
                    }, enabled = !starting, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Start session") }
                }
            }
        }

        // Bodyweight (C32): the snapshot needs a weigh-in; stale after 14 days.
        val bw = latestBw
        val days = bw?.let { ChronoUnit.DAYS.between(LocalDate.parse(it.local_date), today) }
        Row(Modifier.fillMaxWidth().clickable { weighIn = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bodyweight", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        bw?.value_num == null -> "No weigh-in yet. Needed for pull-ups, dips and assisted work."
                        days != null && days > c.params.fork().int("bodyweight.stale_days") -> Numbers.kg(Scaled.toDouble(bw.value_num!!, 3)) + " · $days days ago, weigh in again"
                        else -> Numbers.kg(Scaled.toDouble(bw.value_num!!, 3)) + if (days == 0L) " · today" else " · $days days ago"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { weighIn = true }) { Text("Log weight") }
        }

        if (recent.isNotEmpty()) {
            SectionLabel("Recent sessions")
            recent.forEach { s ->
                val date = LocalDate.parse(s.local_date).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
                val dur = if (s.started_at != null && s.finished_at != null) " · " + ((s.finished_at!! - s.started_at!!) / 60_000) + " min" else ""
                Text(
                    "$date$dur" + if (s.status == "abandoned") " · abandoned" else "", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenPast(s.id) }.padding(vertical = 12.dp),
                )
            }
            TextButton(onClick = onHistory) { Text("All sessions") }
        }
    }

    if (weighIn) WeighInDialog(onDismiss = { weighIn = false }) { kg ->
        weighIn = false
        scope.launch { c.bodyweight.logWeighIn(kg) }
    }
}

/** One status line at most (ANDROID.md 7.9): reliability problems first, then sync. */
@Composable
private fun StatusRow(c: AppContainer, pending: PendingStatus, dead: Int, sync: SyncState, onOpenSync: () -> Unit) {
    val context = LocalContext.current
    val problems = buildList {
        if (!c.device.notificationsAllowed()) add("Notifications are off: the rest alarm cannot show")
        if (!c.device.canScheduleExactAlarms()) add("Exact alarms are off: the rest alarm may be late")
        if (context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).getBoolean("equipment_later", false)) add("Equipment not confirmed yet")
    }
    val syncText = when {
        dead > 0 -> "$dead changes could not be uploaded"
        sync is SyncState.NeedsSignIn -> "Sign in again to upload your data"
        sync is SyncState.NotAllowed -> "This account is not enabled for upload"
        sync is SyncState.SchemaNotExposed -> "Server not configured (fitness schema not exposed)"
        sync is SyncState.SchemaDrift -> "Server update needed"
        sync is SyncState.ServerReadOnly -> "Server storage is full (read-only). Your data is safe on this phone."
        sync is SyncState.Unreachable -> "Server unreachable, the project may be paused"
        else -> pendingLine(pending)
    }
    val line = problems.firstOrNull() ?: syncText ?: return
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpenSync).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (problems.isNotEmpty() || dead > 0) Icons.Filled.Warning else Icons.Filled.CloudOff, null, tint = AdvisoryAmber)
        Text(line, style = MaterialTheme.typography.bodyMedium)
    }
}

/** UX 7.1 wording; null when nothing is worth showing. */
fun pendingLine(p: PendingStatus): String? = when (p) {
    PendingStatus.None -> null
    is PendingStatus.Offline -> "Offline. ${p.count} changes are saved on this phone and will upload automatically."
    is PendingStatus.Long -> "Upload is retrying. Oldest change waiting: ${p.oldestAgeH} h."
}

@Composable
fun WeighInDialog(onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf("") }
    val kg = text.replace(',', '.').toDoubleOrNull()?.takeIf { it in 20.0..300.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log weight") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text("Bodyweight (kg)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = { TextButton(onClick = { kg?.let(onSave) }, enabled = kg != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
