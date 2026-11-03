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
import dev.fitnessapp.app.planning.SessionBuild
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.planning.TrainingWindows
import dev.fitnessapp.app.session.SessionService
import dev.fitnessapp.app.ui.common.Banner
import dev.fitnessapp.app.ui.common.BannerKind
import dev.fitnessapp.app.ui.common.FitTextField
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.LoadingDumbbell
import dev.fitnessapp.app.ui.common.PLANNER_LINES
import dev.fitnessapp.app.ui.common.showUndo
import dev.fitnessapp.app.ui.common.TonalButton
import dev.fitnessapp.app.ui.common.Pill
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.ui.you.WindowsSheet
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import dev.fitnessapp.data.repo.Setting
import dev.fitnessapp.data.sync.PendingStatus
import dev.fitnessapp.data.sync.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.fitnessapp.app.planning.PlanItContent
import dev.fitnessapp.app.planning.PromptPlans
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Today (D22, D49): start an empty session, repeat a past one, or resume the open one, this week's strip, the bodyweight tile with a
 * weigh-in prompt when stale, the status banner and recent sessions, laid out as Bento tiles (D31). The
 * recommendation card arrives with M4.
 */
@Composable
fun TodayRoute(
    c: AppContainer, modifier: Modifier, onOpenSession: (id: String, resumed: Boolean) -> Unit, onOpenSync: () -> Unit, onOpenPast: (String) -> Unit = {},
    onHistory: () -> Unit = {}, onOpenTest: (enrollmentId: String) -> Unit = {},
    onOpenReliability: () -> Unit = {}, onOpenEquipment: () -> Unit = {}, onSetGoals: () -> Unit = {},
    onOpenWeek: (LocalDate) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val active by c.sessionRepository.observeActive().collectAsState(initial = null)
    val recent by c.sessionRepository.observeRecent(8).collectAsState(initial = emptyList())
    val latestBw by c.bodyweight.observeLatest().collectAsState(initial = null)
    val outbox by c.sync.observeOutbox().collectAsState(initial = 0 to null)
    val dead by c.sync.observeDeadLetters().collectAsState(initial = 0)
    val sync by c.sync.state.collectAsState()
    var weighIn by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(false) }
    // The readiness sheet of a general session (UX 16.3) and the source session of a Repeat.
    var sheet by remember { mutableStateOf<PreSheet?>(null) }
    var sheetRepeat by remember { mutableStateOf<String?>(null) }
    // The New session sheet (D62) and the built session waiting for the readiness sheet.
    var newSession by remember { mutableStateOf(false) }
    var sheetBuilt by remember { mutableStateOf<BuiltStart?>(null) }
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
    val goals by remember { c.goals.observe() }.collectAsState(initial = null)
    val profile by remember { c.profiles.observe() }.collectAsState(initial = null)
    var editTimes by remember { mutableStateOf(false) }
    // The planner and the Today card (M4.5, D70): rerun when sessions, the calendar, the goals or the time chip change.
    val calendar by c.calendar.snapshot.collectAsState()
    var plan by remember { mutableStateOf<TodayPlan?>(null) }
    var planFailed by remember { mutableStateOf(false) }
    // Whether [plan] was computed for the current inputs: only then do Start and the sheet reuse its week plan (D73).
    var planFresh by remember { mutableStateOf(false) }
    var chipMin by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Int?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    var override by remember { mutableStateOf<String?>(null) }
    var planTick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(active?.id, recent.map { it.id + it.status }, calendar.anchors, goals, profile?.availability, chipMin, planTick) {
        val zoneNow = ZoneId.of("Europe/Amsterdam")
        val todayNow = LocalDate.now(zoneNow).toString()
        planFresh = false
        val r = runCatching {
            withContext(Dispatchers.Default) {
                TodayPlans.load(
                    c, chipMin, activeSession = active != null,
                    finishedToday = recent.any { it.status == "finished" && it.local_date == todayNow && it.habit_id == null },
                    setupNeeded = goals?.isEmpty() == true && c.challenges.open().isEmpty(),
                )
            }
        }
        // A run cancelled because its inputs changed is not a planner failure: the next run replaces it.
        r.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        plan = r.getOrNull()
        planFailed = r.isFailure
        planFresh = r.isSuccess
        r.exceptionOrNull()?.let { android.util.Log.w("TodayPlan", "planner failed", it) }
        if (plan?.options?.primaryOptionId != picked) picked = null
        plan?.let { p ->
            runCatching { TodayPlans.shown(c, p) }
            val next = withContext(Dispatchers.Default) { TodayPlans.next(c, p) }
            if (plan === p) plan = p.withNext(next)
        }
    }


    /** Starts a general session (fresh, a Repeat of [repeatOf] or [built] for a type, D62) and records the sheet. */
    fun startGeneral(repeatOf: String?, s: PreSheet?, a: PreAnswers, built: BuiltStart? = null) {
        starting = true
        scope.launch {
            a.elbow?.let { score -> notice = PreSession.elbow(c, score, a.symptoms) }
            val id = if (built != null) {
                c.sessionRepository.startBuilt(
                    c.params, SessionBuild.items(c, built.result), SessionBuild.families(built.result, c), built.budgetMin, built.budgetSource,
                ).also { (sid, blockId) -> c.sessionPlans.record(sid, blockId, built.result, built.budgetMin) }.first
            } else {
                c.sessionRepository.start(c.params, repeatOf = repeatOf)
            }
            SessionService.start(context) // user action while the activity is resumed (ANDROID.md 11.3)
            starting = false
            onOpenSession(id, false)
            s?.let { PreSession.record(c, id, it.takeIf { x -> x.plan.pre.isNotEmpty() }, it.plan, a, System.currentTimeMillis()) }
        }
    }

    /** Start, Repeat or a built session (UX 9.8): the prompt plan first; with nothing to ask the session starts at once. */
    fun begin(repeatOf: String?, built: BuiltStart? = null) {
        if (starting) return
        starting = true
        scope.launch {
            val s = runCatching {
                withContext(Dispatchers.Default) {
                    val fs = built?.let { PreSession.plannedFsOfBuilt(c, it.result) } ?: repeatOf?.let { PreSession.plannedFsOfSession(c, it) }.orEmpty()
                    PreSheet(PromptPlans.plan(c, home = false, plannedFs = fs, elbowRequired = false), false, System.currentTimeMillis())
                }
            }.getOrNull()
            starting = false
            if (s != null && s.plan.pre.isNotEmpty()) {
                sheet = s
                sheetRepeat = repeatOf
                sheetBuilt = built
            } else {
                startGeneral(repeatOf, s, PreAnswers(), built)
            }
        }
    }
    /** Builds the picked option for the time chip and starts it through the readiness sheet (UX 9.8, D62). */
    fun startOption(p: TodayPlan) {
        val id = picked ?: p.options.primaryOptionId ?: return
        val opt = p.options.options.firstOrNull { it.optionId == id } ?: return
        val type = opt.sessionType?.let { dev.fitnessapp.algo.planning.allocator.SessionType.of(it) } ?: return
        val budget = p.chip?.minutes ?: p.options.budgetMin.toInt()
        scope.launch {
            starting = true
            val built = runCatching {
                withContext(Dispatchers.Default) {
                    val f = SessionBuild.facts(c, weekPlan = p.week.plan.takeIf { planFresh })
                    SessionBuild.build(c, SessionBuild.request(f, type, budget.toDouble(), SessionBuild.laterOf(p.options, type)))
                }
            }.getOrNull()
            starting = false
            if (built == null || built.exercises.isEmpty()) { newSession = true; return@launch }
            runCatching { TodayPlans.action(c, "start", p.options.options.indexOfFirst { it.optionId == id }) }
            begin(null, BuiltStart(type, built, budget, p.chip?.source ?: p.options.budgetSource))
        }
    }
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

            PlanStrip(today, recent, plan, onOpenWeek)

            notice?.let { (kind, text) -> Banner(text, if (kind == "stop" || kind == "warn") BannerKind.SAFETY else BannerKind.ADVISORY, action = "OK", onAction = { notice = null }) }

            // D55: until the goals are saved, a card opens You > Preferences at Muscle goals and Sessions.
            if (goals?.isEmpty() == true) {
                Tile(spacing = 6.dp, onClick = onSetGoals) {
                    Text("Set your goals", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Pick what to improve, maintain or skip, and your training experience. The week's targets and session building use them.",
                        style = MaterialTheme.typography.bodyMedium, color = Fit.Muted,
                    )
                    LinkButton("Set goals", onSetGoals)
                }
            }

            // D75: until the usual training times are saved, a card opens their editor; the plan uses the default times meanwhile.
            if (profile != null && !TrainingWindows.isSet(profile?.availability)) {
                Tile(spacing = 6.dp, onClick = { editTimes = true }) {
                    Text("When do you usually train?", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Until you set your times, the plan assumes weekday evenings 19:45-21:15 and weekends 09:00-16:00.",
                        style = MaterialTheme.typography.bodyMedium, color = Fit.Muted,
                    )
                    LinkButton("Set times", { editTimes = true })
                }
            }
            if (editTimes) WindowsSheet(profile?.availability, onDismiss = { editTimes = false }) { json ->
                editTimes = false
                scope.launch { c.profiles.set(Setting.AVAILABILITY, json) }
            }

            StatusRow(c, PendingStatus.of(sync, outbox.first, outbox.second, System.currentTimeMillis(), c.platformConfig.sync), dead, sync, onOpenSync, onOpenReliability, onOpenEquipment)

            // The Today card (UX 9.3, D70): the verdict, options, chips and time chips; on a challenge day the add-on choice.
            plan?.let { p ->
                TodayCardTile(
                    p, picked,
                    onPick = { o ->
                        picked = o.optionId
                        // The add-on chosen on a challenge day is built at the session's start (D70 (4)).
                        if (p.options.planned != null && p.options.planned?.kind != "general") {
                            c.settings.setAddOnPick(p.options.date.toString(), p.options.planned?.slot ?: "", o.addon ?: "none")
                        }
                        scope.launch { runCatching { TodayPlans.action(c, "select_alt", p.options.options.indexOf(o)) } }
                    },
                    onChip = { ch -> chipMin = ch.minutes; scope.launch { runCatching { TodayPlans.action(c, "budget", ch.minutes) } } },
                    onOtherOptions = { override = null; newSession = true },
                    onLogAnyway = {
                        override = p.options.restReasons.firstOrNull()?.let { r ->
                            dev.fitnessapp.app.uidet.UiTodayCard.text(r.code, r.params, java.time.Instant.now(), ZoneId.of("Europe/Amsterdam")) { it }
                        } ?: "Today is a rest day."
                        newSession = true
                    },
                    onRestToday = { scope.launch { runCatching { TodayPlans.action(c, "rest_today", 0) } } },
                    onPlanIt = todayItem(p)?.takeIf { calendar.status == "ok" && calendar.writeOff == null && calendar.target != null }?.let { item ->
                        {
                            // "Plan it in calendar" (UX 9.3, D82 (3)): today's recommended session into the calendar, with Undo.
                            scope.launch {
                                val t0 = System.currentTimeMillis()
                                val (marker, err) = PlanItContent.planIt(c, p.week.plan, item)
                                runCatching { TodayPlans.action(c, "plan_it", 0) }
                                planTick++
                                if (marker == null) {
                                    snackbar.showSnackbar(if (err == "nothing_to_train") "Nothing to plan for today any more." else "Couldn't add to calendar.")
                                } else if (snackbar.showUndo("Added to calendar", context) == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                    PlanItContent.undo(c, marker, t0)
                                    planTick++
                                }
                            }
                        }
                    },
                )
            }
            // While the planner runs for the first time (D73); a rerun keeps the card it replaces.
            if (plan == null && !planFailed && active == null) Tile(spacing = 6.dp) { LoadingDumbbell(PLANNER_LINES) }
            if (plan == null && planFailed) Tile(spacing = 6.dp) {
                Text("Couldn't work out a recommendation", style = MaterialTheme.typography.titleLarge)
                LinkButton("Retry", { planTick++ })
            }

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

        androidx.compose.material3.SnackbarHost(snackbar, Modifier.padding(horizontal = 12.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val a = active
            if (a != null) {
                PrimaryButton("Resume session", onClick = {
                    SessionService.start(context)
                    onOpenSession(a.id, true)
                }, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow)
            } else {
                val p = plan
                val st = p?.card?.state
                val startable = p != null && (st == dev.fitnessapp.app.uidet.CardState.OPTIONAL ||
                    (st == dev.fitnessapp.app.uidet.CardState.PLANNED && p.card.variant == "GENERAL") ||
                    (st == dev.fitnessapp.app.uidet.CardState.PLANNED_BUT_REST && p.card.variant == "SOFT"))
                val opt = p?.options?.options?.firstOrNull { it.optionId == (picked ?: p.options.primaryOptionId) }
                when {
                    startable && opt != null && st == dev.fitnessapp.app.uidet.CardState.PLANNED_BUT_REST ->
                        TonalButton("Start anyway", onClick = { startOption(p!!) }, modifier = Modifier.fillMaxWidth())
                    startable && opt != null ->
                        PrimaryButton("Start " + opt.label.replaceFirstChar { it.lowercase() }, onClick = { startOption(p!!) }, enabled = !starting, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow)
                    // D62: the New session sheet builds a session for a type; Empty and Repeat (D49) sit at its bottom.
                    st == null || st == dev.fitnessapp.app.uidet.CardState.DONE || st == dev.fitnessapp.app.uidet.CardState.ENGINE_ERROR ->
                        PrimaryButton("Start session", onClick = { override = null; newSession = true }, enabled = !starting, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow)
                    else -> Unit
                }
            }
        }
    }

    if (newSession) NewSessionSheet(
        c, onDismiss = { newSession = false },
        today = plan?.options, banner = override, weekPlan = plan?.week?.plan?.takeIf { planFresh },
        onStart = { b ->
            newSession = false
            // "Log a session anyway" on a hard rest day (UX 9.3): the session is logged; the challenge decides what it means.
            override?.let { scope.launch { runCatching { TodayPlans.action(c, "log_anyway", 0) } } }
            begin(null, b)
        },
        onEmpty = { newSession = false; begin(null) },
        onRepeat = { newSession = false; repeat = true },
    )

    if (repeat) RepeatSheet(c, onDismiss = { repeat = false }) { sourceId ->
        repeat = false
        begin(sourceId)
    }

    sheet?.let { s ->
        ReadinessSheet(c, s, onDismiss = { sheet = null }) { a ->
            sheet = null
            startGeneral(sheetRepeat, s, a, sheetBuilt)
        }
    }

    if (weighIn) WeighInDialog(onDismiss = { weighIn = false }) { kg ->
        weighIn = false
        scope.launch { c.bodyweight.logWeighIn(kg) }
    }
}

