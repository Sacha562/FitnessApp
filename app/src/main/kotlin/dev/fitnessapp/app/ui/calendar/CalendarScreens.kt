package dev.fitnessapp.app.ui.calendar

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import dev.fitnessapp.algo.challenge.calendar.CalendarLink
import dev.fitnessapp.algo.challenge.calendar.WritePrompt
import dev.fitnessapp.algo.core.calendar.Anchor
import dev.fitnessapp.algo.core.calendar.AnchorKind
import dev.fitnessapp.algo.core.calendar.SlotKey
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.BuildConfig
import dev.fitnessapp.app.calendar.CalendarPrompt
import dev.fitnessapp.app.calendar.CalendarSnapshot
import dev.fitnessapp.app.ui.challenges.ChallengeTexts
import dev.fitnessapp.app.ui.challenges.ChallengeWeek
import dev.fitnessapp.app.ui.common.Banner
import dev.fitnessapp.app.ui.common.BannerKind
import dev.fitnessapp.app.ui.common.ChipButton
import dev.fitnessapp.app.ui.common.FitScaffold
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.MoreSection
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.common.TonalButton
import dev.fitnessapp.app.ui.theme.Fit
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * You → Calendars (UX 15.6, D39): permission, the calendars read and the write target, this week as the app sees it
 * (kind and score per event, tap for the event sheet), the app's own events, the adopt list and the other prompts, and
 * the overrides. Until the Week tab (M4) this is where the calendar lives.
 */
@Composable
fun CalendarsRoute(c: AppContainer, onBack: () -> Unit) {
    val snap by c.calendar.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmReal by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch {
            c.fgsEvent("permission_result", mapOf("permission" to "calendar", "granted" to (it.values.all { g -> g })))
            c.calendar.refresh("permission")
        }
    }
    LaunchedEffect(Unit) { c.calendar.refresh("screen") }
    FitScaffold("Calendars", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let { Banner(it, BannerKind.ADVISORY, action = "OK", onAction = { message = null }) }
            if (snap.status == "permission_denied" || !c.calendar.canRead()) {
                Banner("Planning without your calendar. Challenge sessions are placed on their preferred days.", BannerKind.ADVISORY)
                PrimaryButton("Connect calendar", { launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) })
                return@Column
            }
            if (snap.status == "calendar_unavailable") Banner("The calendar could not be read. ${snap.error.orEmpty()}", BannerKind.ADVISORY)
            CalendarList(c, snap)
            WriteTile(c, snap, onRealWrites = { confirmReal = true }, onMessage = { message = it }, onPermission = {
                launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            })
            PromptTile(c, snap) { message = it }
            AppEventsTile(snap)
            WeekTile(snap, onOpen = { sheet = it })
            OverridesTile(snap, onOpen = { sheet = it })
            TonalButton("Refresh", { scope.launch { c.calendar.refresh("manual") } })
            snap.refreshedAt?.let {
                Text("Read ${ChallengeTexts.time(Week.localTime(it, ChallengeWeek.ZONE))}", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            }
        }
    }
    sheet?.let { key -> EventSheet(c, snap, key, onDismiss = { sheet = null }, onMessage = { message = it }) }
    if (confirmReal) RealWritesSheet(onDismiss = { confirmReal = false }) {
        confirmReal = false
        c.calendar.setRealWrites(true)
    }
}

@Composable
private fun CalendarList(c: AppContainer, snap: CalendarSnapshot) {
    val scope = rememberCoroutineScope()
    val extras = c.settings.calendarExtraIdentities
    val shown = snap.calendars.filter { it.visible || it.isTestCalendar }
    val first = if (snap.needsPick) shown else shown.filter { it == snap.primary || it.isTestCalendar || it.identity in extras }
        .sortedBy { if (it == snap.primary) 0 else if (it.isTestCalendar) 1 else 2 }
    val rest = shown - first.toSet()
    Tile(spacing = 6.dp) {
        TileLabel(if (snap.needsPick) "Pick your main calendar" else "Read gym sessions from")
        for (cal in first) CalendarRow(c, snap, cal, extras)
        if (rest.isNotEmpty()) MoreSection("More calendars (${rest.size})") {
            Column { for (cal in rest) CalendarRow(c, snap, cal, extras) }
        }
        Text("All-day events are always ignored.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
    }
}

@Composable
private fun CalendarRow(c: AppContainer, snap: CalendarSnapshot, cal: dev.fitnessapp.app.calendar.ProviderCalendar, extras: Set<String>) {
    val scope = rememberCoroutineScope()
    run {
            val todoist = Regex("(?i)todoist").containsMatchIn(cal.displayName)
            val primary = cal == snap.primary
            val checked = primary || cal.isTestCalendar || cal.identity in extras
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !primary && !cal.isTestCalendar && !todoist) {
                    scope.launch { if (snap.needsPick) c.calendar.selectPrimary(cal.identity) else c.calendar.toggleExtra(cal.identity) }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked, null, enabled = !todoist)
                Column(Modifier.weight(1f)) {
                    Text(cal.displayName, style = MaterialTheme.typography.bodyLarge)
                    val note = when {
                        primary -> "main calendar"
                        cal.isTestCalendar -> "test calendar on this phone"
                        todoist -> "ignored: old all-day tasks"
                        else -> null
                    }
                    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
                }
            }
    }
}

