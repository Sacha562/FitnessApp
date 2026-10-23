package dev.fitnessapp.app.ui.today

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.session.SessionService
import dev.fitnessapp.app.ui.common.Banner
import dev.fitnessapp.app.ui.common.BannerKind
import dev.fitnessapp.app.ui.common.FitTextField
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.Pill
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import dev.fitnessapp.data.sync.PendingStatus
import dev.fitnessapp.data.sync.SyncState
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Today in M1 (D22): start an empty session or resume the open one, this week's strip, the bodyweight tile with a
 * weigh-in prompt when stale, the status banner and recent sessions, laid out as Bento tiles (D31). The
 * recommendation card arrives with M4.
 */
@Composable
fun TodayRoute(
    c: AppContainer, modifier: Modifier, onOpenSession: (id: String, resumed: Boolean) -> Unit, onOpenSync: () -> Unit, onOpenPast: (String) -> Unit = {},
    onHistory: () -> Unit = {}, onOpenTest: (enrollmentId: String) -> Unit = {},
    onOpenReliability: () -> Unit = {}, onOpenEquipment: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by c.sessionRepository.observeActive().collectAsState(initial = null)
    val recent by c.sessionRepository.observeRecent(8).collectAsState(initial = emptyList())
    val latestBw by c.bodyweight.observeLatest().collectAsState(initial = null)
    val outbox by c.sync.observeOutbox().collectAsState(initial = 0 to null)
    val dead by c.sync.observeDeadLetters().collectAsState(initial = 0)
    val sync by c.sync.state.collectAsState()
    var weighIn by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    val zone = ZoneId.of("Europe/Amsterdam")
    val today = LocalDate.now(zone)

    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(
                    today.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)) + " · Week " + today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
                    style = MaterialTheme.typography.titleSmall, color = Fit.Muted,
                )
                Text(
                    today.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }

            WeekStrip(today, recent)

            StatusRow(c, PendingStatus.of(sync, outbox.first, outbox.second, System.currentTimeMillis(), c.platformConfig.sync), dead, sync, onOpenSync, onOpenReliability, onOpenEquipment)

            // The challenge's session of the day (UX 9, CHALLENGE_ENGINE 7.6); hidden without an enrollment.
            TodayChallengeTile(c, sessionActive = active != null, onOpenSession = { onOpenSession(it, false) }, onOpenTest = onOpenTest)

            // The open session's tile; without one, the Start button below says it all (D35).
            active?.let { a ->
                Tile(padding = androidx.compose.foundation.layout.PaddingValues(18.dp), spacing = 6.dp) {
                    val mins = a.started_at?.let { (System.currentTimeMillis() - it) / 60_000 } ?: 0
                    Pill("Started $mins min ago")
                    Text("Session in progress", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 4.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BodyweightTile(c, latestBw?.value_num?.let { Scaled.toDouble(it, 3) }, latestBw?.local_date, today, Modifier.weight(1f)) { weighIn = true }
                WeekTile(recent, today, Modifier.weight(1f))
            }

            if (recent.isNotEmpty()) {
                Tile(spacing = 0.dp, padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
                    TileLabel("Recent sessions", Modifier.padding(bottom = 4.dp))
                    recent.forEachIndexed { i, s ->
                        if (i > 0) TileDivider()
                        RecentRow(s) { onOpenPast(s.id) }
                    }
                    LinkButton("All sessions", onHistory, Modifier.padding(top = 2.dp))
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val a = active
            if (a != null) {
                PrimaryButton("Resume session", onClick = {
                    SessionService.start(context)
                    onOpenSession(a.id, true)
                }, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow)
            } else {
                PrimaryButton("Start session", onClick = {
                    if (starting) return@PrimaryButton
                    starting = true
                    scope.launch {
                        val id = c.sessionRepository.start(c.params)
                        SessionService.start(context) // user action while the activity is resumed (ANDROID.md 11.3)
                        starting = false
                        onOpenSession(id, false)
                    }
                }, enabled = !starting, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow)
            }
        }
    }

    if (weighIn) WeighInDialog(onDismiss = { weighIn = false }) { kg ->
        weighIn = false
        scope.launch { c.bodyweight.logWeighIn(kg) }
    }
}

/** Monday to Sunday of this week: a check on days with a finished session, today in the accent. */
@Composable
private fun WeekStrip(today: LocalDate, recent: List<WorkoutSessionEntity>) {
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val done = recent.filter { it.status == "finished" }.map { it.local_date }.toSet()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0L until 7L) {
            val day = monday.plusDays(i)
            val isToday = day == today
            val trained = day.toString() in done
            val name = day.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))
            Column(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(if (isToday) Fit.Accent else Fit.Tile)
                    .semantics { contentDescription = name + (if (isToday) ", today" else "") + (if (trained) ", trained" else "") },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                val ink = if (isToday) Fit.OnAccent else Fit.Muted
                Text(name.take(1), style = MaterialTheme.typography.labelMedium, color = ink)
                when {
                    trained -> Icon(Icons.Rounded.Check, null, tint = if (isToday) Fit.OnAccent else Fit.Accent, modifier = Modifier.size(16.dp))
                    else -> Box(Modifier.padding(top = 5.dp, bottom = 6.dp).size(5.dp).clip(CircleShape).background(if (isToday) Fit.OnAccent else Fit.Line))
                }
            }
        }
    }
}

