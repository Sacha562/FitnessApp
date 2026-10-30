package dev.fitnessapp.app.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.targets.runs.Run
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.planning.PlanningFacts
import dev.fitnessapp.app.planning.WeekVolume
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.uidet.UiVolumeBars
import dev.fitnessapp.app.uidet.VolumeRow
import dev.fitnessapp.app.uidet.VolumeState
import dev.fitnessapp.data.telemetry.EventDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Week tab (UX 10), M4.2 part: the week's header, the runs the calendar shows (they count as leg fatigue only, F6)
 * and the muscle volume bars (10.8): done and committed volume against this week's targets. The day rows, Plan it and
 * the week navigation arrive with the week planner (M4.5, M4.6).
 */
@Composable
fun WeekRoute(c: AppContainer, modifier: Modifier, onSetGoals: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var week by remember { mutableStateOf<WeekVolume?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    LaunchedEffect(tick) {
        runCatching { withContext(Dispatchers.Default) { WeekVolume.compute(c) } }
            .onSuccess { w ->
                week = w
                failed = null
                shownOnce(c, w)
            }
            .onFailure { failed = it.message ?: it.javaClass.simpleName }
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val w = week
        val monday = w?.monday ?: Week.isoWeekStart(java.time.LocalDate.now(PlanningFacts.ZONE))
        val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        Column {
            Text("Week " + monday.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR), style = MaterialTheme.typography.titleSmall, color = Fit.Muted)
            Text(fmt.format(monday) + " – " + fmt.format(monday.plusDays(6)), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            w?.challenge?.let { ch ->
                Text(
                    ch.name + " · block ${ch.blockIndex} · week ${ch.blockWeek}" + if (ch.deload) " · deload" else "",
                    style = MaterialTheme.typography.bodyMedium, color = Fit.Muted,
                )
            }
        }
        failed?.let { Text("The week could not be computed: $it", style = MaterialTheme.typography.bodySmall, color = Fit.Advisory) }
        if (w == null) return@Column
        if (w.runs.isNotEmpty()) RunsTile(w.runs)
        if (!w.goalsSet) {
            Tile(spacing = 6.dp, onClick = onSetGoals) {
                Text("Set your goals", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pick a goal per muscle group, then this page shows your weekly targets next to what you did.",
                    style = MaterialTheme.typography.bodySmall, color = Fit.Muted,
                )
                LinkButton("Open Muscle goals", onSetGoals)
            }
        } else {
            VolumeTile(c, w) { group ->
                open = group
                val state = w.bars.rows.firstOrNull { it.group == group }?.state?.name ?: "COVERED"
                scope.launch {
                    c.sessionRepository.event(
                        EventDraft(type = "volume_row_opened", screen = "week", entityType = "none", payload = mapOf("group" to JsonPrimitive(group), "state" to JsonPrimitive(state))),
                    )
                }
            }
        }
    }
    val w = week
    if (open != null && w != null) GroupSheet(c, w, open!!) { open = null }
}

/** `weekplan_shown` once per local date and ISO week (UX 19.5): the volume rows only until the planner arrives. */
private suspend fun shownOnce(c: AppContainer, w: WeekVolume) {
    val key = "weekplan_shown:" + java.time.LocalDate.now(PlanningFacts.ZONE) + ":" + w.isoWeek
    if (!c.settings.once(key)) return
    val rows = w.bars.rows.map { r ->
        JsonObject(
            mapOf(
                "group" to JsonPrimitive(r.group), "done" to JsonPrimitive(r.done), "planned" to JsonPrimitive(r.planned),
                "target" to JsonPrimitive(r.target), "state" to JsonPrimitive(r.state.name), "marker" to JsonPrimitive(if (r.flag) "!" else ""),
            ),
        )
    }
    c.sessionRepository.event(
        EventDraft(
            type = "weekplan_shown", screen = "week", entityType = "none",
            payload = mapOf(
                "iso_week" to JsonPrimitive(w.isoWeek), "iso_week_offset" to JsonPrimitive(0), "days" to JsonArray(emptyList()),
                "volume_rows" to JsonArray(rows),
            ),
        ),
    )
}

@Composable
private fun RunsTile(runs: List<Run>) {
    val day = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
    Tile(spacing = 4.dp) {
        TileLabel("Runs")
        Text(
            runs.joinToString(" · ") { r ->
                day.format(Week.localDate(r.start, PlanningFacts.ZONE)) + " ${Math.round(r.minutes)} min " + r.intensity + if (r.long) ", long" else ""
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Runs count as leg fatigue, not as training volume.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
    }
}

@Composable
private fun VolumeTile(c: AppContainer, w: WeekVolume, onOpen: (String) -> Unit) {
    Tile(spacing = 0.dp, padding = PaddingValues(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 6.dp)) {
        TileLabel("Muscle volume (hard sets this week)", Modifier.padding(start = 10.dp, bottom = 2.dp))
        Text(
            if (w.targets.deload.active) "Deload week: targets are halved. Planned is your challenge's sessions still ahead."
            else "Solid: done. Light: your challenge's sessions still ahead. Tick: this week's target.",
            style = MaterialTheme.typography.bodySmall, color = Fit.Muted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        w.bars.rows.forEachIndexed { i, r ->
            if (i > 0) TileDivider(Modifier.padding(horizontal = 10.dp))
            BarRow(label(c, r.group), r) { onOpen(r.group) }
        }
        w.bars.covered.forEach { g ->
            TileDivider(Modifier.padding(horizontal = 10.dp))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).clickable { onOpen(g.group) }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label(c, g.group), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("covered by challenge · " + UiVolumeBars.text(g.done, g.planned, 0.0).substringBefore(" / "), style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            }
        }
        if (w.bars.skipped.isNotEmpty()) {
            TileDivider(Modifier.padding(horizontal = 10.dp))
            Text(
                "Not trained: " + w.bars.skipped.joinToString(", ") { label(c, it).lowercase(Locale.ENGLISH) },
                style = MaterialTheme.typography.bodySmall, color = Fit.Muted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }
    }
}

/** One bar row (UX 19.5): name, goal tag, bar (done solid, planned light, target tick at 1.0, MRV marker), numbers, `!`. */
@Composable
private fun BarRow(name: String, r: VolumeRow, onClick: () -> Unit) {
    val tag = when {
        r.goal == "improve" && r.focus -> "focus"
        r.goal == "improve" -> "improve"
        else -> "maintain"
    }
    val stateText = when (r.state) {
        VolumeState.DEFICIT -> ", below the evidence minimum"
        VolumeState.OVER -> ", above the recoverable maximum"
        VolumeState.OK -> ""
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$name, $tag, ${r.text} sets$stateText" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(r.text, style = MaterialTheme.typography.bodyMedium)
            Text(tag, style = MaterialTheme.typography.labelSmall, color = Fit.Muted)
            if (r.flag) Text("!", style = MaterialTheme.typography.titleSmall, color = if (r.state == VolumeState.DEFICIT) Fit.Advisory else Fit.Muted)
        }
        val color = if (r.goal == "improve") Fit.Accent else Fit.Outline
        BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp)) {
            val full = maxWidth
            val cap = 1.25f
            fun x(share: Double) = full * (share.toFloat() / cap).coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(Fit.Raised)
                    .then(if (r.focus) Modifier.border(1.dp, Fit.Accent, CircleShape) else Modifier),
            )
            Box(Modifier.width(x(r.fillDone + r.fillPlanned)).fillMaxHeight().clip(CircleShape).background(color.copy(alpha = 0.35f)))
            Box(Modifier.width(x(r.fillDone)).fillMaxHeight().clip(CircleShape).background(color))
            Box(Modifier.offset(x = x(1.0) - 1.dp).width(2.dp).fillMaxHeight().background(Fit.Ink))
            r.normalMark?.let { Box(Modifier.offset(x = x(it) - 1.dp).width(2.dp).fillMaxHeight().background(Fit.Ink.copy(alpha = 0.3f))) }
            r.mrvMark?.let { Box(Modifier.offset(x = x(it) - 1.dp).width(2.dp).fillMaxHeight().background(Fit.Safety)) }
        }
    }
}