@Composable
private fun WriteTile(c: AppContainer, snap: CalendarSnapshot, onRealWrites: () -> Unit, onMessage: (String) -> Unit, onPermission: () -> Unit) {
    val scope = rememberCoroutineScope()
    Tile(spacing = 8.dp) {
        TileLabel("Challenge sessions are added to")
        val target = snap.target
        val name = snap.calendars.firstOrNull { it.id == target?.calendarId }?.displayName
        Text(
            when {
                snap.writeOff == "no_permission" -> "Not in your calendar: the app may not write to it."
                snap.writeOff == "off" -> "Not in your calendar: writing is off."
                target == null && snap.testCalendar == null -> "Not in your calendar yet: create the test calendar first."
                target == null -> "Not in your calendar: no main calendar."
                target.testCalendar -> "$name (test calendar; your own calendar is not changed)"
                else -> name.orEmpty()
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (snap.pending.isNotEmpty()) Text("${snap.pending.size} changes waiting", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
        if (snap.writeOff == "no_permission") TonalButton("Allow calendar access", onPermission)
        if (snap.testCalendar == null) TonalButton("Create test calendar", { scope.launch { c.calendar.createTestCalendar() } })
        if (BuildConfig.USER_CALENDAR_WRITES_ENABLED) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Write to my own calendar", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (c.settings.calendarRealWrites) "Challenge sessions and the sessions you plan go to your main calendar."
                        else "Off: only the test calendar on this phone is changed.",
                        style = MaterialTheme.typography.bodySmall, color = Fit.Muted,
                    )
                }
                Switch(c.settings.calendarRealWrites, { on -> if (on) onRealWrites() else c.calendar.setRealWrites(false).also { onMessage("New sessions go to the test calendar again.") } })
            }
        }
    }
}

@Composable
private fun PromptTile(c: AppContainer, snap: CalendarSnapshot, onMessage: (String) -> Unit) {
    if (snap.prompts.isEmpty()) return
    val scope = rememberCoroutineScope()
    Tile(spacing = 8.dp) {
        TileLabel("Needs you")
        snap.prompts.forEachIndexed { i, p ->
            if (i > 0) TileDivider()
            val inst = when (val q = p.prompt) {
                is WritePrompt.Adopt -> snap.instances[q.instanceKey]
                is WritePrompt.Rename -> snap.instances[q.instanceKey]
                is WritePrompt.Remove -> snap.instances[q.instanceKey]
                is WritePrompt.AddToCalendar -> null
            }
            val whenText = inst?.let { "${ChallengeTexts.date(Week.localDate(it.beginUtc, ChallengeWeek.ZONE))} ${ChallengeTexts.time(Week.localTime(it.beginUtc, ChallengeWeek.ZONE))}" }.orEmpty()
            when (val q = p.prompt) {
                is WritePrompt.Adopt -> PromptRow(
                    "${inst?.title.orEmpty()} · $whenText",
                    if (q.mismatch) "Your calendar says ${inst?.title.orEmpty()}; your challenge says ${slotName(q.slot)} that day." else "Let the app keep this ${slotName(q.slot)} event up to date: title and exercise list.",
                    if (q.mismatch) "Adopt and rename" else "Adopt",
                ) { scope.launch { c.calendar.adopt(p)?.let { onMessage(errorText(it)) } } }
                is WritePrompt.Rename -> PromptRow("${inst?.title.orEmpty()} · $whenText", "You edited this event. Rename it to ${q.title}?", "Rename") {
                    scope.launch { c.calendar.rename(p)?.let { onMessage(errorText(it)) } }
                }
                is WritePrompt.Remove -> PromptRow("${inst?.title.orEmpty()} · $whenText", "This session is no longer planned. Remove it from your calendar?", "Remove") {
                    scope.launch { c.calendar.remove(p)?.let { onMessage(errorText(it)) } }
                }
                is WritePrompt.AddToCalendar -> PromptRow("${slotName(q.slot)} · week ${q.isoWeek.substringAfter("-W").trimStart('0')}", "You deleted this event.", "Add to calendar") {
                    scope.launch { c.calendar.addToCalendar(p) }
                }
            }
        }
    }
}