/** Bodyweight (C32): the snapshot needs a weigh-in; stale after `bodyweight.stale_days`. */
@Composable
private fun BodyweightTile(c: AppContainer, kg: Double?, date: String?, today: LocalDate, modifier: Modifier, onWeighIn: () -> Unit) {
    val days = date?.let { ChronoUnit.DAYS.between(LocalDate.parse(it), today) }
    val stale = kg == null || (days != null && days > c.params.fork().int("bodyweight.stale_days"))
    Tile(modifier.heightIn(min = 112.dp), radius = 22.dp, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp), spacing = 2.dp, onClick = onWeighIn) {
        Text("Bodyweight", style = MaterialTheme.typography.labelMedium, color = Fit.Muted)
        Text(kg?.let { Numbers.kg(it) } ?: "—", style = MaterialTheme.typography.headlineMedium)
        if (stale) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.Warning, null, tint = Fit.Advisory, modifier = Modifier.size(14.dp))
                Text(if (kg == null) "Log a weigh-in" else "$days days ago, weigh in", style = MaterialTheme.typography.bodySmall, color = Fit.Advisory)
            }
        } else {
            Text(if (days == 0L) "Today · tap to update" else "$days days ago · tap to update", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
        }
    }
}

/** Finished sessions in this ISO week and the last one. */
@Composable
private fun WeekTile(recent: List<WorkoutSessionEntity>, today: LocalDate, modifier: Modifier) {
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val finished = recent.filter { it.status == "finished" }
    val count = finished.count { !LocalDate.parse(it.local_date).isBefore(monday) }
    Tile(modifier.heightIn(min = 112.dp), radius = 22.dp, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp), spacing = 2.dp) {
        Text("This week", style = MaterialTheme.typography.labelMedium, color = Fit.Muted)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineMedium)
            Text(if (count == 1) "session" else "sessions", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted, modifier = Modifier.padding(bottom = 4.dp))
        }
        // The week strip above shows this week's days; the last session is named only when it was before this week.
        finished.firstOrNull()?.let { LocalDate.parse(it.local_date) }?.takeIf { it.isBefore(monday) }?.let {
            Text("Last: " + it.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)), style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
        }
    }
}

@Composable
private fun RecentRow(s: WorkoutSessionEntity, onClick: () -> Unit) {
    val date = LocalDate.parse(s.local_date).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
    val dur = if (s.started_at != null && s.finished_at != null) ((s.finished_at!! - s.started_at!!) / 60_000).toString() + " min" else null
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(date, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(listOfNotNull(dur, if (s.status == "abandoned") "abandoned" else null).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Fit.Muted, modifier = Modifier.padding(start = 4.dp))
    }
}

/** One status line at most (ANDROID.md 7.9): reliability problems first, then sync. */
@Composable
private fun StatusRow(
    c: AppContainer, pending: PendingStatus, dead: Int, sync: SyncState, onOpenSync: () -> Unit, onOpenReliability: () -> Unit, onOpenEquipment: () -> Unit,
) {
    val context = LocalContext.current
    // Each problem opens the screen that fixes it: the permissions on Rest timer reliability, the gym on Equipment.
    val problems = buildList {
        if (!c.device.notificationsAllowed()) add("Notifications are off: the rest alarm cannot show" to onOpenReliability)
        if (!c.device.canScheduleExactAlarms()) add("Exact alarms are off: the rest alarm may be late" to onOpenReliability)
        if (context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).getBoolean("equipment_later", false)) add("Equipment not confirmed yet" to onOpenEquipment)
    }
    val syncText = when {
        dead > 0 -> "$dead changes could not be uploaded"
        sync is SyncState.NeedsSignIn -> "Sign in again to upload your data"
        sync is SyncState.NotAllowed -> "This account is not enabled for upload"
        sync is SyncState.SchemaNotExposed -> "Can't reach the server; changes are kept on this phone."
        sync is SyncState.SchemaDrift -> "Server update needed"
        sync is SyncState.ServerReadOnly -> "Server is full; changes are kept on this phone."
        sync is SyncState.Unreachable -> "Can't reach the server; changes are kept on this phone."
        else -> pendingLine(pending)
    }
    val (line, onClick) = problems.firstOrNull() ?: (syncText ?: return) to onOpenSync
    Banner(
        line, BannerKind.ADVISORY, icon = if (problems.isNotEmpty() || dead > 0) Icons.Rounded.Warning else Icons.Rounded.CloudOff,
        onClick = onClick,
    )
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
            FitTextField(
                value = text, onValueChange = { text = it }, label = "Bodyweight (kg)", singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = { TextButton(onClick = { kg?.let(onSave) }, enabled = kg != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
