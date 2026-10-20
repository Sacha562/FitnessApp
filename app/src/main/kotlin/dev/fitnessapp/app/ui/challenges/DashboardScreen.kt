package dev.fitnessapp.app.ui.challenges

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.common.Banner
import dev.fitnessapp.app.ui.common.BannerKind
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.FitScaffold
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.Pill
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.ProgressBar
import dev.fitnessapp.app.ui.common.RoundIconButton
import dev.fitnessapp.app.ui.common.SectionLabel
import dev.fitnessapp.app.ui.common.Segmented
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.common.TonalButton
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.uidet.GateRow
import kotlinx.coroutines.launch

private enum class DashTab(val label: String) { OVERVIEW("Overview"), RULES("Rules"), HISTORY("History"), TESTS("Tests") }

/** The active challenge (UX 14.2-14.7). */
@Composable
fun ChallengeDashboardRoute(c: AppContainer, enrollmentId: String, onBack: () -> Unit) {
    val vm: DashboardViewModel = viewModel(key = "dash:$enrollmentId", factory = viewModelFactory { initializer { DashboardViewModel(c, enrollmentId) } })
    val ui by vm.ui.collectAsState()
    val messages by vm.messages.collectAsState()
    val error by vm.error.collectAsState()
    var tab by remember { mutableStateOf(DashTab.OVERVIEW) }
    var menu by remember { mutableStateOf(false) }
    var proposals by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }

    if (proposals) {
        BackHandler { proposals = false }
        ProposalsScreen(ui, onBack = { proposals = false }, onDecide = vm::decide, onViewed = { id -> c.appScope.launch { c.fgsEvent("challenge_proposal_viewed", mapOf("proposal_id" to id)) } })
        return
    }

    FitScaffold(
        title = ui?.title ?: "Challenge", onBack = onBack,
        actions = {
            Box {
                RoundIconButton(Icons.Rounded.MoreVert, "Menu", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val paused = ui?.status == "paused"
                    val open = ui?.status == "active" || paused
                    if (open) DropdownMenuItem(text = { Text(if (paused) "Resume" else "Pause") }, onClick = { menu = false; sheet = if (paused) "resume" else "pause" })
                    DropdownMenuItem(text = { Text("Proposals") }, onClick = { menu = false; proposals = true })
                    if (open) DropdownMenuItem(text = { Text("Correct a value") }, onClick = { menu = false; sheet = "correct" })
                    if (open) DropdownMenuItem(text = { Text("Abandon") }, onClick = { menu = false; sheet = "abandon" })
                }
            }
        },
    ) { pad ->
        val u = ui
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Banner(it, BannerKind.ADVISORY, action = "OK", onAction = vm::clearMessages) }
            messages.forEach { (kind, text) -> Banner(text, if (kind == "stop" || kind == "warn") BannerKind.SAFETY else BannerKind.ADVISORY, action = "OK", onAction = vm::clearMessages) }
            if (u == null) {
                Text("Loading…", color = Fit.Muted)
                return@Column
            }
            if (u.undecided > 0) Banner("${u.undecided} proposals waiting for your decision", BannerKind.ADVISORY, action = "Review", onAction = { proposals = true })
            u.pausedSince?.let { Banner("Paused since ${ChallengeTexts.date(it)}", BannerKind.ADVISORY, action = "Resume", onAction = { sheet = "resume" }) }
            Segmented(DashTab.entries, tab, { it.label }, { tab = it })
            when (tab) {
                DashTab.OVERVIEW -> {
                    DashboardOverview(u, onPain = { sheet = "pain" })
                    HabitRemindersTile(c)
                }
                DashTab.RULES -> Rules(u)
                DashTab.HISTORY -> History(u)
                DashTab.TESTS -> Tests(u)
            }
        }
    }

    when (sheet) {
        "pause" -> PauseDialog(vm, onDone = { sheet = null })
        "resume" -> AlertDialog(
            onDismissRequest = { sheet = null },
            title = { Text("Resume?") },
            text = { Text("The definition's resume rules decide what happens after the pause (repeat the week, or restart the block after a long pause).") },
            confirmButton = { TextButton({ vm.resume(); sheet = null }) { Text("Resume") } },
            dismissButton = { TextButton({ sheet = null }) { Text("Cancel") } },
        )
        "abandon" -> AlertDialog(
            onDismissRequest = { sheet = null },
            title = { Text("Abandon ${ui?.title}?") },
            text = { Text("Your history is kept. Future calendar events the app created will be removed. You can restart later from your last block's loads.") },
            confirmButton = { TextButton({ vm.abandon(); sheet = null }) { Text("Abandon", color = Fit.Safety) } },
            dismissButton = { TextButton({ sheet = null }) { Text("Cancel") } },
        )
        "pain" -> dev.fitnessapp.app.ui.live.PainSheet(onDismiss = { sheet = null }) { region, side, severity, symptoms ->
            sheet = null
            vm.reportPain(region, side, severity, symptoms)
        }
        "correct" -> CorrectDialog(ui?.targets.orEmpty(), onSave = { t, v, r -> vm.correct(t, v, r); sheet = null }, onDismiss = { sheet = null })
    }
}