@Composable
private fun PromptRow(title: String, text: String, action: String, onAction: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
        LinkButton(action, onAction)
    }
}

@Composable
private fun AppEventsTile(snap: CalendarSnapshot) {
    val links = snap.links.filter { it.state != CalendarLink.DELETED_BY_APP }.sortedBy { it.lastStart }
    if (links.isEmpty()) return
    Tile(spacing = 6.dp) {
        TileLabel("The app's events")
        for (l in links) {
            val key = SlotKey.parse(l.slotKey) as? SlotKey.Challenge
            val d = Week.localDate(l.lastStart, ChallengeWeek.ZONE)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${ChallengeTexts.date(d)} · ${key?.let { slotName(it.slot) } ?: l.slotKey}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(linkStateText(l.state), style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            }
        }
    }
}

@Composable
private fun WeekTile(snap: CalendarSnapshot, onOpen: (String) -> Unit) {
    val today = LocalDate.now(ChallengeWeek.ZONE)
    val week = Week.isoWeek(today)
    val rows = snap.anchors.filter { Week.isoWeek(it.localDate) == week && it.ignoredReason != "todoist" && it.ignoredReason != "all_day" }
    Tile(spacing = 4.dp) {
        TileLabel("This week as the app sees it")
        if (rows.isEmpty()) Text("No events this week.", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
        for (a in rows) AnchorRow(snap, a) { onOpen(a.instanceKey) }
    }
}

@Composable
private fun OverridesTile(snap: CalendarSnapshot, onOpen: (String) -> Unit) {
    val rows = snap.anchors.filter { it.method?.code == "manual" }
    if (rows.isEmpty()) return
    Tile(spacing = 4.dp) {
        TileLabel("Your corrections")
        for (a in rows) AnchorRow(snap, a) { onOpen(a.instanceKey) }
    }
}

@Composable
private fun AnchorRow(snap: CalendarSnapshot, a: Anchor, onClick: () -> Unit) {
    val inst = snap.instances[a.instanceKey]
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(inst?.title ?: "(no title)", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text("${ChallengeTexts.date(a.localDate)} ${ChallengeTexts.time(a.startLocal)}", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
        }
        Text(kindText(a), style = MaterialTheme.typography.bodySmall, color = if (a.needsConfirm) Fit.Advisory else Fit.Muted)
    }
}

/**
 * The calendar event sheet (UX 16.11, D39): the event, how the app reads it, the correction choices with the scope
 * "this event" or "the whole series", and Adopt for an unmarked challenge event.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventSheet(c: AppContainer, snap: CalendarSnapshot, instanceKey: String, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val a = snap.anchors.firstOrNull { it.instanceKey == instanceKey } ?: return
    val inst = snap.instances[instanceKey]
    val scope = rememberCoroutineScope()
    var series by remember { mutableStateOf(a.seriesKey in c.settings.calendarSeriesOverrides) }
    val enrollmentId = a.enrollmentId ?: snap.prompts.firstOrNull()?.enrollmentId ?: snap.links.firstOrNull()?.markerUuid
    val slots = listOf("C", "A", "B", "TEST")
    fun apply(kind: String?, slot: String? = null, type: String? = null) {
        scope.launch {
            c.calendar.correct(instanceKey, kind, slot, if (kind == AnchorKind.CHALLENGE_SESSION.code) enrollmentId else null, type, series)
            onDismiss()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(inst?.title ?: "(no title)", style = MaterialTheme.typography.titleLarge)
            Text(
                "${ChallengeTexts.date(a.localDate)} ${ChallengeTexts.time(a.startLocal)}-${ChallengeTexts.time(a.endLocal)} · " +
                    (snap.calendars.firstOrNull { it.id == a.calendarId }?.displayName ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = Fit.Muted,
            )
            Text("The app reads this as: ${kindText(a)}", style = MaterialTheme.typography.bodyMedium)
            TileLabel("This is")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in slots) {
                    ChipButton(slotName(s), a.kind == AnchorKind.CHALLENGE_SESSION && a.slot == s, { apply(AnchorKind.CHALLENGE_SESSION.code, slot = s) }, enabled = enrollmentId != null)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, type) in listOf("Push" to "PUSH", "Pull" to "PULL", "Legs" to "LEGS", "Other" to "AUTO")) {
                    ChipButton("Gym: $label", a.kind == AnchorKind.PLANNED_GYM && a.gymType == type, { apply(AnchorKind.PLANNED_GYM.code, type = type) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipButton("Run", a.kind == AnchorKind.RUN, { apply(AnchorKind.RUN.code) })
                ChipButton("Not a gym session", a.kind == AnchorKind.BUSY && a.method?.code == "manual", { apply(AnchorKind.IGNORED.code) })
            }
            Text("Apply to", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipButton("This event", !series, { series = false })
                ChipButton("The whole series", series, { series = true })
            }
            if (a.method?.code == "manual") LinkButton("Remove correction", { apply(null) })
            val adopt = snap.prompts.firstOrNull { (it.prompt as? WritePrompt.Adopt)?.instanceKey == instanceKey }
            if (adopt != null) PrimaryButton("Adopt (let the app update it)", {
                scope.launch {
                    c.calendar.adopt(adopt)?.let { onMessage(errorText(it)) }
                    onDismiss()
                }
            })
        }
    }
}

/** D39: real writes only after the test-calendar checks, confirmed in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RealWritesSheet(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Write to your own calendar?", style = MaterialTheme.typography.titleLarge)
            Text(
                "The app then adds and updates challenge sessions for this week and next in your main calendar. It only edits its own events and the ones you adopt.",
                style = MaterialTheme.typography.bodyMedium,
            )
            PrimaryButton("Write to my calendar", onConfirm)
            TonalButton("Not now", onDismiss)
        }
    }
}

fun slotName(slot: String): String = if (slot == "TEST") "Test day" else "Session $slot"

fun kindText(a: Anchor): String = if (a.method?.code == "manual") manualText(a) else when (a.kind) {
    AnchorKind.CHALLENGE_SESSION -> slotName(a.slot.orEmpty()) + (a.matchScore?.let { " (" + String.format(java.util.Locale.ROOT, "%.2f", it) + ")" } ?: "") +
        (if (a.attention != null) " · other type" else "")
    AnchorKind.PLANNED_GYM -> "Gym" + (a.gymType?.takeIf { it != "AUTO" }?.let { ": " + it.lowercase().replace("+", " + ") } ?: "") + " (${a.score})"
    AnchorKind.RUN -> "Run, ${a.runIntensity?.code ?: "easy"} ${a.runMinutes ?: 0} min"
    AnchorKind.BUSY -> "Busy"
    AnchorKind.IGNORED -> when (a.ignoredReason) {
        "duplicate_of" -> "Duplicate"
        "cancelled" -> "Cancelled"
        "declined" -> "Declined"
        "availability_free" -> "Free time"
        else -> "Ignored"
    }
}

/** A user's correction reads as what it says, without a score. */
private fun manualText(a: Anchor): String = when (a.kind) {
    AnchorKind.CHALLENGE_SESSION -> slotName(a.slot.orEmpty())
    AnchorKind.PLANNED_GYM -> "Gym" + (a.gymType?.takeIf { it != "AUTO" }?.let { ": " + it.lowercase().replace("+", " + ") } ?: "")
    AnchorKind.RUN -> "Run"
    else -> "Not a gym session"
} + " (your correction)"

fun linkStateText(state: String): String = when (state) {
    CalendarLink.ACTIVE -> "Kept up to date"
    CalendarLink.USER_MOVED -> "Moved by you"
    CalendarLink.USER_EDITED -> "Edited by you"
    CalendarLink.USER_DELETED -> "Deleted by you"
    CalendarLink.RELEASED -> "Left alone"
    else -> state
}

fun errorText(code: String): String = when (code) {
    "sandbox" -> "Writing to your own calendar is off; only the test calendar is changed."
    "target_guard" -> "This event is not in the calendar the app writes to."
    "already_marked" -> "This event already belongs to the app."
    "marker_missing" -> "The event changed meanwhile; nothing was written."
    "no_target" -> "No calendar to write to yet."
    else -> "The calendar could not be changed ($code)."
}
