package dev.fitnessapp.app.ui.you

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.planning.Mesocycles
import dev.fitnessapp.app.ui.challenges.GateRowView
import dev.fitnessapp.app.ui.common.ChipButton
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.FitScaffold
import dev.fitnessapp.app.ui.common.FitTextField
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.LoadingDumbbell
import dev.fitnessapp.app.ui.common.Segmented
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.common.undoTimeoutMs
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.uidet.BestKind
import dev.fitnessapp.app.uidet.PersonalBest
import dev.fitnessapp.app.uidet.ProgressAxis
import dev.fitnessapp.app.uidet.ProgressMetric
import dev.fitnessapp.app.uidet.ProgressPoint
import dev.fitnessapp.app.uidet.ProgressSeries
import dev.fitnessapp.app.uidet.UiProgress
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** The tabs of UX 15.2. */
enum class ProgressTab(val label: String) { EXERCISE("Exercise"), BODYWEIGHT("Bodyweight"), VOLUME("Volume"), BESTS("Bests") }

/** The range chips of UX 19.7.1: 4 weeks, 12 weeks (default, `window_weeks`), 6 months, all. */
enum class ProgressRange(val label: String, val code: String, val weeks: Int?) { W4("4 w", "4w", 4), W12("12 w", "12w", 12), M6("6 m", "6m", 26), ALL("All", "all", null) }

/** What the user picked on the screen. */
data class ProgressPick(
    val tab: ProgressTab = ProgressTab.EXERCISE,
    val exerciseId: String? = null,
    val metric: ProgressMetric? = null,
    val axis: ProgressAxis = ProgressAxis.ADDED,
    val range: ProgressRange = ProgressRange.W12,
    val side: String? = null,
    val table: Boolean = false,
    val point: String? = null,
    val volumeGroup: String? = null,
)

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** Bests listed under the chart before "Show all". */
private const val BESTS_SHOWN = 5
private val MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
private val ZONE = ZoneId.of("Europe/Amsterdam")

