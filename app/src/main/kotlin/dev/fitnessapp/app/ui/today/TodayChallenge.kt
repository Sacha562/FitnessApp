package dev.fitnessapp.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fitnessapp.algo.challenge.materialize.Materializer
import dev.fitnessapp.algo.challenge.plan.TodayAdvisor
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.session.SessionService
import dev.fitnessapp.app.ui.challenges.ChallengeTexts
import dev.fitnessapp.app.ui.challenges.ChallengeWeek
import dev.fitnessapp.app.ui.challenges.HabitDays
import dev.fitnessapp.app.ui.common.Banner
import dev.fitnessapp.app.ui.common.BannerKind
import dev.fitnessapp.app.ui.common.ChipButton
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.Pill
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.live.PAIN_KINDS
import dev.fitnessapp.app.ui.theme.Fit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** The challenge part of Today (UX 9, CHALLENGE_ENGINE 7.6): today's planned slot, or the next one and why not today. */
data class TodayChallengeUi(
    val enrollmentId: String,
    val program: String,
    val mode: String,
    val headline: String,
    val detail: String?,
    /** The slot to start today: the planned one, else the one "train now" allows (7.6); null when none fits. */
    val slot: String?,
    val trainNow: Boolean,
    val blocked: String?,
    /** The HOME checklist (UX 9.6). */
    val habits: List<HabitRowUi> = emptyList(),
    /** The template name of [slot] for the button ("Start Heavy pulling now instead"). */
    val slotName: String? = null,
    /** [slot] is the definition's test slot: Start opens the test-day wizard (UX 14.9). */
    val isTest: Boolean = false,
)

/** How a habit row is completed (UX 9.6): quick-done by its checkbox, or through the home mini-session (Start). */
enum class HabitAction { QUICK, START, NONE }

/** One row of the home habits checklist: "[ ] 2-arm hang 17:15 4-5 sets, 2-3 min total (deload: scaled)". */
data class HabitRowUi(
    val habitId: String,
    val name: String,
    val time: String,
    val dose: String?,
    /** "done", "skipped: session A today", "off: elbow protocol", "deload: scaled"; null while simply due. */
    val status: String?,
    val done: Boolean,
    val action: HabitAction,
    /** The row opens the mini-session (habits with an exercise). */
    val hasSession: Boolean,
    val measureToday: Boolean,
)

class TodayChallengeViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow<TodayChallengeUi?>(null)
    val ui: StateFlow<TodayChallengeUi?> = _ui
    private val _elbowFor = MutableStateFlow<String?>(null)

    /** The slot waiting for the elbow check (templates with `requiresReadiness: ["elbow"]`, CHALLENGE_ENGINE 11). */
    val elbowFor: StateFlow<String?> = _elbowFor
    private val _notice = MutableStateFlow<Pair<String, String>?>(null)
    val notice: StateFlow<Pair<String, String>?> = _notice
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** A quick-done waiting out its Undo window (UX 9.6): nothing is written until it passes. */
    private val _pendingQuick = MutableStateFlow<String?>(null)
    val pendingQuick: StateFlow<String?> = _pendingQuick
    private var quickJob: kotlinx.coroutines.Job? = null
    private var habitDay: dev.fitnessapp.data.challenge.HabitDay? = null

    fun refresh() {
        viewModelScope.launch {
            _ui.value = withContext(Dispatchers.Default) { runCatching { build() }.getOrNull() }
        }
    }

    private suspend fun build(): TodayChallengeUi? {
        val e = c.challenges.open().firstOrNull { it.status == "active" || it.status == "paused" } ?: return null
        val f = c.challenges.folded(e.id)
        val def = f.def
        val program = ChallengeTexts.title(def)
        if (f.state.status == "paused") return TodayChallengeUi(e.id, program, f.state.mode, "Challenge paused", "Resume it in the Challenges tab.", null, false, null)
        val facts = c.challengeFacts.facts(def, f.state)
        val (input, plan) = ChallengeWeek.plan(c, f, facts)
        val today = Week.localDate(input.now, input.zone)
        val habits = habits(f, today, input, plan)
        val advice = TodayAdvisor.advise(def, c.params, input, plan)
        fun name(slot: String) = Materializer.templateFor(def, f.state, slot)?.name?.get("en") ?: "Session $slot"
        val todaySlot = advice.slot
        if (advice.kind == "challenge" && todaySlot != null) {
            val done = plan.sessions.any { it.date == today && it.slot == todaySlot && it.source == "done" }
            val time = advice.start?.let { ChallengeTexts.time(Week.localTime(it, input.zone)) }
            return TodayChallengeUi(
                e.id, program, f.state.mode, if (done) "Done today: ${name(todaySlot)}" else "Today: ${name(todaySlot)}",
                time, if (done) null else todaySlot, false, null, habits, isTest = todaySlot == def.doc.structure.block.testSlot,
            )
        }
        val next = plan.sessions.firstOrNull { it.source != "done" && it.date.isAfter(today) }
        val nextText = next?.let { "Next: ${name(it.slot)} ${ChallengeTexts.date(it.date)} ${ChallengeTexts.time(Week.localTime(it.interval.start, input.zone))}" }
            ?: "This week's challenge sessions are done."
        val now = TodayAdvisor.advise(def, c.params, input, plan, trainNow = true)
        // The planner's reason reads "rest day before <slot>"; shown with the session's name (D35: no codes).
        val blocked = if (advice.kind == "rest") {
            advice.reasons.firstOrNull()?.substringAfter(": ")?.substringAfter("rest day before ", "")?.takeIf { it.isNotEmpty() }
                ?.let { "Rest today, before ${name(it)}" } ?: "Rest today"
        } else {
            null
        }
        val nowSlot = if (now.kind == "challenge" && now.reasons == listOf("train_now")) now.slot else null
        return TodayChallengeUi(
            e.id, program, f.state.mode, nextText, blocked, nowSlot, true, blocked, habits, slotName = nowSlot?.let(::name)?.substringBefore(':'),
        )
    }

    /** UX 9.6: today's habits with their dose, state and how they are completed. */
    private suspend fun habits(
        f: dev.fitnessapp.data.challenge.FoldedEnrollment, today: LocalDate,
        input: dev.fitnessapp.algo.challenge.plan.PlanInput, plan: dev.fitnessapp.algo.challenge.plan.ChallengeWeekPlan,
    ): List<HabitRowUi> {
        val day = HabitDays.of(c, f, today, input, plan)
        habitDay = day
        return day.rows.map { r ->
            val h = f.def.doc.habits.single { it.id == r.habitId }
            val p = h.prescription
            val hasSession = p["exercise"] != null
            // Per-set targets or stop flags need the per-set log (the plank, D33); otherwise the checkbox logs it as prescribed.
            val quick = !hasSession || (p["hold_s"] == null && p["stopSetOn"] == null)
            val due = r.state == "DUE" || r.state == "SCALED"
            HabitRowUi(
                habitId = r.habitId, name = h.name["en"] ?: r.habitId, time = r.time.toString(), dose = HabitTexts.dose(r.dose),
                status = HabitTexts.status(r, day.input.facts.weekPlanSlots[today].orEmpty(), f.state.vars), done = r.state == "DONE",
                action = when {
                    !due -> HabitAction.NONE
                    quick -> HabitAction.QUICK
                    else -> HabitAction.START
                },
                hasSession = hasSession && (due || r.state == "DONE"), measureToday = r.measureToday,
            )
        }
    }

    /** The checkbox (UX 9.6): logged after the Undo window, so an undone tap writes nothing. */
    fun quickDone(habitId: String) {
        val u = _ui.value ?: return
        quickJob?.cancel()
        _pendingQuick.value = habitId
        quickJob = viewModelScope.launch {
            kotlinx.coroutines.delay(QUICK_UNDO_MS)
            quickEnded = habitId to System.currentTimeMillis()
            _pendingQuick.value = null
            c.appScope.launch {
                runCatching { c.challengeSessions.quickDone(u.enrollmentId, habitId) }
                refresh()
            }
        }
    }

    fun undoQuick() {
        quickJob?.cancel()
        quickJob = null
        _pendingQuick.value?.let { quickEnded = it to System.currentTimeMillis() }
        _pendingQuick.value = null
    }

    /** When a habit's Undo window closed: a late tap on the Undo spot must not open the row (P5). */
    private var quickEnded: Pair<String, Long>? = null

    /** The row or its Start chip: the home mini-session (a Live Session with `kind = home`, C34). */
    fun startHabit(habitId: String, onOpen: (String) -> Unit) {
        val day = habitDay ?: return
        val ended = quickEnded
        if (ended != null && ended.first == habitId && System.currentTimeMillis() - ended.second < QUICK_TAP_GUARD_MS) return
        viewModelScope.launch {
            _busy.value = true
            val id = runCatching { withContext(Dispatchers.Default) { c.challengeSessions.startHabit(day, habitId, "today") } }
                .onFailure { _notice.value = "warn" to (it.message ?: "The habit could not start.") }.getOrNull()
            _busy.value = false
            if (id != null) {
                SessionService.start(c.appContext)
                onOpen(id)
            }
            refresh()
        }
    }

    fun start(slot: String, onOpen: (String) -> Unit) {
        val u = _ui.value ?: return
        viewModelScope.launch {
            val needsElbow = withContext(Dispatchers.Default) {
                val f = c.challenges.folded(u.enrollmentId)
                "elbow" in Materializer.templateFor(f.def, f.state, slot)?.requiresReadiness.orEmpty() && !checkedToday()
            }
            if (needsElbow) _elbowFor.value = slot else launchSession(slot, onOpen)
        }
    }

    private suspend fun checkedToday(): Boolean {
        val today = LocalDate.now(ChallengeWeek.ZONE).toString()
        return c.db.wellness().countKindOn("elbow_check", today) > 0
    }

    /** Readiness sheet answer (UX 16.3): the safety rules run on it before the session is materialized. */
    fun submitElbow(score: Int, symptoms: List<String>, onOpen: (String) -> Unit) {
        val u = _ui.value ?: return
        val slot = _elbowFor.value ?: return
        _elbowFor.value = null
        viewModelScope.launch {
            val out = runCatching { c.challengeSessions.elbowCheck(u.enrollmentId, score, symptoms) }.getOrNull()
            out?.notifications?.firstOrNull { it.kind == "stop" || it.kind == "warn" }?.let { n -> _notice.value = n.kind to ChallengeTexts.message(out.folded.def, n) }
            launchSession(slot, onOpen)
        }
    }

    fun cancelElbow() {
        _elbowFor.value = null
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private suspend fun launchSession(slot: String, onOpen: (String) -> Unit) {
        val u = _ui.value ?: return
        _busy.value = true
        val id = runCatching { withContext(Dispatchers.Default) { c.challengeSessions.start(u.enrollmentId, slot) } }
            .onFailure { _notice.value = "warn" to (it.message ?: "The session could not start.") }.getOrNull()
        _busy.value = false
        if (id != null) {
            SessionService.start(c.appContext)
            onOpen(id)
        }
        refresh()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayChallengeTile(c: AppContainer, sessionActive: Boolean, onOpenSession: (String) -> Unit, onOpenTest: (String) -> Unit = {}) {
    val vm: TodayChallengeViewModel = viewModel(key = "today_challenge", factory = viewModelFactory { initializer { TodayChallengeViewModel(c) } })
    val ui by vm.ui.collectAsState()
    val elbowFor by vm.elbowFor.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(sessionActive) { vm.refresh() }
    notice?.let { (kind, text) -> Banner(text, if (kind == "stop" || kind == "warn") BannerKind.SAFETY else BannerKind.ADVISORY, action = "OK", onAction = vm::dismissNotice) }
    val u = ui ?: return
    Tile(spacing = 6.dp) {
        Pill(u.program + " · " + ChallengeTexts.mode(u.mode), container = if (u.mode == "injury") Fit.SafetyContainer else Fit.Raised)
        Text(u.headline, style = MaterialTheme.typography.titleLarge)
        u.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fit.Muted) }
        val slot = u.slot
        if (!sessionActive && slot != null) {
            if (u.isTest && !u.trainNow) {
                PrimaryButton("Start test day", { onOpenTest(u.enrollmentId) }, Modifier.fillMaxWidth())
            } else if (u.trainNow) {
                LinkButton(if (busy) "Starting…" else "Start ${u.slotName ?: "a session"} now instead", { vm.start(slot, onOpenSession) }, enabled = !busy)
            } else {
                PrimaryButton(if (busy) "Starting…" else "Start session", { vm.start(slot, onOpenSession) }, Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
    }
    if (u.habits.isNotEmpty()) HabitsTile(u.habits, vm, sessionActive, onOpenSession)
    if (elbowFor != null) ElbowCheckDialog(onDismiss = vm::cancelElbow) { score, symptoms -> vm.submitElbow(score, symptoms, onOpenSession) }
}

/** UX 9.6 HOME: one row per habit; the checkbox quick-dones, Start (or the row) opens the mini-session. */
@Composable
private fun HabitsTile(rows: List<HabitRowUi>, vm: TodayChallengeViewModel, sessionActive: Boolean, onOpenSession: (String) -> Unit) {
    val pending by vm.pendingQuick.collectAsState()
    val busy by vm.busy.collectAsState()
    Tile(spacing = 4.dp) {
        Text("Home", style = MaterialTheme.typography.labelMedium, color = Fit.Muted)
        rows.forEach { r ->
            val canOpen = r.hasSession && !sessionActive && !busy && pending != r.habitId
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (canOpen) Modifier.clickable { vm.startHabit(r.habitId, onOpenSession) } else Modifier),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val checked = r.done || pending == r.habitId
                when (r.action) {
                    HabitAction.QUICK -> Checkbox(checked = checked, onCheckedChange = { on -> if (on) vm.quickDone(r.habitId) else if (pending == r.habitId) vm.undoQuick() }, modifier = Modifier.size(48.dp))
                    HabitAction.START -> ChipButton("Start", false, { if (canOpen) vm.startHabit(r.habitId, onOpenSession) })
                    HabitAction.NONE -> Checkbox(checked = r.done, onCheckedChange = null, enabled = false, modifier = Modifier.size(48.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(r.name + " · " + r.time, style = MaterialTheme.typography.titleSmall)
                    val line = listOfNotNull(r.dose, if (pending == r.habitId) "done" else r.status).joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                }
                // The Undo replaces the badge in place: the rows below never move under the finger (P5).
                when {
                    pending == r.habitId -> LinkButton("Undo", vm::undoQuick)
                    r.measureToday && !r.done -> Pill("Measure today", container = Fit.Accent, content = Fit.OnAccent)
                }
            }
        }
    }
}

/** The dose and state wording of a habit row (UX 9.6). */
internal object HabitTexts {
    fun dose(d: dev.fitnessapp.algo.challenge.materialize.HabitDose): String? {
        val parts = ArrayList<String>()
        if (d.sets.isNotEmpty()) parts += d.sets.distinct().joinToString("-") + if (d.sets.max() == 1) " set" else " sets"
        if (d.totalS.isNotEmpty()) {
            val min = d.totalS.map { it.toDouble() / 60.0 }
            parts += min.distinct().joinToString("-") { dev.fitnessapp.app.format.Numbers.num(it, 1) } + " min total"
        }
        d.holdS?.let { parts += dev.fitnessapp.app.format.Numbers.num(it.toDouble(), 1) + " s" + if (d.perSide) "/side" else "" }
        d.addedKg?.takeIf { it.signum() > 0 }?.let { parts += "+ " + dev.fitnessapp.app.format.Numbers.num(it.toDouble(), 2) + " kg" }
        // "4-5 sets, 2-3 min total" (a total over the sets) or "3 sets × 20 s/side" (per set).
        return parts.joinToString(if (d.totalS.isNotEmpty()) ", " else " × ").ifEmpty { null }
    }

    fun status(r: dev.fitnessapp.algo.challenge.materialize.HabitToday, todaySlots: List<String>, vars: Map<String, dev.fitnessapp.algo.challenge.fitexpr.Value>): String? = when (r.state) {
        "DONE" -> "done"
        "SCALED" -> r.reason?.removePrefix("mode:")?.let { "${ChallengeTexts.mode(it).lowercase()}: scaled" }
        "OFF" -> r.reason?.let { reason ->
            when {
                reason.startsWith("mode:") -> "off: " + ChallengeTexts.mode(reason.removePrefix("mode:")).lowercase()
                reason.startsWith("status:") -> "off: " + ChallengeTexts.status(reason.removePrefix("status:")).lowercase()
                else -> "off"
            }
        }
        "SKIPPED" -> "skipped: " + skipReason(r, todaySlots, vars)
        else -> null
    }

    /** Which part of `skipIf` holds today: a `todayHas('<slot>')` with that slot today, or a true `s.<flag>`. */
    private fun skipReason(r: dev.fitnessapp.algo.challenge.materialize.HabitToday, todaySlots: List<String>, vars: Map<String, dev.fitnessapp.algo.challenge.fitexpr.Value>): String {
        if (r.reason?.startsWith("error:") == true) return "could not check the rule"
        val src = r.skipIf.orEmpty()
        Regex("todayHas\\('([A-Z0-9_]+)'\\)").findAll(src).map { it.groupValues[1] }.firstOrNull { it in todaySlots }?.let { return if (it == "TEST") "test day" else "session $it today" }
        Regex("s\\.([a-zA-Z0-9_]+)").findAll(src).map { it.groupValues[1] }
            .firstOrNull { (vars[it] as? dev.fitnessapp.algo.challenge.fitexpr.BoolV)?.b == true }
            ?.let { v -> return v.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase() }
        return "today"
    }
}

/** UX 16.3 elbow check: 0-3, with the kind asked at 2 or more (C69); the stop signals start the elbow protocol. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ElbowCheckDialog(onDismiss: () -> Unit, onSubmit: (Int, List<String>) -> Unit) {
    var score by remember { mutableStateOf<Int?>(null) }
    var kinds by remember { mutableStateOf(emptySet<String>()) }
    val labels = listOf(0 to "Fine", 1 to "A trace", 2 to "Some ache", 3 to "Pain")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How do your elbows feel?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChips(labels, labels.firstOrNull { it.first == score }, { it.second }) { score = it.first }
                if ((score ?: 0) >= 2) {
                    Text("What kind?", style = MaterialTheme.typography.labelMedium, color = Fit.Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PAIN_KINDS.filter { it.second.any { r -> r.startsWith("elbow") } }.forEach { (code, _, label) ->
                            ChipButton(label, code in kinds, { kinds = if (code in kinds) kinds - code else kinds + code }, role = Role.Checkbox)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(score!!, kinds.toList()) }, enabled = score != null) { Text("Continue") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** The Undo window of a quick-done habit (UX 9.6 "Undo snackbar"). */
private const val QUICK_UNDO_MS = 5_000L

/** Taps on a row right after its Undo disappeared are ignored for this long (P5: nothing acts under a moving finger). */
private const val QUICK_TAP_GUARD_MS = 1_500L
