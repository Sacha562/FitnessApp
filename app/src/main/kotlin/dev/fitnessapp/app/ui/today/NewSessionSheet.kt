package dev.fitnessapp.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.calendar.AnchorKind
import dev.fitnessapp.algo.pipeline.BuildResult
import dev.fitnessapp.algo.planning.allocator.Allocation
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.planning.PlanningFacts
import dev.fitnessapp.app.planning.SessionBuild
import dev.fitnessapp.app.ui.common.ChipButton
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.uidet.TimeChip
import dev.fitnessapp.app.uidet.UiTrim

/** A built session ready to start (D62): its type, the build, the chip's minutes and where they came from. */
data class BuiltStart(val type: SessionType, val result: BuildResult, val budgetMin: Int, val budgetSource: String)

/**
 * The New session sheet (D62): the eight session types, the time chips (a calendar chip when today has a general gym
 * event, then 45 / 60 / 75 / 90+, the default length preselected otherwise), a live preview of the built exercises in
 * ORD-1 order with sets, ~minutes and the trim line, and "Empty session" and "Repeat" at the bottom. A type the
 * allocator cannot give anything today is disabled with its reason. Builds run off the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewSessionSheet(
    c: AppContainer,
    onDismiss: () -> Unit,
    onStart: (BuiltStart) -> Unit,
    onEmpty: () -> Unit,
    onRepeat: () -> Unit,
) {
    var facts by remember { mutableStateOf<SessionBuild.Facts?>(null) }
    var chips by remember { mutableStateOf<List<TimeChip>>(emptyList()) }
    var chip by remember { mutableStateOf<TimeChip?>(null) }
    var allocations by remember { mutableStateOf<Map<SessionType, Allocation>>(emptyMap()) }
    var type by remember { mutableStateOf<SessionType?>(null) }
    var built by remember { mutableStateOf<BuildResult?>(null) }
    var building by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val f = runCatching { SessionBuild.facts(c) }.getOrNull()
        if (f == null) {
            failed = true
            return@LaunchedEffect
        }
        val profile = c.profiles.get()
        val today = Week.localDate(f.at, PlanningFacts.ZONE)
        val event = c.calendar.snapshot.value.anchors.filter { it.kind == AnchorKind.PLANNED_GYM && it.localDate == today }
            .maxByOrNull { it.endUtc.toEpochMilli() - it.beginUtc.toEpochMilli() }
            ?.let { ((it.endUtc.toEpochMilli() - it.beginUtc.toEpochMilli()) / 60_000L).toInt() - (profile?.changeover_min ?: AppContainer.CHANGEOVER_MIN) }
            ?.takeIf { it > 0 }
        val max = c.params.fork().int("today.max_budget_min")
        val list = UiTrim.chips(event, listOf(45, 60, 75), max)
        chips = list
        chip = list.firstOrNull { it.source == "calendar" } ?: list.firstOrNull { it.minutes == (profile?.default_session_min ?: 75) }
            ?: list.firstOrNull { it.minutes == 75 } ?: list.first()
        facts = f
        val budget = chip!!.minutes.toDouble()
        allocations = SessionBuild.TYPES.associateWith { t -> SessionBuild.allocate(c, SessionBuild.request(f, t, budget)) }
        type = SessionBuild.TYPES.firstOrNull { UiTrim.usable(allocations[it]) }
    }
    LaunchedEffect(facts, type, chip) {
        val f = facts ?: return@LaunchedEffect
        val t = type ?: return@LaunchedEffect
        val ch = chip ?: return@LaunchedEffect
        building = true
        built = runCatching { SessionBuild.build(c, SessionBuild.request(f, t, ch.minutes.toDouble())) }.getOrNull()
        building = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("New session", style = MaterialTheme.typography.titleLarge)
            when {
                failed -> Text("Couldn't build a session right now. You can still start an empty one.", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                facts == null -> Text("Working out what this week still needs…", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                else -> {
                    TileLabel("Train")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SessionBuild.TYPES.forEach { t ->
                            ChipButton(SessionBuild.label(t), selected = t == type, onClick = { type = t }, enabled = UiTrim.usable(allocations[t]))
                        }
                    }
                    val blocked = SessionBuild.TYPES.filter { !UiTrim.usable(allocations[it]) }
                    if (blocked.isNotEmpty()) {
                        Text(
                            "Not today: " + blocked.joinToString(", ") { SessionBuild.label(it) } + " (covered by your challenge, protected or already done this week)",
                            style = MaterialTheme.typography.bodySmall, color = Fit.Muted,
                        )
                    }
                    TileLabel("Time")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chips.forEach { ch -> ChipButton(ch.label, selected = ch == chip, onClick = { chip = ch }) }
                    }
                    TileDivider()
                    val b = built
                    when {
                        type == null -> Text("Nothing to build today: everything is covered or rests.", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                        building && b == null -> Text("Building…", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                        b == null || b.exercises.isEmpty() -> Text(UiTrim.emptyText(b), style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                        else -> {
                            val families = SessionBuild.families(b, c)
                            Text(SessionBuild.title(families) + " · ~" + UiTrim.minutes(b.estMin) + " min", style = MaterialTheme.typography.titleMedium)
                            b.exercises.forEach { x ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(c.catalog.bySlug(x.slug)?.nameEn ?: x.slug, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text("${x.sets} sets", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                                }
                            }
                            UiTrim.trimLine(b, chip!!.minutes, max = c.params.fork().int("today.max_budget_min")) { c.catalog.bySlug(it)?.nameEn ?: it }
                                ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
                            if (b.coreDoesNotFit) Text("This doesn't fit the time; pick a longer chip.", style = MaterialTheme.typography.bodySmall, color = Fit.Advisory)
                            PrimaryButton(
                                "Start " + SessionBuild.title(families).lowercase(),
                                onClick = { onStart(BuiltStart(type!!, b, chip!!.minutes, chip!!.source)) },
                                modifier = Modifier.fillMaxWidth(), enabled = !building,
                            )
                        }
                    }
                }
            }
            TileDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LinkButton("Empty session", onEmpty)
                LinkButton("Repeat a session", onRepeat)
            }
        }
    }
}