/** You → Progress (UX 15.2, D86 (5)). */
@Composable
fun ProgressRoute(c: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var ui by remember { mutableStateOf<ProgressUi?>(null) }
    var volume by remember { mutableStateOf<List<VolumeWeekRow>?>(null) }
    var pick by remember { mutableStateOf(ProgressPick()) }
    var reload by remember { mutableIntStateOf(0) }
    var change by remember { mutableStateOf<MesoFamily?>(null) }
    var mesoBusy by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(reload) { ui = runCatching { ProgressData.load(c) }.getOrNull() ?: ProgressUi(null, emptyList(), null, null, emptyList(), emptyList()) }
    LaunchedEffect(pick.tab) { if (pick.tab == ProgressTab.VOLUME && volume == null) volume = runCatching { ProgressData.volume(c) }.getOrDefault(emptyList()) }
    val u = ui
    val exerciseId = pick.exerciseId ?: u?.defaultExerciseId
    val metric = u?.exercises?.firstOrNull { it.exerciseId == exerciseId }?.let { e -> pick.metric?.takeIf { it in UiProgress.metrics(e.sets) } ?: UiProgress.metrics(e.sets).firstOrNull() }
    LaunchedEffect(exerciseId, metric, pick.axis, pick.range, pick.tab) {
        if (u != null && pick.tab == ProgressTab.EXERCISE && exerciseId != null) {
            c.uiEvent(
                "progress_viewed", "you.progress",
                mapOf("exercise_id" to JsonPrimitive(exerciseId), "metric" to JsonPrimitive(metric?.code ?: "none"), "axis" to JsonPrimitive(pick.axis.code), "range" to JsonPrimitive(pick.range.code)),
            )
        }
    }
    FitScaffold("Progress", onBack, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        if (u == null) {
            Column(Modifier.padding(pad).padding(16.dp)) { LoadingDumbbell(listOf("Reading your sessions", "Drawing the charts")) }
        } else {
            ProgressScreen(
                u, volume, pick.copy(exerciseId = exerciseId, metric = metric), c.params, pad,
                onPick = { p ->
                    if (p.axis != pick.axis) scope.launch { c.uiEvent("progress_axis_toggled", "you.progress", mapOf("axis" to JsonPrimitive(p.axis.code))) }
                    if (p.point != null && p.point != pick.point) scope.launch { c.uiEvent("progress_point_opened", "you.progress", mapOf("set_id" to JsonPrimitive(p.point))) }
                    pick = p
                },
                onChange = { change = it },
                mesoBusy = mesoBusy,
                onMeso = { on ->
                    // D100: mesocycles are opt-in; turning on starts a fresh one from today's preferences.
                    mesoBusy = true
                    scope.launch {
                        runCatching { if (on) Mesocycles.activate(c) else Mesocycles.deactivate(c) }
                        c.fgsEvent("settings_changed", mapOf("key" to "mesocycles_enabled", "from" to !on, "to" to on))
                        mesoBusy = false
                        reload++
                    }
                },
            )
        }
    }
    change?.let { fam ->
        val cur = remember(fam) { mutableStateOf<Mesocycles.Current?>(null) }
        val choices = remember(fam) { mutableStateOf<List<Pair<String, String>>?>(null) }
        LaunchedEffect(fam) {
            cur.value = Mesocycles.current(c)
            choices.value = Mesocycles.choices(c, fam.family).map { it.slug to it.nameEn }
        }
        ChangeSheet(fam, choices.value, onDismiss = { change = null }) { from, to ->
            change = null
            scope.launch {
                val current = cur.value ?: return@launch
                Mesocycles.change(c, current, fam.family, from, to)
                reload++
                val back = snackbar.showSnackbar(
                    "Main lift changed", actionLabel = "Undo", withDismissAction = false,
                    duration = if (undoTimeoutMs(context) > 4000) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (back == SnackbarResult.ActionPerformed) {
                    Mesocycles.current(c)?.let { Mesocycles.change(c, it, fam.family, to, from) }
                    reload++
                }
            }
        }
    }
}

/** The stateless screen (Roborazzi renders it with fixture data). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressScreen(
    u: ProgressUi,
    volume: List<VolumeWeekRow>?,
    pick: ProgressPick,
    params: ParamView,
    pad: PaddingValues,
    onPick: (ProgressPick) -> Unit,
    onChange: (MesoFamily) -> Unit,
    mesoBusy: Boolean = false,
    onMeso: (Boolean) -> Unit = {},
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var tabsY by remember { mutableIntStateOf(0) }
    val open = { id: String ->
        onPick(pick.copy(tab = ProgressTab.EXERCISE, exerciseId = id, metric = null, point = null))
        // The chart sits below the mesocycle card: scroll the tabs to the top so it is in view.
        scope.launch { scroll.animateScrollTo(tabsY) }
        Unit
    }
    Column(
        Modifier.padding(pad).fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (u.mesoOff) MesoOffTile(mesoBusy) { onMeso(true) } else u.meso?.let { MesoTile(it, onLift = open, onChange, mesoBusy) { onMeso(false) } }
        Segmented(
            ProgressTab.entries, pick.tab, { it.label }, { onPick(pick.copy(tab = it)) },
            Modifier.onGloballyPositioned { tabsY = it.positionInParent().y.toInt() },
        )
        when (pick.tab) {
            ProgressTab.EXERCISE -> ExerciseTab(u, pick, params, onPick)
            ProgressTab.BODYWEIGHT -> BodyweightTab(u)
            ProgressTab.VOLUME -> VolumeTab(volume, pick, onPick)
            ProgressTab.BESTS -> BestsTab(u, onOpen = open)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MesoOffTile(busy: Boolean, onTurnOn: () -> Unit) {
    Tile(spacing = 6.dp) {
        TileLabel("This mesocycle")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Off", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            LinkButton(if (busy) "Starting…" else "Turn on", onTurnOn, enabled = !busy)
        }
        Text(
            "Pick main lifts for each family and keep them for a block of weeks, so their progress is easy to track. Main lifts come first in every session that trains their muscles.",
            style = MaterialTheme.typography.bodySmall, color = Fit.Muted,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MesoTile(m: MesoCard, onLift: (String) -> Unit, onChange: (MesoFamily) -> Unit, busy: Boolean, onTurnOff: () -> Unit) {
    Tile(spacing = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TileLabel("This mesocycle", Modifier.weight(1f))
            LinkButton("Turn off", onTurnOff, color = Fit.Muted, enabled = !busy)
        }
        Text(m.title, style = MaterialTheme.typography.titleMedium)
        m.families.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.label, style = MaterialTheme.typography.bodyMedium, color = Fit.Muted, modifier = Modifier.width(52.dp))
                if (f.lifts.isEmpty()) {
                    Text(f.note.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Fit.Muted, modifier = Modifier.weight(1f))
                } else {
                    Column(Modifier.weight(1f)) {
                        f.lifts.forEach { l ->
                            Text(
                                l.name, style = MaterialTheme.typography.bodyMedium, color = Fit.Ink,
                                modifier = Modifier.clickable { onLift(l.exerciseId) }.padding(vertical = 4.dp),
                            )
                        }
                    }
                    LinkButton("Change", { onChange(f) })
                }
            }
        }
        if (m.rotated.isNotEmpty()) TileDivider()
        m.rotated.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExerciseTab(u: ProgressUi, pick: ProgressPick, params: ParamView, onPick: (ProgressPick) -> Unit) {
    var picker by remember { mutableStateOf(false) }
    val e = u.exercises.firstOrNull { it.exerciseId == pick.exerciseId }
    Tile(spacing = 0.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { picker = true }, verticalAlignment = Alignment.CenterVertically) {
            Text(e?.name ?: "Pick an exercise", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("▾", style = MaterialTheme.typography.titleMedium, color = Fit.Accent)
        }
    }
    if (picker) ExercisePicker(u.exercises, onDismiss = { picker = false }) { picker = false; onPick(pick.copy(exerciseId = it, metric = null, side = null, point = null)) }
    if (e == null) {
        Text("Log 2 sessions of an exercise to see a trend.", color = Fit.Muted)
        return
    }
    val metrics = UiProgress.metrics(e.sets)
    val metric = pick.metric ?: metrics.firstOrNull()
    if (metric == null || e.sessions < 2) {
        Text("Log 2 sessions of an exercise to see a trend.", color = Fit.Muted)
        if (metric == null) return
    }
    if (metrics.size > 1) ChoiceChips(metrics, metric, { it.label }) { onPick(pick.copy(metric = it, point = null)) }
    // Without a bodyweight part (a barbell, a machine) added and system kg are the same numbers.
    if (metric == ProgressMetric.STRENGTH && e.exercise.fBw > 0.0) {
        Segmented(ProgressAxis.entries, pick.axis, { if (it == ProgressAxis.ADDED) "Added kg" else "System kg" }, { onPick(pick.copy(axis = it, point = null)) })
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProgressRange.entries.forEach { r -> ChipButton(r.label, pick.range == r, { onPick(pick.copy(range = r, point = null)) }) }
    }
    val levels = if (metric == ProgressMetric.HOLDS) e.hold else e.sse
    val full = UiProgress.series(metric!!, e.sets, levels, pick.axis, e.exercise, params)
    val sides = full.points.map { it.side }.distinct().sorted()
    if (sides.size > 1) {
        ChoiceChips(listOf("both") + sides, pick.side ?: "both", { if (it == "both") "Both" else it }) { onPick(pick.copy(side = it.takeIf { s -> s != "both" }, point = null)) }
    }
    val bySide = full.copy(
        points = full.points.filter { pick.side == null || it.side == pick.side },
        band = full.band.filter { pick.side == null || it.side == pick.side },
        bests = full.bests.filter { pick.side == null || it.side == pick.side },
    )
    val s = UiProgress.window(bySide, pick.range.weeks)
    val fmt = valueFormat(s)
    val gate = e.gate?.takeIf { metric == ProgressMetric.STRENGTH && pick.axis == ProgressAxis.ADDED }
    Tile(spacing = 8.dp) {
        TileLabel(
            when (metric) {
                ProgressMetric.STRENGTH -> "Estimated 1RM per session (best set)"
                ProgressMetric.REPS -> "Reps per session (best set)"
                ProgressMetric.HOLDS -> "Best hold per session"
                ProgressMetric.ASSIST -> "Least assistance with a clean rep"
            },
        )
        val precise = { x: Double -> (if (x >= 0) "+" else "−") + Numbers.num(abs(x), 2) + " kg" }
        ProgressChart(s, gate?.thresholdNow, gate?.let { "gate ${precise(it.thresholdNow)}" }, fmt, pick.point) { onPick(pick.copy(point = it)) }
        // One estimate per side: with both sides shown, the last band alone would be one arm's.
        val latest = s.band.map { it.side }.distinct().sorted().mapNotNull { side -> s.band.lastOrNull { it.side == side } }
        val name = if (metric == ProgressMetric.HOLDS) "Hold estimate" else "e1RM estimate (SSE-1)"
        latest.forEach {
            val side = if (latest.size > 1) " · " + sideName(it.side) else ""
            Text("$name$side ${fmt(it.m)} (80 %: ${fmt(it.lo)} to ${fmt(it.hi)})", style = MaterialTheme.typography.bodyMedium)
        }
        val newBests = s.bests.size
        Text(
            "${s.points.map { it.sessionId }.distinct().size} sessions · " + if (newBests == 1) "1 new best" else "$newBests new bests",
            style = MaterialTheme.typography.bodySmall, color = Fit.Muted,
        )
        gate?.let { g -> UiProgress.gateLines(g, null) { x -> if (x == g.thresholdNow || x == g.thresholdTest) precise(x) else fmt(x) }.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) } }
        s.points.firstOrNull { it.setId == pick.point }?.let { p -> PointDetail(p, fmt) }
        LinkButton(if (pick.table) "Hide table" else "Show as table", { onPick(pick.copy(table = !pick.table)) })
        if (pick.table) Table(s, fmt)
    }
    if (s.bests.isNotEmpty()) {
        var all by remember(e.exerciseId) { mutableStateOf(false) }
        Tile(spacing = 4.dp) {
            TileLabel("Personal bests")
            val list = s.bests.reversed()
            (if (all) list else list.take(BESTS_SHOWN)).forEach { b -> Text(bestLine(b, e.exercise.fBw > 0.0), style = MaterialTheme.typography.bodySmall) }
            if (list.size > BESTS_SHOWN && !all) LinkButton("Show all ${list.size}", { all = true })
        }
    }
    u.gates?.let { g ->
        Tile(spacing = 8.dp) {
            TileLabel(g.header)
            g.rows.forEachIndexed { i, r ->
                if (i > 0) TileDivider()
                GateRowView(r)
            }
        }
    }
}

private fun valueFormat(s: ProgressSeries): (Double) -> String = { x ->
    when (s.unit) {
        "reps" -> "${x.toInt()} reps"
        "s" -> Numbers.num(x, 1) + " s"
        else -> if (s.signed) (if (x >= 0) "+" else "−") + Numbers.num(abs(x), 1) + " kg" else Numbers.num(x, 1) + " kg"
    }
}

/** One best in words (UX 12.3, 15.2): an e1RM best of a bodyweight lift ([signed]) reads as added kg (C32). */
fun bestWhat(b: PersonalBest, signed: Boolean): String {
    val n = { x: Double -> Numbers.num(x, 1) }
    val kg = { x: Double -> (if (signed) (if (x >= 0) "+" else "−") + n(abs(x)) else n(x)) + " kg" }
    return when (b.kind) {
        BestKind.E1RM -> if (signed && b.addedValue != null && b.addedPrevious != null) {
            "estimated 1RM ${kg(b.addedValue)}, up from ${kg(b.addedPrevious)}"
        } else {
            "estimated 1RM ${n(b.value)} kg, up from ${n(b.previous)} kg"
        }
        BestKind.REPS -> "${b.value.toInt()} reps at ${n(b.loadKg ?: 0.0)} kg, best was ${b.previous.toInt()}"
        BestKind.HOLD -> "${n(b.value)} s, up from ${n(b.previous)} s"
        BestKind.ASSIST -> "${n(b.value)} kg assistance, down from ${n(b.previous)} kg"
    }
}

private fun bestLine(b: PersonalBest, signed: Boolean): String =
    DAY.format(b.date) + (if (b.side == "both") "" else " (${b.side})") + ": " + bestWhat(b, signed)

@Composable
private fun PointDetail(p: ProgressPoint, fmt: (Double) -> String) {
    val s = p.set
    val load = s.shownKg?.let { (if (s.bodyweightKg != null && s.e1rmAddedKg != null && it >= 0 && s.e1rmAddedKg != s.e1rmKg) "+" else "") + Numbers.num(it, 1) + " kg" }
    val work = listOfNotNull(s.reps?.let { "$it reps" }, s.holdS?.let { Numbers.num(it, 1) + " s" }, load, s.assistKg?.let { "assist " + Numbers.num(it, 1) + " kg" }).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${DAY.format(p.date)}${if (p.side == "both") "" else " · ${p.side}"}: ${fmt(p.y)}" + if (p.test) " · test day" else "", style = MaterialTheme.typography.bodyMedium)
        Text("Set: $work" + (s.bodyweightKg?.let { " · bodyweight ${Numbers.num(it, 1)} kg" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
    }
}

@Composable
private fun Table(s: ProgressSeries, fmt: (Double) -> String) {
    val band = s.band.associateBy { it.atMs to it.side }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        s.points.reversed().forEach { p ->
            val b = band[p.atMs to p.side]
            Row {
                Text(DAY.format(p.date) + if (p.side == "both") "" else " ${p.side}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
                Text(fmt(p.y) + (if (p.best) " ★" else "") + (if (p.test) " ◆" else ""), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                b?.let { Text("est ${fmt(it.m)}", style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
            }
        }
    }
}

/**
 * The chart of UX 15.2 and 19.7.1: raw points at `raw_point_opacity`, the SSE-1 level line with its 80 % band at
 * `band_opacity`, stars on bests, diamonds on test days and the gate as a dashed line. A tap picks the nearest point.
 */
@Composable
fun ProgressChart(
    s: ProgressSeries, gateY: Double?, gateLabel: String?, fmt: (Double) -> String, selected: String?,
    title: String = s.metric.label.lowercase(), onTap: (String) -> Unit,
) {
    if (s.points.isEmpty() && s.band.isEmpty()) {
        Text("No sessions in this range.", color = Fit.Muted)
        return
    }
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = Fit.Muted, fontSize = 11.sp)
    val ys = s.points.map { it.y } + s.band.flatMap { listOf(it.lo, it.hi) } + listOfNotNull(gateY)
    val pad = ((ys.max() - ys.min()) * 0.08).coerceAtLeast(if (s.unit == "reps") 1.0 else 0.5)
    val minY = ys.min() - pad
    val maxY = ys.max() + pad
    val xs = s.points.map { it.atMs } + s.band.map { it.atMs }
    val minX = xs.min()
    val maxX = xs.max()
    val sides = (s.points.map { it.side } + s.band.map { it.side }).distinct().sorted()
    val desc = "Chart of $title: " + s.points.takeLast(3).joinToString { "${DAY.format(it.date)} ${fmt(it.y)}" }
    Canvas(
        Modifier.fillMaxWidth().height(220.dp).semantics { contentDescription = desc }
            .pointerInput(s.points) {
                detectTapGestures { o ->
                    val left = 52.dp.toPx()
                    val w = size.width - left - 8.dp.toPx()
                    fun x(ms: Long) = left + if (maxX == minX) w / 2 else (ms - minX).toFloat() / (maxX - minX) * w
                    s.points.minByOrNull { abs(x(it.atMs) - o.x) }?.let { onTap(it.setId) }
                }
            },
    ) {
        val left = 52.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val w = size.width - left - 8.dp.toPx()
        fun x(ms: Long) = left + if (maxX == minX) w / 2 else (ms - minX).toFloat() / (maxX - minX) * w
        fun y(v: Double) = (bottom - (v - minY) / (maxY - minY) * (bottom - 8.dp.toPx())).toFloat()
        // Grid and y labels.
        (0..3).forEach { i ->
            val v = minY + (maxY - minY) * i / 3.0
            drawLine(Fit.Line, Offset(left, y(v)), Offset(left + w, y(v)), 1.dp.toPx())
            // Axis ticks keep one decimal: whole-number labels repeat on a narrow range ("9, 9").
            val tick = (if (s.signed) (if (v >= 0) "+" else "−") else if (v < 0) "−" else "") + Numbers.num(abs(v), 1)
            val t = measurer.measure(tick, label)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), y(v) - t.size.height / 2f))
        }
        // Month labels on the x axis.
        var month: Int? = null
        s.points.forEach { p ->
            if (p.date.monthValue != month) {
                month = p.date.monthValue
                val t = measurer.measure(MONTH.format(p.date), label)
                drawText(t, topLeft = Offset((x(p.atMs) - t.size.width / 2f).coerceIn(left, size.width - t.size.width), bottom + 4.dp.toPx()))
            }
        }
        gateY?.let { g ->
            drawLine(Fit.Advisory, Offset(left, y(g)), Offset(left + w, y(g)), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            gateLabel?.let { gl ->
                val t = measurer.measure(gl, TextStyle(color = Fit.Advisory, fontSize = 11.sp))
                drawText(t, topLeft = Offset(left + w - t.size.width, y(g) - t.size.height - 2.dp.toPx()))
            }
        }
        sides.forEachIndexed { i, side ->
            val color = if (i == 0) Fit.Accent else Fit.Ink
            val band = s.band.filter { it.side == side }
            if (band.size > 1) {
                val area = Path().apply {
                    moveTo(x(band.first().atMs), y(band.first().hi))
                    band.drop(1).forEach { lineTo(x(it.atMs), y(it.hi)) }
                    band.reversed().forEach { lineTo(x(it.atMs), y(it.lo)) }
                    close()
                }
                drawPath(area, color.copy(alpha = 0.12f))
                val line = Path().apply {
                    moveTo(x(band.first().atMs), y(band.first().m))
                    band.drop(1).forEach { lineTo(x(it.atMs), y(it.m)) }
                }
                drawPath(line, color, style = Stroke(2.dp.toPx()))
            } else if (band.size == 1) {
                drawLine(color.copy(alpha = 0.36f), Offset(x(band[0].atMs), y(band[0].hi)), Offset(x(band[0].atMs), y(band[0].lo)), 6.dp.toPx())
            }
            // Equal values on both sides would hide one side's point: nudge the sides apart.
            val dodge = if (sides.size > 1) (i - (sides.size - 1) / 2f) * 4.dp.toPx() else 0f
            s.points.filter { it.side == side }.forEach { p ->
                val c = Offset(x(p.atMs) + dodge, y(p.y))
                when {
                    p.test -> drawPath(diamond(c, 6.dp.toPx()), color)
                    else -> drawCircle(color.copy(alpha = 0.6f), 3.5.dp.toPx(), c)
                }
                if (p.best) drawPath(star(Offset(c.x, c.y - 12.dp.toPx()), 6.dp.toPx()), Fit.Accent)
                if (p.setId == selected) drawCircle(Fit.Ink, 6.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
    if (sides.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            sides.forEachIndexed { i, side -> Text("● ${sideName(side)}", color = if (i == 0) Fit.Accent else Fit.Ink, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun sideName(side: String) = when (side) { "L" -> "Left"; "R" -> "Right"; else -> side }

private fun diamond(c: Offset, r: Float) = Path().apply {
    moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close()
}

private fun star(c: Offset, r: Float) = Path().apply {
    for (i in 0 until 10) {
        val a = Math.PI / 2 + i * Math.PI / 5
        val rr = if (i % 2 == 0) r else r * 0.45f
        val px = c.x + (rr * kotlin.math.cos(a)).toFloat()
        val py = c.y - (rr * kotlin.math.sin(a)).toFloat()
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
}

@Composable
private fun BodyweightTab(u: ProgressUi) {
    if (u.bodyweight.isEmpty()) {
        Text("Log your bodyweight from You to see it here.", color = Fit.Muted)
        return
    }
    val pts = u.bodyweight.mapIndexed { i, b ->
        ProgressPoint("bw$i", "bw$i", b.date.atStartOfDay(ZONE).toInstant().toEpochMilli(), b.date, "both", b.kg, b.test, false, b.kg, dev.fitnessapp.app.uidet.ProgressSet("bw$i", "bw$i", 0, b.date, "both", b.test))
    }
    val line = u.bodyweight.mapIndexedNotNull { i, b -> b.ewma?.let { dev.fitnessapp.app.uidet.ProgressBand(pts[i].atMs, "both", it, it, it) } }
    val s = ProgressSeries(ProgressMetric.STRENGTH, ProgressAxis.SYSTEM, "kg", false, pts, line, emptyList())
    Tile(spacing = 8.dp) {
        ProgressChart(s, null, null, { Numbers.num(it, 1) + " kg" }, null, title = "bodyweight") {}
        u.bodyweight.last().let { b ->
            Text("Last weigh-in ${Numbers.num(b.kg, 1)} kg (${DAY.format(b.date)})" + (b.ewma?.let { " · trend ${Numbers.num(it, 1)} kg, used for loads" } ?: ""), style = MaterialTheme.typography.bodyMedium)
        }
        Text("Diamonds are test-day weigh-ins; the line is the average the app uses for prescriptions.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VolumeTab(rows: List<VolumeWeekRow>?, pick: ProgressPick, onPick: (ProgressPick) -> Unit) {
    if (rows == null) {
        LoadingDumbbell(listOf("Adding up your weeks"))
        return
    }
    val groups = rows.flatMap { it.done.keys }.distinct()
    if (groups.isEmpty()) {
        Text("No weeks with sets yet.", color = Fit.Muted)
        return
    }
    val group = pick.volumeGroup?.takeIf { it in groups } ?: groups.first()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { g -> ChipButton(g.replace('_', ' ').replaceFirstChar { it.uppercase() }, g == group, { onPick(pick.copy(volumeGroup = g)) }) }
    }
    Tile(spacing = 10.dp) {
        TileLabel("Hard sets per week against the target")
        rows.forEach { r ->
            val done = r.done[group] ?: 0.0
            val target = r.target[group] ?: 0.0
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Text("Week ${r.isoWeek.substringAfter("-W").trimStart('0')}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("${Numbers.num(done, 1)} of ${Numbers.num(target, 1)}", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                }
                dev.fitnessapp.app.ui.common.ProgressBar(if (target > 0) (done / target).toFloat() else 0f, height = 6.dp)
            }
        }
    }
}

@Composable
private fun BestsTab(u: ProgressUi, onOpen: (String) -> Unit) {
    if (u.bests.isEmpty()) {
        Text("No personal bests yet. A best needs at least one earlier session of the exercise.", color = Fit.Muted)
        return
    }
    Tile(spacing = 0.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        u.bests.forEachIndexed { i, b ->
            if (i > 0) TileDivider()
            Column(Modifier.fillMaxWidth().clickable { onOpen(b.exerciseId) }.padding(vertical = 8.dp)) {
                Text("${b.name} · ${b.best.kind.label}", style = MaterialTheme.typography.bodyMedium)
                Text(bestLine(b.best, b.signed), style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExercisePicker(exercises: List<ExerciseProgress>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FitTextField(q, { q = it }, label = "Search", singleLine = true)
            Column(Modifier.verticalScroll(rememberScrollState())) {
                exercises.filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }.sortedByDescending { it.lastMs }.forEach { e ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(e.exerciseId) }.padding(vertical = 10.dp)) {
                        Text(e.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${e.sessions} ${if (e.sessions == 1) "session" else "sessions"} · last ${DAY.format(Instant.ofEpochMilli(e.lastMs).atZone(ZONE))}",
                            style = MaterialTheme.typography.bodySmall, color = Fit.Muted,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChangeSheet(f: MesoFamily, choices: List<Pair<String, String>>?, onDismiss: () -> Unit, onApply: (String, String) -> Unit) {
    var from by remember(f) { mutableStateOf(f.lifts.first().slug) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${f.label} main lift", style = MaterialTheme.typography.titleLarge)
            // SEL-1 S0 only adds an anchor when its muscles have sets planned that day (D86 (3)).
            Text("Until the mesocycle ends, main lifts come first in every ${f.label.lowercase()} session that trains their muscles.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            if (f.lifts.size > 1) {
                Text("Replace", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    f.lifts.forEach { l -> ChipButton(l.name, from == l.slug, { from = l.slug }) }
                }
            }
            Text("With", style = MaterialTheme.typography.labelLarge)
            if (choices == null) {
                LoadingDumbbell(listOf("Finding exercises"))
            } else {
                val taken = f.lifts.map { it.slug }.toSet()
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    choices.filter { it.first !in taken }.forEach { (slug, name) ->
                        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable { onApply(from, slug) }.padding(vertical = 12.dp))
                    }
                }
            }
        }
    }
}