/**
 * The week strip (UX 9.7, D70): Monday to Sunday with the planner's symbols (a check when trained, `*` planned in the
 * calendar, `o` recommended, `x` a rest day the challenge blocks, `T` the test day); swipe for next week's provisional
 * plan. Without a plan yet, the days with a finished session.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlanStrip(today: LocalDate, recent: List<WorkoutSessionEntity>, plan: TodayPlan?, onOpenWeek: (LocalDate) -> Unit) {
    val weeks = listOfNotNull(plan?.week?.plan, plan?.next)
    if (weeks.isEmpty()) return WeekStrip(today, recent, onOpenWeek)
    val pager = androidx.compose.foundation.pager.rememberPagerState { weeks.size }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.foundation.pager.HorizontalPager(pager) { page ->
            val w = weeks[page]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                w.days.forEach { d ->
                    val isToday = d.date == today
                    val kinds = d.items.map { it.kind }
                    val trained = kinds.any { it == "done" || it == "done_general" } || (page == 0 && recent.any { it.status == "finished" && it.local_date == d.date.toString() })
                    val symbol = when {
                        trained -> "v"
                        "test" in kinds -> "T"
                        kinds.any { it == "challenge_session" || it == "planned_gym" } -> "*"
                        "recommended_general" in kinds -> "o"
                        d.status == "blocked" -> "x"
                        else -> "-"
                    }
                    val label = when (symbol) {
                        "v" -> "trained"; "T" -> "test day"; "*" -> "planned"; "o" -> "recommended"; "x" -> "rest day"; else -> "rest"
                    }
                    val name = d.date.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))
                    Column(
                        Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(if (isToday) Fit.Accent else Fit.Tile)
                            .clickable { onOpenWeek(d.date) }
                            .semantics { contentDescription = "$name, $label" + (if (isToday) ", today" else "") },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        val ink = if (isToday) Fit.OnAccent else Fit.Muted
                        Text(name.take(1), style = MaterialTheme.typography.labelMedium, color = ink)
                        when (symbol) {
                            "v" -> Icon(Icons.Rounded.Check, null, tint = if (isToday) Fit.OnAccent else Fit.Accent, modifier = Modifier.size(16.dp))
                            "-" -> Box(Modifier.padding(top = 5.dp, bottom = 6.dp).size(5.dp).clip(CircleShape).background(if (isToday) Fit.OnAccent else Fit.Line))
                            else -> Text(if (symbol == "o") "○" else if (symbol == "*") "●" else symbol, style = MaterialTheme.typography.labelMedium, color = if (isToday) Fit.OnAccent else Fit.Ink)
                        }
                    }
                }
            }
        }
        if (weeks.size > 1) Text(
            if (pager.currentPage == 0) "This week · swipe for next week" else "Next week (provisional)",
            style = MaterialTheme.typography.labelSmall, color = Fit.Muted,
        )
    }
}

/** Today's recommended general session in this week's plan, the item "Plan it in calendar" writes (D82 (3)). */
private fun todayItem(p: TodayPlan): dev.fitnessapp.algo.planning.weekplan.PlanItem? =
    p.week.plan.days.firstOrNull { it.date == p.options.date }?.items
        ?.firstOrNull { it.kind == dev.fitnessapp.algo.planning.weekplan.WeeklyPlanner.KIND_RECOMMENDED && it.start.isAfter(java.time.Instant.now()) }

/** Monday to Sunday of this week: a check on days with a finished session, today in the accent. */
@Composable
private fun WeekStrip(today: LocalDate, recent: List<WorkoutSessionEntity>, onOpenWeek: (LocalDate) -> Unit) {
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
                    .clickable { onOpenWeek(day) }
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
