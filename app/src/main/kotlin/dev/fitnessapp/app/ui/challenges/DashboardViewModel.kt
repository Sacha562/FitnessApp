package dev.fitnessapp.app.ui.challenges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.eca.RuleEnv
import dev.fitnessapp.algo.challenge.eca.Records
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.enrollment.PauseTools
import dev.fitnessapp.algo.challenge.fitexpr.DateV
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.Type
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.Values
import dev.fitnessapp.algo.challenge.materialize.ItemChange
import dev.fitnessapp.algo.challenge.materialize.MigrationPreview
import dev.fitnessapp.algo.challenge.materialize.MigrationPreviews
import dev.fitnessapp.algo.challenge.materialize.PlannedItem
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.uidet.GateRows
import dev.fitnessapp.app.uidet.UiGateRows
import dev.fitnessapp.data.challenge.ChallengeEventDraft
import dev.fitnessapp.data.challenge.FoldedEnrollment
import dev.fitnessapp.data.db.entity.ChallengeEventEntity
import dev.fitnessapp.data.db.entity.ChallengeRuleEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One slot of This week; [calendar] is its calendar status (D39) and [instanceKey] the matched event, for the event sheet. */
data class WeekSlotUi(val slot: String, val text: String, val done: Boolean, val calendar: String? = null, val instanceKey: String? = null)
data class RuleUi(val id: String, val stage: String, val tag: String, val text: String, val status: String, val via: String?, val fired: Int, val lastFired: String?, val source: String)
data class ProposalUi(val id: String, val title: String, val rationale: String, val ifDeclined: String, val decision: String?)
data class HistoryUi(val header: String, val lines: List<Pair<String, Boolean>>)
data class TestUi(val date: String, val lines: List<String>, val valid: String)

/** A value the user may correct (CHALLENGE_ENGINE 4.9) and how its input works. */
data class CorrectTarget(val target: String, val label: String, val current: Value?, val type: Type, val step: BigDecimal?, val values: List<String> = emptyList())

data class DashboardUi(
    val title: String,
    val status: String,
    val statusText: String,
    val mode: String,
    val phase: Int,
    val phases: List<String>,
    val blockLine: String,
    val nextTest: String?,
    val precondition: String?,
    val week: List<WeekSlotUi>,
    val gates: GateRows,
    val rules: List<RuleUi>,
    val proposals: List<ProposalUi>,
    val history: List<HistoryUi>,
    val tests: List<TestUi>,
    val undecided: Int,
    val pausedSince: LocalDate?,
    val targets: List<CorrectTarget>,
    /** "Log test day" shows on the planned test day and after it, and while the test is pending (UX 14.2). */
    val testDay: Boolean = false,
    /** The program version this enrollment runs, and a newer one it can update to (CHALLENGE_ENGINE 4.12, D44). */
    val version: String = "",
    val update: String? = null,
)

/** The definition-update preview (UX 14.2, D44): what changes in this and next week's sessions, the rules and proposals. */
data class UpdatePreviewUi(
    val from: String,
    val to: String,
    val notes: String?,
    /** Per planned session: its header ("Session A · Fri 23 Oct") and one line per changed exercise. */
    val sessions: List<Pair<String, List<String>>>,
    val rules: List<String>,
    val proposals: List<String>,
)

/**
 * The active challenge dashboard (UX 14.2-14.7): overview with gate rows, rules, proposals, history and tests,
 * and the enrollment actions (proposal decisions, pause, resume, abandon, correct a value), each written as an input
 * event through [dev.fitnessapp.data.challenge.ChallengeRepository].
 */