/**
 * Home-habit reminders (ANDROID.md 17): off until the D4 cutover so Todoist and the app never both remind; the
 * cutover switches them on, and this switch can turn them off again.
 */
@Composable
private fun HabitRemindersTile(c: AppContainer) {
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(c.settings.habitRemindersEnabled) }
    Tile {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Habit reminders", style = MaterialTheme.typography.titleMedium)
                Text("A notification at each home habit's time, with Start, Snooze and Skip today.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            }
            androidx.compose.material3.Switch(checked = on, onCheckedChange = { v ->
                val from = on
                on = v
                c.settings.habitRemindersEnabled = v
                scope.launch {
                    c.fgsEvent("settings_changed", mapOf("key" to "habit_reminders", "from" to from, "to" to v))
                    c.reminders.recompute()
                }
            })
        }
    }
}

/** The Overview tab (UX 14.2); internal for the screenshot test. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DashboardOverview(u: DashboardUi, onPain: () -> Unit = {}) {
    Tile {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(u.statusText, container = if (u.mode == "injury") Fit.SafetyContainer else Fit.Raised)
            Text("Phase ${u.phase} of ${u.phases.size}", style = MaterialTheme.typography.labelLarge, color = Fit.Muted)
        }
        PhaseStepper(u.phases, u.phase)
        Text(u.blockLine, style = MaterialTheme.typography.bodyLarge)
        u.nextTest?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        u.precondition?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Advisory) }
    }
    if (u.week.isNotEmpty()) {
        Tile {
            TileLabel("This week")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                u.week.forEach { w -> Pill(w.text, container = if (w.done) Fit.Accent else Fit.Raised, content = if (w.done) Fit.OnAccent else Fit.Ink) }
            }
        }
    }
    Tile {
        TileLabel(u.gates.header)
        u.gates.rows.forEachIndexed { i, r ->
            if (i > 0) TileDivider()
            GateRowView(r)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TonalButton("Log test day", {}, Modifier.weight(1f), enabled = false)
        TonalButton("Report pain", onPain, Modifier.weight(1f))
    }
    Text("The test-day wizard arrives with step 4 of M2b.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
}

@Composable
private fun PhaseStepper(phases: List<String>, current: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        phases.forEachIndexed { i, name ->
            val id = i + 1
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(if (id <= current) Fit.Accent else Fit.Raised),
                    contentAlignment = Alignment.Center,
                ) { Text("$id", style = MaterialTheme.typography.labelMedium, color = if (id <= current) Fit.OnAccent else Fit.Muted) }
                Text(name, style = MaterialTheme.typography.labelSmall, color = if (id == current) Fit.Ink else Fit.Muted, maxLines = 2)
            }
        }
    }
}

@Composable
private fun GateRowView(r: GateRow) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (r.met == true) Icon(Icons.Rounded.Check, "Met", tint = Fit.Accent, modifier = Modifier.size(18.dp))
            Text(r.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        r.target?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
        r.sides.forEach { s ->
            Text(s.text, style = MaterialTheme.typography.bodySmall)
            ProgressBar(s.fraction.toFloat(), height = 6.dp)
        }
        r.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
        r.muted?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted.copy(alpha = 0.7f)) }
    }
}

@Composable
private fun Rules(u: DashboardUi) {
    var open by remember { mutableStateOf<String?>(null) }
    val stages = listOf("safety" to "Safety", "data" to "Data", "structural" to "Structure", "progression" to "Progression", "in_session" to "In-session")
    for ((stage, label) in stages) {
        val rows = u.rules.filter { it.stage == stage }
        if (rows.isEmpty()) continue
        SectionLabel(label)
        Tile(spacing = 6.dp) {
            rows.forEachIndexed { i, r ->
                if (i > 0) TileDivider()
                Column(Modifier.fillMaxWidth().clickable { open = if (open == r.id) null else r.id }.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(r.tag)
                        Text(r.id, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = Fit.Muted, modifier = Modifier.weight(1f))
                    }
                    Text(r.text, style = MaterialTheme.typography.bodyMedium)
                    Text(listOfNotNull(r.status, r.via, if (r.fired > 0) "applied ${r.fired}×" else null).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (r.status.startsWith("Waiting")) Fit.Advisory else Fit.Muted)
                    if (open == r.id) Text(r.source, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = Fit.Muted)
                }
            }
        }
    }
}

@Composable
private fun History(u: DashboardUi) {
    if (u.history.isEmpty()) Text("Nothing yet.", color = Fit.Muted)
    u.history.forEach { h ->
        Tile(spacing = 4.dp) {
            Text(h.header, style = MaterialTheme.typography.titleSmall)
            h.lines.forEach { (text, muted) -> Text(text, style = MaterialTheme.typography.bodySmall, color = if (muted) Fit.Muted.copy(alpha = 0.7f) else Fit.Ink) }
        }
    }
}

@Composable
private fun Tests(u: DashboardUi) {
    if (u.tests.isEmpty()) Text("No valid test yet.", color = Fit.Muted)
    u.tests.forEach { t ->
        Tile(spacing = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.date, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Pill(t.valid)
            }
            t.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** UX 14.4: every proposal decided on its own, no "approve all"; a decision can change any time (D8). */
