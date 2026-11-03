package dev.fitnessapp.app.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.fitnessapp.algo.challenge.calendar.CalendarLink
import dev.fitnessapp.algo.challenge.calendar.WritePrompt
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.calendar.CalendarClassification
import dev.fitnessapp.algo.core.calendar.AnchorKind
import dev.fitnessapp.algo.core.calendar.SlotKey
import dev.fitnessapp.algo.planning.weekplan.PlanIt
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.planning.weekplan.FixedItem
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.algo.planning.weekplan.WeeklyPlanner
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.uidet.UiTrim
import dev.fitnessapp.app.planning.SessionBuild
import dev.fitnessapp.app.ui.challenges.ChallengeLines
import dev.fitnessapp.app.planning.PlanItContent
import dev.fitnessapp.app.planning.PlanningFacts
import dev.fitnessapp.app.planning.WeekChallenge
import dev.fitnessapp.app.planning.WeekPlans
import dev.fitnessapp.app.planning.WeekVolume
import dev.fitnessapp.app.ui.calendar.EventSheet
import dev.fitnessapp.app.ui.calendar.errorText
import dev.fitnessapp.app.ui.calendar.kindText
import dev.fitnessapp.app.ui.calendar.linkStateText
import dev.fitnessapp.app.ui.common.Banner
import dev.fitnessapp.app.ui.common.ChipButton
import dev.fitnessapp.app.ui.common.BannerKind
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.LoadingDumbbell
import dev.fitnessapp.app.ui.common.PLANNER_LINES
import dev.fitnessapp.app.ui.common.Pill
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.common.showUndo
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.uidet.UiVolumeBars
import dev.fitnessapp.app.uidet.LoggedSession
import dev.fitnessapp.app.uidet.UiWeekGrid
import dev.fitnessapp.app.uidet.UiWeekRows
import dev.fitnessapp.app.uidet.WeekGrid
import dev.fitnessapp.app.uidet.VolumeRow
import dev.fitnessapp.app.uidet.VolumeState
import dev.fitnessapp.app.uidet.WeekAction
import dev.fitnessapp.app.uidet.WeekRow
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import dev.fitnessapp.data.telemetry.EventDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One page of the Week tab (UX 10, D82): one ISO week's rows, header facts and volume. */
private class WeekPage(
    val monday: LocalDate,
    val offset: Int,
    /** This week's plan, or next week's provisional one; null for a past week and the weeks after next. */
    val run: WeekPlans.Run?,
    /** This week's volume, or a past week's; null for the weeks ahead. */
    val volume: WeekVolume?,
    /** The day sheet's rows (D82 (2)). */
    val rows: List<WeekRow>,
    /** The calendar grid (D83); null for the weeks after next. */
    val grid: WeekGrid?,
    val challenge: WeekChallenge?,
    val degraded: Boolean,
)

/**
 * Loads the page [offset] ISO weeks from this one (UX 10.7): this and next week from the week planner, a past week from
 * its logged sessions and volume (read-only), a later week as the challenge's block structure only.
 */