class DashboardViewModel(private val c: AppContainer, val enrollmentId: String) : ViewModel() {
    private val zone: ZoneId = ZoneId.of("Europe/Amsterdam")
    private val _ui = MutableStateFlow<DashboardUi?>(null)
    val ui: StateFlow<DashboardUi?> = _ui
    private val _messages = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    /** Notifications of the last action as `(kind, text)` (CHALLENGE_ENGINE 5.7). */
    val messages: StateFlow<List<Pair<String, String>>> = _messages
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        viewModelScope.launch {
            val enrollment = c.challenges.observeAll().map { rows -> rows.firstOrNull { it.id == enrollmentId } }.filterNotNull()
            // collectLatest: every change of the enrollment row (a new event) refolds; a plain collect would stay in the
            // first fold's inner flow forever and show a stale status (seen on the phone after Abandon).
            enrollment.collectLatest { e ->
                val f = runCatching { c.challenges.folded(e.id) }.onFailure { _error.value = it.message }.getOrNull() ?: return@collectLatest
                combine(
                    c.db.ruleEvents().observeGeneration(e.id, f.enrollment.fold_generation),
                    c.db.challengeEvents().observeHistory(e.id),
                    // The This week row shows the calendar status (D39): rebuild on every calendar refresh.
                    c.calendar.snapshot,
                ) { rules, events, _ -> rules to events }.collect { (rules, events) ->
                    _ui.value = withContext(Dispatchers.Default) { runCatching { build(f, rules, events) }.onFailure { _error.value = it.message }.getOrNull() }
                }
            }
        }
    }

    private suspend fun build(f: FoldedEnrollment, rules: List<ChallengeRuleEventEntity>, events: List<ChallengeEventEntity>): DashboardUi {
        val def = f.def
        val s = f.state
        val facts = c.challengeFacts.facts(def, s)
        val env = RuleEnv(def, c.params, Records(c.params), s, LocalDate.now(zone), facts)
        val blockWeeks = (def.blockWeeks.evaluate(env.roots(), env.host) as? QV)?.v?.toInt()
        val needsClean = (s.vars["needsCleanSession"] as? dev.fitnessapp.algo.challenge.fitexpr.BoolV)?.b == true
        val nextTest = ChallengeWeek.nextTestText(c, f, facts)
        return DashboardUi(
            title = ChallengeTexts.title(def),
            status = s.status,
            statusText = if (s.status == EnrollmentState.ACTIVE) ChallengeTexts.mode(s.mode) else ChallengeTexts.status(s.status),
            mode = s.mode,
            phase = s.phase,
            phases = def.doc.phases.map { it.name["en"] ?: "Phase ${it.id}" },
            blockLine = "Block ${s.block.index} · week ${s.block.week}" + (blockWeeks?.let { " of $it" } ?: ""),
            nextTest = nextTest,
            precondition = if (needsClean && (s.plannedTest.date != null || s.plannedTest.basis == "postponed")) "First one normal session without pain" else null,
            week = week(f, facts),
            gates = UiGateRows.build(def, c.params, s, facts, facts.bwKg, emptyList()),
            rules = rules(def, s, rules),
            proposals = def.doc.proposals.map { p -> proposalUi(p, s.proposalDecisions[p.id]?.decision) },
            history = history(def, rules, events),
            tests = tests(s),
            undecided = def.doc.proposals.count { it.id !in s.proposalDecisions },
            pausedSince = s.pausedAt,
            targets = targets(def, s),
            testDay = s.status == EnrollmentState.ACTIVE &&
                (s.mode == "test_pending" || s.plannedTest.date?.let { !LocalDate.now(zone).isBefore(it) } == true),
            version = def.version,
            update = runCatching { c.challenges.updateFor(f.enrollment.id)?.version }.getOrNull(),
        )
    }

    /** This week's slots (UX 14.2 "This week"): done challenge sessions and the planner's suggestions. */
    private suspend fun week(f: FoldedEnrollment, facts: EngineFacts): List<WeekSlotUi> {
        if (f.state.status != EnrollmentState.ACTIVE) return emptyList()
        val (_, plan) = ChallengeWeek.plan(c, f, facts)
        val cal = c.calendar.snapshot.value
        return plan.sessions.map { p ->
            val day = p.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.ENGLISH))
            val label = slotLabel(p.slot)
            val anchor = p.eventRef?.let { k -> cal.anchors.firstOrNull { it.instanceKey == k } }
            val status = when {
                p.source == "done" || cal.status != "ok" -> null
                anchor?.marker != null -> cal.links.firstOrNull { it.markerUuid == f.enrollment.id && it.slotKey == anchor.marker!!.slotKey }?.state.let {
                    when (it) {
                        dev.fitnessapp.algo.challenge.calendar.CalendarLink.USER_MOVED -> "moved by you"
                        dev.fitnessapp.algo.challenge.calendar.CalendarLink.USER_EDITED -> "edited by you"
                        else -> "in calendar"
                    }
                }
                anchor != null -> if (cal.prompts.any { (it.prompt as? dev.fitnessapp.algo.challenge.calendar.WritePrompt.Adopt)?.instanceKey == anchor.instanceKey }) "adopt?" else "in calendar"
                cal.writeOff != null || cal.target == null -> "not in calendar"
                else -> null
            }
            WeekSlotUi(
                p.slot, if (p.source == "done") "$label done $day" else "$label $day ${ChallengeTexts.time(Week.localTime(p.interval.start, zone))}", p.source == "done",
                status, anchor?.instanceKey,
            )
        } + plan.droppedSlots.map { WeekSlotUi(it, "${slotLabel(it)}: no room this week", false) }
    }

    private fun rules(def: CompiledDefinition, s: EnrollmentState, audit: List<ChallengeRuleEventEntity>): List<RuleUi> {
        val applied = audit.filter { it.outcome == "applied" }.groupBy { it.rule_id }
        val stageOrder = listOf("safety", "data", "structural", "progression")
        val out = def.doc.rules.sortedWith(compareBy({ stageOrder.indexOf(it.stage) }, { -it.prio }, { it.id })).map { r ->
            val proposal = r.proposal ?: r.supersededBy ?: r.uses.firstOrNull()
            val status = when {
                r.tag == "P" -> when (s.proposalDecisions[r.proposal]?.decision) {
                    "approved" -> "Active"
                    "declined" -> "Off: your program's rule applies"
                    else -> "Waiting for your decision"
                }
                r.supersededBy != null && s.approved(r.supersededBy!!) -> "Replaced by a recommended change"
                else -> "Active"
            }
            val fired = applied[r.id].orEmpty()
            RuleUi(
                r.id, r.stage, if (r.uses.isNotEmpty() && r.tag == "A") "A+P" else r.tag, r.explain["en"] ?: r.id, status,
                proposal?.let { proposalTitle(def, it) }, fired.size, null, r.condition,
            )
        }
        val inSession = def.doc.inSessionRules.map { r ->
            val status = if (r.tag == "P") when (s.proposalDecisions[r.proposal]?.decision) {
                "approved" -> "Active"
                "declined" -> "Off"
                else -> "Waiting for your decision"
            } else "Active"
            RuleUi(r.id, "in_session", r.tag, inSessionText(def, r), status, r.proposal?.let { proposalTitle(def, it) }, 0, null, r.condition)
        }
        return out + inSession
    }

    /** The part of the program a rule belongs to, by the proposal's title (D35: no ids). */
    private fun proposalTitle(def: CompiledDefinition, id: String): String? = def.doc.proposals.firstOrNull { it.id == id }?.title?.get("en")

    /** An in-session rule as its message reads ("Hand slipped: end the set here."), with the sessions it runs in. */
    private fun inSessionText(def: CompiledDefinition, r: dev.fitnessapp.algo.challenge.cds.InSessionRuleSpec): String {
        val msg = r.then.firstNotNullOfOrNull { it["msg"]?.jsonPrimitive?.contentOrNull }?.let { def.doc.messages[it]?.get("en") }
        val text = msg ?: if (r.then.any { "capTarget" in it }) "The next set's target is capped at the reps you did." else "Adjusts the remaining sets."
        return if (r.slots.isEmpty()) text else text + " (session ${r.slots.joinToString(", ")})"
    }

    private fun history(def: CompiledDefinition, audit: List<ChallengeRuleEventEntity>, events: List<ChallengeEventEntity>): List<HistoryUi> {
        val byEvent = audit.groupBy { it.challenge_event_id }
        return events.mapNotNull { e ->
            val rows = byEvent[e.id].orEmpty().sortedWith(compareBy({ it.cascade_depth }, { -it.prio }, { it.rule_id }))
            val label = eventLabel(def, e)
            if (label == null && rows.isEmpty()) return@mapNotNull null
            val lines = rows.mapNotNull { r ->
                val changes = changes(r.applied)
                when (r.outcome) {
                    "applied" -> (ChallengeTexts.ruleExplain(def, r.rule_id) + if (changes.isNotEmpty()) " ($changes)" else "") to false
                    "no_effect" -> null
                    "error" -> "${ChallengeTexts.ruleExplain(def, r.rule_id)}: could not be checked" to true
                    else -> "${ChallengeTexts.ruleExplain(def, r.rule_id)}: not applied, ${ChallengeTexts.outcome(r.outcome)}" to true
                }
            }
            if (label == null && lines.isEmpty()) return@mapNotNull null
            HistoryUi("${ChallengeTexts.date(LocalDate.parse(e.local_date))} · ${label ?: ChallengeTexts.eventType(e.event_type)}", lines)
        }
    }

    private fun changes(applied: String): String = runCatching {
        Json.parseToJsonElement(applied).jsonObject.entries.joinToString(", ") { (k, v) ->
            val o = v.jsonObject
            val old = o["old"]?.jsonPrimitive?.contentOrNull ?: "–"
            val new = o["new"]?.jsonPrimitive?.contentOrNull ?: "–"
            "${ChallengeTexts.varLabel(k.removePrefix("s.").removePrefix("$"))} $old -> $new"
        }
    }.getOrDefault("")

    private fun eventLabel(def: CompiledDefinition, e: ChallengeEventEntity): String? {
        val p = runCatching { Json.parseToJsonElement(e.payload).jsonObject }.getOrNull()
        return when (e.event_type) {
            EventTypes.CREATED -> if (e.supersedes_event_id != null) "Earlier tests corrected" else "Challenge started"
            EventTypes.APPROVAL -> {
                val id = p?.get("proposal_id")?.jsonPrimitive?.contentOrNull
                val title = id?.let { proposalTitle(def, it) } ?: "Recommended change"
                when (p?.get("decision")?.jsonPrimitive?.contentOrNull) {
                    "approved" -> "On: $title"
                    "declined" -> "Off: $title"
                    else -> "Changed: $title"
                }
            }
            EventTypes.SESSION_COMPLETED -> "Session ${p?.get("slot")?.jsonPrimitive?.contentOrNull ?: ""}"
            EventTypes.SESSION_AMENDED -> "Session edited"
            EventTypes.TEST_COMPLETED -> "Test day"
            EventTypes.TEST_DUE -> null
            EventTypes.PAIN -> if (p?.get("retracted")?.jsonPrimitive?.contentOrNull == "true") "Pain report withdrawn" else "Pain reported"
            EventTypes.READINESS -> null
            EventTypes.WEEK_CLOSED -> "Week ${p?.get("iso_week")?.jsonPrimitive?.contentOrNull?.substringAfter('W')?.trimStart('0') ?: ""} closed"
            EventTypes.PAUSE -> "Paused"
            EventTypes.RESUME -> "Resumed after ${p?.get("gap_days")?.jsonPrimitive?.contentOrNull ?: "?"} days"
            EventTypes.ABANDON -> "Abandoned"
            EventTypes.OVERRIDE -> "Value corrected"
            EventTypes.HABIT_COMPLETED -> null
            EventTypes.ATTEMPT -> "Attempt logged"
            EventTypes.MIGRATED -> "Program updated to version ${p?.get("to_version")?.jsonPrimitive?.contentOrNull ?: "?"}"
            else -> null
        }
    }

    private fun tests(s: EnrollmentState): List<TestUi> {
        val t = s.lastValidTest ?: return emptyList()
        val lines = t.metrics.entries.filter { !it.key.endsWith("_lo") && !it.key.endsWith("_hi") }.map { (k, v) ->
            val note = if (k in t.notComparable) " (not comparable)" else ""
            "${ChallengeTexts.metricLabel(k)}: ${ChallengeTexts.value(v, k)}$note"
        }
        return listOf(TestUi(ChallengeTexts.date(t.date), lines, "Counts for the goals"))
    }

    /** CHALLENGE_ENGINE 4.9: state vars that hold loads, pins, holds and switches, the block week and the planned test date. */
    private fun targets(def: CompiledDefinition, s: EnrollmentState): List<CorrectTarget> {
        val vars = def.stateVars.filter { !it.spec.static && it.spec.tag != "E" && it.spec.name in def.snapshotVars + listOf("bVariant", "needsCleanSession") }.map { v ->
            val step = when ((v.type as? Type.Q)?.dim) {
                dev.fitnessapp.algo.challenge.fitexpr.Dim.MASS -> BigDecimal("2.5")
                dev.fitnessapp.algo.challenge.fitexpr.Dim.PIN -> BigDecimal(5)
                dev.fitnessapp.algo.challenge.fitexpr.Dim.DURATION -> BigDecimal.ONE
                dev.fitnessapp.algo.challenge.fitexpr.Dim.INT -> BigDecimal.ONE
                dev.fitnessapp.algo.challenge.fitexpr.Dim.NUM -> BigDecimal("0.01")
                else -> null
            }
            CorrectTarget(v.spec.name, ChallengeTexts.varLabel(v.spec.name), s.vars[v.spec.name], v.type, step, v.spec.values)
        }
        return vars + listOf(
            CorrectTarget("block.week", "Week of the block", Values.int(s.block.week), Type.INT, BigDecimal.ONE),
            CorrectTarget("plannedTest.date", "Planned test date", s.plannedTest.date?.let(::DateV), Type.DateT, null),
        )
    }

    // ------------------------------------------------------------------------------------------ actions

    private fun record(type: String, payload: EventPayload, then: suspend () -> Unit = {}) {
        viewModelScope.launch {
            val today = LocalDate.now(zone)
            runCatching { c.challenges.record(enrollmentId, ChallengeEventDraft(type, payload, Instant.now(), today)) }
                .onSuccess { o ->
                    _messages.value = o.notifications.map { it.kind to ChallengeTexts.message(o.folded.def, it) } + o.warnings.map { "warn" to it }
                    then()
                }
                .onFailure { _error.value = it.message }
        }
    }

    fun decide(proposalId: String, decision: String) {
        val previous = _ui.value?.proposals?.firstOrNull { it.id == proposalId }?.decision
        if (previous == decision) return
        record(EventTypes.APPROVAL, EventPayload.ApprovalChanged(proposalId, decision)) {
            c.fgsEvent("challenge_proposal_decided", mapOf("proposal_id" to proposalId, "decision" to decision, "previous" to previous))
        }
    }

    fun pause(reasonCode: String) = record(EventTypes.PAUSE, EventPayload.Pause(reasonCode))

    fun resume() {
        val since = _ui.value?.pausedSince ?: return
        record(EventTypes.RESUME, EventPayload.Resume("other", ChronoUnit.DAYS.between(since, LocalDate.now(zone))))
    }

    fun abandon() = record(EventTypes.ABANDON, EventPayload.Abandon("other"))

    fun correct(target: String, value: Value, reasonCode: String) = record(EventTypes.OVERRIDE, EventPayload.ManualOverride(target, value, reasonCode))

    /** UX 14.7 Report pain: the wellness log and `pain.reported` for every running challenge (C33). */
    fun reportPain(region: String, side: String, severity: String, symptoms: List<String>) {
        viewModelScope.launch {
            runCatching {
                val wid = c.sessionRepository.reportPain(null, null, region, side, severity, "standalone", "challenge_dashboard", symptoms)
                val outcomes = c.challengeSessions.forwardPain(wid, region, side, severity, symptoms, null)
                _messages.value = outcomes.flatMap { o -> o.notifications.map { it.kind to ChallengeTexts.message(o.folded.def, it) } }
            }.onFailure { _error.value = it.message }
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _error.value = null
    }

    // ------------------------------------------------------------------------------------------ definition update

    /** The preview the user saw, kept for "Update": the update is written only for the state it was made on. */
    private var pending: Pair<CompiledDefinition, MigrationPreview>? = null

    /**
     * CHALLENGE_ENGINE 4.12 step 2 (D44): this and next week's planned sessions materialized under the running version
     * and the new one, compared item by item, with the rule and proposal changes.
     */
    suspend fun updatePreview(): UpdatePreviewUi = withContext(Dispatchers.Default) {
        c.challenges.catchUp(force = true)
        val f = c.challenges.folded(enrollmentId)
        val to = requireNotNull(c.challenges.updateFor(enrollmentId)) { "No update available" }
        val facts = c.challengeFacts.facts(f.def, f.state)
        val sessions = (0..1).flatMap { w -> ChallengeWeek.plan(c, f, facts, weekOffset = w).second.sessions.filter { it.source != "done" }.map { it.slot to it.date } }
        val today = LocalDate.now(zone)
        val p = MigrationPreviews.preview(f.def, to, c.params, f.state, facts, c.catalog.doc, sessions, today, AppContainer.CHANGEOVER_MIN)
        pending = to to p
        val notes = (to.doc.migrations[f.def.version] as? kotlinx.serialization.json.JsonObject)?.get("notes")?.jsonPrimitive?.contentOrNull
        UpdatePreviewUi(
            f.def.version, to.version, notes,
            p.sessions.filter { it.changes.isNotEmpty() }.map { d ->
                val day = d.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH))
                "${if (d.slot == "TEST") "Test day" else "Session ${d.slot}"} · $day" to d.changes.map { ch -> itemLine(ch) }
            },
            p.rules.map { r ->
                val text = ChallengeTexts.ruleExplain(if (r.kind == "removed") f.def else to, r.id)
                when (r.kind) {
                    "added" -> "New rule: $text"
                    "removed" -> "Rule removed: $text"
                    else -> "Rule changed: $text"
                }
            },
            p.newProposals.map { id -> "New proposal, off until you decide: ${proposalTitle(to, id) ?: id}" },
        )
    }

    private fun itemLine(ch: ItemChange): String {
        fun name(i: PlannedItem) = (i.name["en"] ?: i.key) + if (i.role == "warmup") " (warm-up)" else if (i.role == "prehab") " (prehab)" else ""
        val b = ch.before
        val a = ch.after
        return when {
            b == null && a != null -> "Added: ${name(a)}" + ChallengeLines.detailOf(a).let { if (it.isNotEmpty()) " · $it" else "" }
            a == null && b != null -> "Removed: ${name(b)}"
            a != null && b != null -> {
                val (was, now) = ChallengeLines.detailOf(b) to ChallengeLines.detailOf(a)
                "Changed: ${name(a)}" + if (was != now) " · $was → $now" else ""
            }
            else -> ch.key
        }
    }

    /** "Update" in the preview: `definition.migrated` (4.12 step 3). A preview made on an older state is refused. */
    fun applyUpdate(onDone: () -> Unit) {
        val (to, p) = pending ?: return
        viewModelScope.launch {
            runCatching { c.challenges.migrate(enrollmentId, to, p) }
                .onSuccess { o ->
                    pending = null
                    _messages.value = listOf("info" to "Updated to version ${to.version}.") +
                        o.notifications.map { it.kind to ChallengeTexts.message(o.folded.def, it) } + o.warnings.map { "warn" to it }
                }
                .onFailure { _error.value = it.message }
            onDone()
        }
    }

    suspend fun pausePreview(): List<Pair<Long, List<String>>> = withContext(Dispatchers.Default) {
        val f = c.challenges.folded(enrollmentId)
        PauseTools.previewResume(f.def, c.params, f.state, LocalDate.now(zone), zone, f.state.lastFacts, listOf(1, 7, 28))
            .map { p -> p.gapDays to p.notifications.map { ChallengeTexts.message(f.def, it) } }
    }

    /** A week pill's slot name: the program's own letters (A, B, C), "Test" for the test slot. */
    private fun slotLabel(slot: String) = if (slot == "TEST") "Test" else slot
}