/** The row's sheet (UX 10.8): this week's contributions per exercise and the Why of the target. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupSheet(c: AppContainer, w: WeekVolume, group: String, onDismiss: () -> Unit) {
    val gt = w.targets.groups[group]
    val rows = w.contributions[group].orEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label(c, group), style = MaterialTheme.typography.titleLarge)
            if (gt != null) Text(why(w, group), style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
            w.warnings.firstOrNull { it.entity == "group:$group" }?.let { v ->
                val x = v.numbers["x"] ?: 0.0
                Text(
                    "Planned this week: ${UiVolumeBars.text(x, 0.0, 0.0).substringBefore(" / ")} hard sets. Research finds more growth from at least 10 a week " +
                        "for a muscle you want to improve (Pelland 2026; moderate evidence).",
                    style = MaterialTheme.typography.bodySmall, color = Fit.Advisory,
                )
            }
            TileLabel("This week")
            if (rows.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
            rows.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.catalog.bySlug(r.slug)?.nameEn ?: r.slug, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        UiVolumeBars.text(r.vhyp, 0.0, 0.0).substringBefore(" / ") + (if (r.committed) " planned" else ""),
                        style = MaterialTheme.typography.bodyMedium, color = if (r.committed) Fit.Muted else Fit.Ink,
                    )
                }
            }
            Text(
                "Hard sets are weighted: a set counts fully for its main muscles and partly for the muscles that help (Pelland 2026).",
                style = MaterialTheme.typography.bodySmall, color = Fit.Muted, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** The Why of a group's target (F19): goal, base by training age, focus, deload, or the challenge that covers it. */
private fun why(w: WeekVolume, group: String): String {
    val gt = w.targets.groups.getValue(group)
    val age = when (w.targets.trainingAge) { "novice" -> "under 1 year"; "advanced" -> "more than 3 years"; else -> "1-3 years" }
    val n = { x: Double -> UiVolumeBars.text(x, 0.0, 0.0).substringBefore(" / ") }
    return when (gt.status) {
        "covered" -> "Your challenge trains this group while it is active, so the general plan leaves it alone." +
            (gt.conflictNote?.let { " You chose Skip; $it." } ?: "")
        "skip" -> "Goal: skip. Nothing is planned for this group."
        "maintain" -> "Goal: maintain. ${n(gt.t)} hard sets a week keep what you have." +
            (if (w.targets.deload.active) " Deload week: ${n(gt.targetEffective)}." else "")
        else -> "Goal: improve${if (gt.focus) " with focus" else ""}. ${n(w.targets.base)} hard sets a week for your training experience ($age)" +
            (if (gt.focus) ", plus 4 for focus" else "") + "." +
            (if (w.targets.deload.active) " Deload week: ${n(gt.targetEffective)} this week, synced with your challenge." else "")
    }
}

private fun label(c: AppContainer, group: String): String =
    c.taxonomy.groups.firstOrNull { it.id == group }?.labelEn?.substringBefore(" (") ?: group