private suspend fun load(c: AppContainer, offset: Int): WeekPage = withContext(Dispatchers.Default) {
    val now = c.clock.now()
    val zone = PlanningFacts.ZONE
    val today = Week.localDate(now, zone)
    val monday = Week.isoWeekStart(today).plusWeeks(offset.toLong())
    val snap = c.calendar.snapshot.value
    val degraded = snap.status != CalendarClassification.OK
    val canPlan = !degraded && snap.writeOff == null && snap.target != null
    val labels = c.catalogModel.taxonomy.activeGroups.associate { it.id to it.labelEn }
    fun rows(plan: WeekPlan?, done: List<LoggedSession>) =
        UiWeekRows.rows(plan, monday, snap.anchors, snap.instances, snap.links, snap.prompts, done.map { it.date to it.label }, today, now, zone, canPlan) { g -> labels[g] ?: g }
    fun grid(plan: WeekPlan?, done: List<LoggedSession>) = UiWeekGrid.grid(plan, monday, snap.anchors, snap.instances, done, today, now, zone)
    when {
        offset == 0 -> {
            val run = WeekPlans.thisWeek(c, now)
            val shown = UiWeekRows.withMoved(run.plan, c.settings.movedSuggestions(run.plan.isoWeek), zone)
            WeekPage(monday, 0, run, run.volume, rows(shown, emptyList()), grid(shown, emptyList()), run.volume.challenge, degraded)
        }
        offset == 1 -> {
            val run = WeekPlans.nextWeek(c, WeekPlans.thisWeek(c, now), now)
            val shown = UiWeekRows.withMoved(run.plan, c.settings.movedSuggestions(run.plan.isoWeek), zone)
            WeekPage(monday, 1, run, null, rows(shown, emptyList()), grid(shown, emptyList()), runCatching { WeekPlans.blockAt(c, monday) }.getOrNull(), degraded)
        }
        offset < 0 -> {
            val volume = runCatching { WeekVolume.compute(c, Week.atLocal(monday.plusDays(6), LocalTime.of(23, 59), zone)) }.getOrNull()
            val done = c.db.sessions().finishedInIsoWeek(Week.isoWeek(monday))
                .filter { it.habit_id == null && it.deleted_at == null && it.status == "finished" }
                .map { s -> LoggedSession(LocalDate.parse(s.local_date), sessionLabel(s), s.started_at?.let(Instant::ofEpochMilli), s.finished_at?.let(Instant::ofEpochMilli)) }
            WeekPage(monday, offset, null, volume, rows(null, done), grid(null, done), volume?.challenge, degraded)
        }
        else -> WeekPage(monday, offset, null, null, emptyList(), null, runCatching { WeekPlans.blockAt(c, monday) }.getOrNull(), degraded)
    }
}

private fun sessionLabel(s: WorkoutSessionEntity): String = when {
    s.challenge_slot == "TEST" -> "Test day"
    s.challenge_slot != null -> "Session ${s.challenge_slot}"
    s.families.isNotEmpty() -> s.families.joinToString(" + ") { it.lowercase() }.replaceFirstChar { it.uppercase() }
    else -> "Gym session"
}