@Composable
fun ProposalsScreen(ui: DashboardUi?, onBack: () -> Unit, onDecide: (String, String) -> Unit, onViewed: (String) -> Unit) {
    FitScaffold("Proposals", onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "A rule from your program runs as written unless you approve the proposal that replaces it. An undecided proposal is inactive.",
                style = MaterialTheme.typography.bodyMedium, color = Fit.Muted,
            )
            ui?.proposals?.forEach { p -> ProposalTile(p, onDecide, onViewed) }
        }
    }
}

@Composable
fun ProposalTile(p: ProposalUi, onDecide: (String, String) -> Unit, onViewed: (String) -> Unit) {
    LaunchedEffect(p.id) { onViewed(p.id) }
    Tile(spacing = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("${p.id} · P")
            p.decision?.let { Pill(if (it == "approved") "Approved" else "Declined", container = if (it == "approved") Fit.Accent else Fit.Raised, content = if (it == "approved") Fit.OnAccent else Fit.Ink) }
        }
        Text(p.title, style = MaterialTheme.typography.titleMedium)
        Text(p.rationale, style = MaterialTheme.typography.bodyMedium)
        Text("If you decline: ${p.ifDeclined}", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
        p.switches?.let { Text("Switches: $it", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = Fit.Muted) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.decision == "approved") TonalButton("Approved", {}, Modifier.weight(1f), height = 44.dp, container = Fit.Accent, content = Fit.OnAccent)
            else TonalButton("Approve", { onDecide(p.id, "approved") }, Modifier.weight(1f), height = 44.dp)
            if (p.decision == "declined") TonalButton("Declined", {}, Modifier.weight(1f), height = 44.dp, container = Fit.Accent, content = Fit.OnAccent)
            else TonalButton("Decline", { onDecide(p.id, "declined") }, Modifier.weight(1f), height = 44.dp)
        }
    }
}

/** UX 14.7 pause sheet: reason chips and the resume preview of CHALLENGE_ENGINE 10.5. */
@Composable
private fun PauseDialog(vm: DashboardViewModel, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<Pair<Long, List<String>>>>(emptyList()) }
    LaunchedEffect(Unit) { preview = runCatching { vm.pausePreview() }.getOrDefault(emptyList()) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Pause the challenge") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChips(PAUSE_REASONS, PAUSE_REASONS.firstOrNull { it.first == reason }, { it.first }) { reason = it.first }
                preview.forEach { (days, notes) ->
                    Text("Back after $days ${if (days == 1L) "day" else "days"}: " + notes.ifEmpty { listOf("nothing changes.") }.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { vm.pause(PAUSE_REASONS.first { it.first == reason }.second); onDone() } }, enabled = reason != null) { Text("Pause") }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
    )
}

/**
 * Pause reasons of UX 14.7 and their DATA_MODEL 9.1.3 codes (context `pause`, D34).
 */
private val PAUSE_REASONS = listOf("Holiday" to "holiday", "Illness" to "illness", "Injury" to "injury", "Other" to "other")