/**
 * The Week tab (UX 10, D82): the week's header, one stacked row per day (the planner's line, the calendar's events and
 * the row's action: Plan it, Adopt or Add to calendar), the day sheet, week navigation, and the muscle volume bars of
 * 10.8. [focus] opens the week of that day with its row marked (a tap on Today's week strip).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeekRoute(
    c: AppContainer,
    modifier: Modifier,
    onSetGoals: () -> Unit,
    onOpenCalendars: () -> Unit = {},
    onOpenToday: () -> Unit = {},
    focus: LocalDate? = null,
    onFocusUsed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val snap by c.calendar.snapshot.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<WeekPage?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    var busy by remember { mutableStateOf(false) }
    // A tapped empty spot waiting for its "New gym session" sheet (D84 (1)).
    var placing by remember { mutableStateOf<java.time.Instant?>(null) }
    // Dropped blocks held at their drop place until the reloaded week shows them there (D84 (2)).
    var pending by remember { mutableStateOf<Map<String, Pair<LocalDate, Int>>>(emptyMap()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        val thisMonday = Week.isoWeekStart(Week.localDate(c.clock.now(), PlanningFacts.ZONE))
        offset = ChronoUnit.WEEKS.between(thisMonday, Week.isoWeekStart(f)).toInt()
        onFocusUsed()
    }
    LaunchedEffect(offset) { runCatching { c.screenViewed("week", mapOf("iso_week_offset" to offset)) } }
    LaunchedEffect(tick, offset, snap.refreshedAt) {
        runCatching { load(c, offset) }
            .onSuccess { p ->
                page = p
                failed = null
                if (p.offset == 0) shownOnce(c, p)
                // D82 (4): the unedited "Plan it" events of this week and next follow the plan.
                p.run?.let { r -> launch(Dispatchers.Default) { runCatching { PlanItContent.offer(c, r.plan) } } }
            }
            .onFailure {
                // A load cancelled because its inputs changed (a calendar refresh) is not a failure: the next one replaces it.
                if (it is kotlinx.coroutines.CancellationException) throw it
                failed = it.message ?: it.javaClass.simpleName
            }
    }

    /** "Plan it" (UX 10.4): one tap writes the event, then "Added to calendar · Undo". */
    fun planIt(p: WeekPage, item: dev.fitnessapp.algo.planning.weekplan.PlanItem) {
        val run = p.run ?: return
        if (busy) return
        busy = true
        scope.launch {
            val t0 = System.currentTimeMillis()
            val (marker, err) = PlanItContent.planIt(c, run.plan, item)
            busy = false
            tick++
            if (marker == null) {
                snackbar.showSnackbar(planItError(err))
                return@launch
            }
            // A suggestion the user had moved is planned now; its moved place must not carry over to a new suggestion.
            c.settings.setMovedSuggestion(run.plan.isoWeek, item.key, null)
            if (snackbar.showUndo("Added to calendar", context) == SnackbarResult.ActionPerformed) {
                PlanItContent.undo(c, marker, t0)?.let { snackbar.showSnackbar(errorText(it)) }
                tick++
            }
        }
    }

    /** The page again at once (after a drop), so the dropped block's place is never shown from stale data. */
    suspend fun reload() {
        runCatching { load(c, offset) }.getOrNull()?.let { page = it }
    }

    /** A tap on an empty spot (D84 (1)): the sheet opens for this and next week, from now on. */
    fun tapSlot(p: WeekPage, date: LocalDate, minute: Int) {
        if (p.run == null) {
            scope.launch { snackbar.showSnackbar("Only this week and next week can be planned.") }
            return
        }
        val start = Week.atLocal(date, LocalTime.of(minute / 60, minute % 60), PlanningFacts.ZONE)
        if (!start.isAfter(c.clock.now())) {
            scope.launch { snackbar.showSnackbar("That time has passed.") }
            return
        }
        placing = start
    }

    /** A drop after a long press (D84 (2)): a suggestion is planned there, an app event moves; both with Undo. */
    fun drop(p: WeekPage, b: dev.fitnessapp.app.uidet.GridBlock, date: LocalDate, minute: Int) {
        if (p.run == null) return
        val start = Week.atLocal(date, LocalTime.of(minute / 60, minute % 60), PlanningFacts.ZONE)
        if (!start.isAfter(c.clock.now())) {
            scope.launch { snackbar.showSnackbar("That time has passed.") }
            return
        }
        val run = p.run
        val item = b.planIt
        if (b.look == dev.fitnessapp.app.uidet.BlockLook.SUGGESTED && item != null) {
            // D84 (2) as amended: a suggestion only moves on the device; it is planned from the day sheet.
            val week = run.plan.isoWeek
            val before = c.settings.movedSuggestions(week)[item.key]
            c.settings.setMovedSuggestion(week, item.key, start)
            pending = pending + (b.key to (date to minute))
            scope.launch {
                reload()
                pending = pending - b.key
                if (snackbar.showUndo("Suggestion moved. Open the day to plan it.", context) == SnackbarResult.ActionPerformed) {
                    c.settings.setMovedSuggestion(week, item.key, before)
                    reload()
                }
            }
            return
        }
        val marker = b.marker ?: return
        val oldStart = b.start ?: return
        val oldEnd = b.end ?: return
        val end = start.plus(Duration.between(oldStart, oldEnd))
        if (busy) return
        busy = true
        pending = pending + (b.key to (date to minute))
        scope.launch {
            val err = c.calendar.moveEvent(marker, start, end)
            reload()
            pending = pending - b.key
            busy = false
            if (err != null) {
                snackbar.showSnackbar(if (err == "past") "That time has passed." else errorText(err))
                return@launch
            }
            // A general event moved to another day carries that day's marker now.
            val now = if (marker.parsedKey() is SlotKey.General) dev.fitnessapp.algo.core.calendar.Marker(marker.uuid, PlanIt.slotKey(date)) else marker
            val text = "Moved to " + date.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)) + " %02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)
            if (snackbar.showUndo(text, context) == SnackbarResult.ActionPerformed) {
                c.calendar.moveEvent(now, oldStart, oldEnd)?.let { snackbar.showSnackbar(errorText(it)) }
                reload()
            }
        }
    }

    fun act(p: WeekPage, a: WeekAction) {
        when (a) {
            is WeekAction.PlanIt -> planIt(p, a.item)
            is WeekAction.Adopt -> scope.launch { c.calendar.adopt(a.prompt)?.let { snackbar.showSnackbar(errorText(it)) }; tick++ }
            is WeekAction.AddToCalendar -> scope.launch { c.calendar.addToCalendar(a.prompt); tick++ }
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val p = page?.takeIf { it.offset == offset }
            val monday = Week.isoWeekStart(Week.localDate(c.clock.now(), PlanningFacts.ZONE)).plusWeeks(offset.toLong())
            val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Week " + monday.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR) + when {
                            offset == 0 -> " · this week"
                            offset == 1 -> " · next week"
                            offset < 0 -> " · past"
                            else -> ""
                        },
                        style = MaterialTheme.typography.titleSmall, color = Fit.Muted,
                    )
                    Text(fmt.format(monday) + " – " + fmt.format(monday.plusDays(6)), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                }
                IconButton(onClick = { offset-- }, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous week") }
                IconButton(onClick = { offset++ }, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next week") }
            }
            p?.challenge?.let { ch ->
                Text(
                    ch.name + " · block ${ch.blockIndex} · week ${ch.blockWeek}" + if (ch.deload) " · deload" else "",
                    style = MaterialTheme.typography.bodyMedium, color = Fit.Muted,
                )
            }
            p?.run?.plan?.let { w ->
                Text("Gym visits: ${w.recommendedN} planned of max ${w.visits.max}", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
            }
            if (p?.degraded == true && offset >= 0) {
                Banner(
                    "Planning without your calendar. Challenge sessions are placed on their preferred days.", BannerKind.ADVISORY,
                    action = "Connect", onAction = onOpenCalendars,
                )
            }
            failed?.let {
                Text("Couldn't plan this week (weekplan error): $it", style = MaterialTheme.typography.bodySmall, color = Fit.Advisory)
                LinkButton("Retry", { tick++ })
            }
            when {
                p == null && failed == null -> Tile(spacing = 6.dp) { LoadingDumbbell(PLANNER_LINES) }
                p == null -> Unit
                offset >= 2 -> Tile(spacing = 6.dp) {
                    Text("Only this week and next week are planned.", style = MaterialTheme.typography.bodyLarge)
                    if (p.challenge == null) Text("Further weeks show your challenge's blocks once you follow one.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                }
                else -> {
                    if (p.run == null && p.rows.none { it.kind == "done" }) {
                        Text("No sessions logged this week.", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                    }
                    p.grid?.let { g ->
                        val now = c.clock.now()
                        WeekGridView(
                            g,
                            nowMin = Week.localTime(now, PlanningFacts.ZONE).let { it.hour * 60 + it.minute },
                            onTapSlot = { date, minute -> tapSlot(p, date, minute) },
                            onMove = { b, date, minute -> drop(p, b, date, minute) },
                            pending = pending,
                            onOpenDay = { d ->
                                day = d
                                val kind = p.rows.firstOrNull { it.date == d }?.kind ?: "rest"
                                scope.launch {
                                    runCatching {
                                        c.sessionRepository.event(
                                            EventDraft(type = "week_day_opened", screen = "week", entityType = "none", payload = mapOf("date" to JsonPrimitive(d.toString()), "row_kind" to JsonPrimitive(kind))),
                                        )
                                    }
                                }
                            },
                        )
                    }
                    GridLegend()
                    if (p.run != null) {
                        Text(
                            "Tap an empty spot to plan a session there. Long-press a gym session or a suggestion to move it.",
                            style = MaterialTheme.typography.bodySmall, color = Fit.Muted,
                        )
                    }
                    if (offset == 1) Text("Next week is provisional: it changes as this week goes.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                }
            }
            val w = p?.volume
            if (p != null && w != null && offset <= 0) {
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
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
    val w = page?.volume
    if (open != null && w != null) GroupSheet(c, w, open!!) { open = null }
    val p = page
    val at = placing
    val run = p?.run
    if (p != null && at != null && run != null) {
        PlaceSheet(c, run, p.rows.firstOrNull { it.date == Week.localDate(at, PlanningFacts.ZONE) }, at, onDismiss = { placing = null }) { type, budget ->
            placing = null
            scope.launch {
                val t0 = System.currentTimeMillis()
                val (marker, err) = PlanItContent.create(c, run.plan, type, at, budget)
                tick++
                if (marker == null) {
                    snackbar.showSnackbar(planItError(err))
                } else if (snackbar.showUndo("Added to calendar", context) == SnackbarResult.ActionPerformed) {
                    PlanItContent.undo(c, marker, t0)?.let { snackbar.showSnackbar(errorText(it)) }
                    tick++
                }
            }
        }
    }
    val d = day
    if (p != null && d != null) {
        p.rows.firstOrNull { it.date == d }?.let { r ->
            DaySheet(c, p, r, busy, onDismiss = { day = null }, onAction = { a -> act(p, a) }, onOpenToday = { day = null; onOpenToday() }, onMessage = { m ->
                scope.launch { snackbar.showSnackbar(m) }
            }, onChanged = { tick++ })
        }
    }
}

private fun planItError(code: String?): String = when (code) {
    "nothing_to_train" -> "Nothing to plan for that day any more."
    "already_planned" -> "That day already has a planned session."
    "no_snapshot" -> "Couldn't store the plan; try again."
    null -> "Couldn't add to calendar."
    else -> "Couldn't add to calendar: " + errorText(code).replaceFirstChar { it.lowercase() }
}

/** What the three block looks mean (D83 (2)). */
@Composable
private fun GridLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(Fit.Accent, null, "Gym")
        LegendItem(Fit.Accent.copy(alpha = 0.14f), Fit.Accent, "Suggested")
        LegendItem(Fit.Raised, null, "Other")
    }
}

@Composable
private fun LegendItem(fill: androidx.compose.ui.graphics.Color, outline: androidx.compose.ui.graphics.Color?, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(fill)
                .then(if (outline != null) Modifier.border(1.dp, outline, RoundedCornerShape(3.dp)) else Modifier),
        )
        Text(text, style = MaterialTheme.typography.labelSmall, color = Fit.Muted)
    }
}

/** The planner's symbol (UX 9.7): a check for done, filled for fixed, open for recommended, T, x, or a dot. */
@Composable
private fun Symbol(s: String) {
    when (s) {
        "v" -> Icon(Icons.Rounded.Check, null, tint = Fit.Accent, modifier = Modifier.size(16.dp))
        "-" -> Box(Modifier.padding(horizontal = 5.dp).size(5.dp).clip(CircleShape).background(Fit.Line))
        else -> Text(if (s == "o") "○" else if (s == "*") "●" else s, style = MaterialTheme.typography.labelLarge, color = if (s == "x") Fit.Muted else Fit.Accent)
    }
}

/** One session of the day sheet: what it is, when, a second line, its exercises and the calendar event behind it. */
private class SheetSession(
    val title: String,
    val status: String,
    val time: String,
    val extra: String?,
    val rows: List<Pair<String, String>>,
    val event: dev.fitnessapp.app.uidet.WeekEvent?,
    val general: Boolean,
)

/**
 * The sessions of [r]'s day for the sheet: the challenge session or test with the exercises of its materialized plan,
 * then the planned or suggested general session with the exercises of its build and the readiness of its families,
 * or a done session.
 */
private suspend fun sessionsOf(c: AppContainer, p: WeekPage, r: WeekRow, snap: dev.fitnessapp.app.calendar.CalendarSnapshot): List<SheetSession> {
    val zone = PlanningFacts.ZONE
    fun hm(i: java.time.Instant) = Week.localTime(i, zone).let { "%02d:%02d".format(Locale.ROOT, it.hour, it.minute) }
    fun note(e: dev.fitnessapp.app.uidet.WeekEvent?): String {
        val m = e?.marker ?: return ""
        val link = snap.links.firstOrNull { it.markerUuid == m.uuid && it.slotKey == m.slotKey } ?: return ""
        return when (link.state) {
            CalendarLink.USER_MOVED -> " · moved by you"
            CalendarLink.USER_EDITED -> " · edited by you, no longer updated"
            else -> ""
        }
    }
    val gym = r.events.filter { it.kind == AnchorKind.CHALLENGE_SESSION || it.kind == AnchorKind.PLANNED_GYM }
    val out = ArrayList<SheetSession>()
    r.challengeItem?.let { ch ->
        val ev = gym.firstOrNull { it.kind == AnchorKind.CHALLENGE_SESSION }
        val enrollment = c.challenges.open().firstOrNull()?.id
        val rows = if (enrollment != null && ch.slot != null) {
            runCatching { ChallengeLines.exercises(c.challengeSessions.materialize(enrollment, ch.slot!!, r.date)).map { it.name to it.detail } }.getOrDefault(emptyList())
        } else emptyList()
        out += SheetSession(
            ch.label, if (ch.kind == FixedItem.KIND_TEST) "Test day" else "Challenge", hm(ch.start) + "–" + hm(ch.end) + note(ev),
            r.addOnItem?.let { a -> "Then a ${UiWeekRows.typeLabel(a.type)} add-on, about ${Math.round(a.estMinutes)} min" }, rows, ev, false,
        )
    }
    val item = r.item
    if (item != null) {
        val planned = item.kind == FixedItem.KIND_PLANNED_GYM
        val ev = if (planned) gym.firstOrNull { it.instanceKey == item.key } else null
        val run = p.run
        val content = if (run != null && !r.notRecommended) {
            val budget = if (planned) java.time.Duration.between(item.start, item.end).toMinutes() - PlanItContent.changeover(c) else PlanItContent.budget(c, item)
            runCatching { PlanItContent.build(c, run.plan, item, budget) }.getOrNull()
        } else null
        val time = if (planned) hm(item.start) + "–" + hm(item.end) + note(ev)
        else hm(item.start) + (content?.let { " · about ${UiTrim.minutes(it.result.estMin)} min" } ?: "")
        val ready = content?.let { pv ->
            SessionBuild.families(pv.result, c).mapNotNull { f ->
                val groups = Groups.FAMILIES.getValue(f).filter { (pv.result.allocation.direct[it] ?: 0.0) > 0.0 }
                groups.minOfOrNull { pv.readiness[it] ?: 1.0 }?.let { v -> f.lowercase().replaceFirstChar { it.uppercase() } + " ${Math.round(v * 100)} %" }
            }.takeIf { it.isNotEmpty() }?.joinToString(" · ", postfix = " recovered")
        }
        val rows = content?.result?.exercises?.map { x -> (c.catalog.bySlug(x.slug)?.nameEn ?: x.slug) to (if (x.sets == 1) "1 set" else "${x.sets} sets") }.orEmpty()
        out += SheetSession(
            PlanIt.typeName(item.type) ?: item.label,
            when {
                r.notRecommended -> "Not recommended"
                planned -> "Planned"
                else -> "Suggested"
            },
            time, if (r.notRecommended) "This week's plan gives it no sets: its groups are covered, recovering or not wanted." else ready, rows, ev, true,
        )
    }
    if (out.isEmpty() && r.kind == "done") out += SheetSession(r.title.removeSuffix(" (done)"), "Done", "", null, emptyList(), null, false)
    return out
}

/**
 * The day sheet (UX 10.9, D82 (2), tidied after the phone check): each gym session of the day with its status, one time
 * line, its add-on or the readiness of its families, and its exercises with their sets; the rest reason on a day
 * without one; then the actions. Only gym events are listed (D84).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DaySheet(
    c: AppContainer,
    p: WeekPage,
    r: WeekRow,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAction: (WeekAction) -> Unit,
    onOpenToday: () -> Unit,
    onMessage: (String) -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snap by c.calendar.snapshot.collectAsState()
    var sessions by remember { mutableStateOf<List<SheetSession>?>(null) }
    var correct by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(r.date, r.item?.key, r.challengeItem?.key, snap.refreshedAt) {
        sessions = withContext(Dispatchers.Default) { runCatching { sessionsOf(c, p, r, snap) }.getOrDefault(emptyList()) }
    }
    val today = Week.localDate(c.clock.now(), PlanningFacts.ZONE)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(r.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)), style = MaterialTheme.typography.titleLarge)
            val list = sessions
            when {
                list == null -> LoadingDumbbell(PLANNER_LINES, height = 40.dp)
                list.isEmpty() -> {
                    Text(r.title, style = MaterialTheme.typography.titleMedium)
                    r.detail?.let { Text(it.replaceFirstChar { ch -> ch.uppercase() }, style = MaterialTheme.typography.bodyMedium, color = Fit.Muted) }
                    Text("Tap an empty spot in the week to plan a session.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                }
                else -> list.forEachIndexed { i, s ->
                    if (i > 0) TileDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(s.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Pill(s.status, content = if (s.status == "Suggested") Fit.Accent else Fit.Ink)
                        }
                        if (s.time.isNotEmpty()) Text(s.time, style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                        s.extra?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
                        if (s.rows.isNotEmpty()) {
                            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                s.rows.forEach { (name, detail) ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                                    }
                                }
                            }
                        }
                        val e = s.event
                        val link = e?.marker?.let { m -> snap.links.firstOrNull { it.markerUuid == m.uuid && it.slotKey == m.slotKey } }
                        val anchor = e?.let { ev -> snap.anchors.firstOrNull { it.instanceKey == ev.instanceKey } }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val plan = r.action as? WeekAction.PlanIt
                            if (s.general && s.status == "Suggested" && plan != null) LinkButton("Plan it", { onAction(plan); onDismiss() }, enabled = !busy)
                            if (e != null) {
                                snap.prompts.firstOrNull { (it.prompt as? WritePrompt.Adopt)?.instanceKey == e.instanceKey }?.let { pr ->
                                    LinkButton("Adopt", { onAction(WeekAction.Adopt(pr)) }, enabled = !busy)
                                }
                                val removable = s.general && e.marker?.parsedKey() is SlotKey.General && link != null &&
                                    (link.state == CalendarLink.ACTIVE || link.state == CalendarLink.USER_MOVED) && anchor != null && anchor.beginUtc.isAfter(c.clock.now())
                                if (removable) LinkButton("Remove from calendar", {
                                    scope.launch {
                                        val err = c.calendar.removeGeneral(e.marker, "user_remove")
                                        if (err != null) onMessage(errorText(err)) else { onChanged(); onMessage("Removed from your calendar"); onDismiss() }
                                    }
                                }, enabled = !busy)
                                LinkButton("Correct", { correct = e.instanceKey })
                            }
                        }
                    }
                }
            }
            r.action?.let { a -> if (a is WeekAction.AddToCalendar) LinkButton("Add to calendar", { onAction(a); onDismiss() }, enabled = !busy) }
            if (r.date == today && !list.isNullOrEmpty() && list.any { it.status != "Done" }) LinkButton("Go to Today", onOpenToday)
        }
    }
    correct?.let { key -> EventSheet(c, snap, key, onDismiss = { correct = null; onChanged() }, onMessage = onMessage) }
}

/** `weekplan_shown` once per local date and ISO week (UX 19.5, 20.4): the days and the volume rows. */
private suspend fun shownOnce(c: AppContainer, p: WeekPage) {
    val w = p.volume ?: return
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
    val days = p.rows.map { r ->
        JsonObject(mapOf("date" to JsonPrimitive(r.date.toString()), "kind" to JsonPrimitive(r.kind), "type" to JsonPrimitive(r.item?.type ?: "")))
    }
    c.sessionRepository.event(
        EventDraft(
            type = "weekplan_shown", screen = "week", entityType = "none",
            payload = mapOf(
                "iso_week" to JsonPrimitive(w.isoWeek), "iso_week_offset" to JsonPrimitive(p.offset), "days" to JsonArray(days),
                "volume_rows" to JsonArray(rows),
            ),
        ),
    )
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
