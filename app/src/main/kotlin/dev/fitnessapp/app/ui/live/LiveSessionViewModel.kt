package dev.fitnessapp.app.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.load.LoadPoint
import dev.fitnessapp.algo.core.load.LoadScheme
import dev.fitnessapp.algo.core.load.Rounding
import dev.fitnessapp.algo.prescription.rest.RestClass
import dev.fitnessapp.algo.prescription.rest.RestInput
import dev.fitnessapp.algo.prescription.rest.RestPolicy
import dev.fitnessapp.algo.prescription.warmup.WarmupRest
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.notify.SessionNotificationText
import dev.fitnessapp.app.session.SessionService
import dev.fitnessapp.app.uidet.ChipContext
import dev.fitnessapp.app.uidet.ChipSet
import dev.fitnessapp.app.uidet.Deviation
import dev.fitnessapp.app.uidet.ExerciseFlags
import dev.fitnessapp.app.uidet.HistorySet
import dev.fitnessapp.app.uidet.PrefillContext
import dev.fitnessapp.app.uidet.PrefillSource
import dev.fitnessapp.app.uidet.SessionExerciseRef
import dev.fitnessapp.app.uidet.SetDraft
import dev.fitnessapp.app.uidet.SetSlot
import dev.fitnessapp.app.uidet.SetTarget
import dev.fitnessapp.app.uidet.SetValues
import dev.fitnessapp.app.uidet.SnapResult
import dev.fitnessapp.app.uidet.UiPrefill
import dev.fitnessapp.app.uidet.UiReasonChips
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.SessionExerciseEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import dev.fitnessapp.data.db.local.RestTimerEntity
import dev.fitnessapp.data.repo.LogSetCommand
import dev.fitnessapp.data.repo.PlannedValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.fitnessapp.data.sync.SyncState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** One exercise of the session with its catalog row and today's sets. */
data class ExerciseItem(
    val se: SessionExerciseEntity,
    val ex: CatalogExercise,
    val sets: List<SetLogEntity>,
    /** A challenge warm-up item (the row, face pulls, ...): its warm-up sets are its work. */
    val warmupItem: Boolean = false,
) {
    val working: List<SetLogEntity> get() = if (warmupItem) sets else sets.filter { it.set_kind != "warmup" }
    val unilateral: Boolean get() = se.side_mode != "bilateral"
    /** Working sets done; an L+R pair counts as one (C7), and the attempts of a round count as one set. */
    val workingDone: Int get() = working.distinctBy { it.set_index to it.side }.sumOf { if (it.side == "both") 2 else 1 } / 2
    val planned: Int get() = se.planned_sets ?: 0
    val open: Boolean get() = se.status in setOf("pending", "active", "deferred")
}

enum class HoldPhase { READY, LEAD_IN, HOLDING, RESULT }

/** The current-set card (UX 11.6): the slot being logged and the editable draft. */
data class Card(
    val item: ExerciseItem,
    val mode: LoadMode,
    val points: List<LoadPoint>,
    val scheme: LoadScheme,
    val loadIndex: Int?,
    val reps: Int?,
    val holdS: Double?,
    val setIndex: Int,
    val side: String,
    val warmup: Boolean,
    val prefillSource: PrefillSource,
    val target: SetValues,
    val sideCapApplied: Boolean,
    val deviation: Deviation,
    val chips: ChipSet?,
    val reason: String?,
    val reasonPosition: Int?,
    val flags: Set<String>,
    val lastLine: String?,
    val inputMethod: String,
    val taps: Int,
    val snapped: Boolean,
    val shownAt: Long,
    val firstTouchAt: Long?,
    val holdPhase: HoldPhase = HoldPhase.READY,
    val holdPhaseStart: Long = 0L,
    val holdTimerS: Double? = null,
    val holdTargetS: Double? = null,
    val keypadMessage: String? = null,
    /** Eccentric-only sets (UX 11.7.5): the shortest lowering of the set ("Shortest lowering") and its target. */
    val eccS: Double? = null,
    val eccTargetS: Double? = null,
    /** Top-hold rounds (UX 11.7.4): the seconds of each short attempt of the round; null for any other card. */
    val attemptsS: List<Double>? = null,
    /** Finger assistance (`assisted_ordinal`): the fingers used and the planned fingers. */
    val fingers: Int? = null,
    val plannedFingers: Int? = null,
    /** An attempts-block row (phase 3, `LogMode.ATTEMPT_CHECKLIST`): the metric it records and the definition's checklist. */
    val attempt: AttemptInfo? = null,
    /** The `gym_equipment` row of a kg or lb machine stack, which "Stack setup" edits (D43); null for other equipment. */
    val stackRowId: String? = null,
) {
    val isHold: Boolean get() = item.ex.measure == "duration"

    /** Negatives: logged by the lowering seconds and the effort chips, never an RIR chip (C66). */
    val isEcc: Boolean get() = item.ex.contraction == "eccentric_only"
    val isRound: Boolean get() = attemptsS != null

    /** A timed dynamic item (the warm-up row, UX 11.7.6): a countdown instead of a hold, logged in seconds. */
    val isTimed: Boolean get() = isHold && item.ex.contraction == "dynamic"
    val point: LoadPoint? get() = loadIndex?.let { points.getOrNull(it) }
    val setNumber: Int get() = item.workingDone + 1
    val slotKey: String get() = listOf(item.se.id, setIndex.toString(), side, mode.code, warmup.toString()).joinToString("|")
}

/** What the attempt card of a phase-3 attempts block needs (UX 14.9 attempt card). */
data class AttemptInfo(val metric: String, val checklist: List<String>)

/** The last tap's rows for Undo: one set, or the attempts of a round ([extraIds]). */
data class LastLogged(val setId: String, val label: String, val at: Long, val extraIds: List<String> = emptyList())

data class LiveUi(
    val session: WorkoutSessionEntity? = null,
    val items: List<ExerciseItem> = emptyList(),
    val focusedId: String? = null,
    val card: Card? = null,
    val rest: RestTimerEntity? = null,
    val restNext: String? = null,
    /** The next set's exercise and count, shown above [restNext] on the rest card. */
    val restNextTitle: String? = null,
    val lastLogged: LastLogged? = null,
    val switchHint: Boolean = false,
    val banner: String? = null,
    val pendingCount: Int = 0,
    /** The last in-session challenge rule that changed the remaining sets ("Rule: … · Undo", UX 11.10, D14). */
    val ruleLine: RuleLine? = null,
    /** A notice of an in-session rule (`notify`, for example "Hand slipped: end the set here."). */
    val ruleNotice: String? = null,
    /** "Session C" for a challenge session (the session notification and the header). */
    val title: String? = null,
)

data class RuleLine(val seId: String, val app: dev.fitnessapp.algo.challenge.materialize.RuleApplication, val text: String, val at: Long)

/**
 * The live session (UX 11): Room is the single source of truth; the VM keeps only the draft of the card being
 * filled. Prefill comes from `ui.prefill` with "last time" (D18) and today's changed previous set (D23); loads
 * come from LOAD-1; rest durations from REST-1 (D21); the timer from [dev.fitnessapp.app.timer.RestTimerController].
 */
class LiveSessionViewModel(private val c: AppContainer, private val sessionId: String) : ViewModel() {
    private val _ui = MutableStateFlow(LiveUi())
    val ui: StateFlow<LiveUi> = _ui
    private val params get() = c.params.fork()
    private var focusOverride: String? = null
    private val historyCache = java.util.concurrent.ConcurrentHashMap<String, List<HistorySet>>()

    /** The slot being written (double-tap guard): no card is shown until Room emits that set. */
    private data class PendingLog(val seId: String, val setIndex: Int, val side: String)

    @Volatile private var pendingLog: PendingLog? = null
    @Volatile private var pendingAt = 0L

    /** `exercise_focused` (UX 20.2): the last focused item and how the next change was caused. */
    private var lastFocus: String? = null
    private var focusVia: String? = null

    /** `deviation_shown` once per card slot and chip context (UX 20.3). */
    private var deviationShownKey: String? = null

    /** The live plan of a challenge session (CHALLENGE_ENGINE 6.1, 6.10); null for a free session. */
    @Volatile private var challenge: dev.fitnessapp.data.challenge.ChallengeLive? = null

    init {
        viewModelScope.launch {
            combine(
                c.sessionRepository.observeSession(sessionId),
                c.sessionRepository.observeExercises(sessionId),
                c.sessionRepository.observeSets(sessionId),
                c.restTimer.observe(sessionId),
                // Header cloud-off icon only while offline with changes (UX 7.1, 11.x).
                c.sync.observePending().combine(c.sync.state) { n, st -> if (st is SyncState.Offline) n else 0 },
            ) { s, exs, sets, rest, pending -> Snapshot(s, exs, sets, rest, pending) }.collect { rebuild(it) }
        }
        viewModelScope.launch {
            c.challengeSessions.observeLive(sessionId).collect { live ->
                challenge = live
                // A home mini-session is named after its habit item (UX 9.6), a challenge session after its slot.
                val title = live?.let { l ->
                    if (l.plan.slot == dev.fitnessapp.algo.challenge.materialize.HabitPlanner.HOME_SLOT) l.plan.items.firstOrNull()?.name?.get("en") else "Session ${l.plan.slot}"
                }
                _ui.update { it.copy(title = title) }
                last?.let { rebuild(it) }
            }
        }
    }

    private fun planItem(item: ExerciseItem) = challenge?.item(item.se.challenge_item_key)

    /** The planned row of a challenge item for the slot being logged. */
    private fun plannedRow(item: ExerciseItem, setIndex: Int, side: String, warmup: Boolean): dev.fitnessapp.algo.challenge.materialize.PlannedRow? {
        val live = challenge ?: return null
        val key = item.se.challenge_item_key ?: return null
        return c.challengeSessions.plannedRow(live, key, setIndex, side, warmup)
    }

    private data class Snapshot(
        val s: WorkoutSessionEntity?,
        val exs: List<SessionExerciseEntity>,
        val sets: List<SetLogEntity>,
        val rest: RestTimerEntity?,
        val pending: Int,
    )

    private var last: Snapshot? = null

    /** The equipment changed under the card ("Stack setup"): the next rebuild drops the draft for a fresh card. */
    @Volatile private var equipmentChanged = false

    private suspend fun rebuild(snap: Snapshot) {
        last = snap
        val items = snap.exs.mapNotNull { e ->
            c.catalog.byId(e.exercise_id)?.let { ex ->
                ExerciseItem(
                    e, ex, snap.sets.filter { it.session_exercise_id == e.id }.sortedBy { it.completed_at },
                    warmupItem = challenge?.item(e.challenge_item_key)?.plan?.role == "warmup",
                )
            }
        }
        val focus = focusOverride?.takeIf { id -> items.any { it.se.id == id && (it.open || it.workingDone < it.planned) } }
            ?: items.firstOrNull { it.open }?.se?.id
        if (focus != focusOverride) focusOverride = null
        val item = items.firstOrNull { it.se.id == focus }
        if (focus != null && focus != lastFocus) {
            if (lastFocus != null) emit("exercise_focused", focus, mapOf("via" to JsonPrimitive(focusVia ?: "auto")))
            lastFocus = focus
        }
        focusVia = null
        val old = _ui.value.card
        pendingLog?.let { p ->
            if (snap.sets.any { it.session_exercise_id == p.seId && it.set_index == p.setIndex && it.side == p.side }) pendingLog = null
        }
        val card = if (pendingLog != null) null else item?.let { buildCard(it, snap.sets, old) }
        val activeRest = snap.rest?.takeIf { it.state != "ended" }
        card?.let(::reportDeviation)
        _ui.update {
            it.copy(
                session = snap.s, items = items, focusedId = focus, card = card, rest = activeRest,
                restNext = card?.let(::nextLine), restNextTitle = card?.let(::nextTitle), pendingCount = snap.pending,
            )
        }
        updateNotification(snap.s, card)
    }

    /** Keeps the draft while the slot is the same; otherwise builds a fresh prefilled card. */
    private suspend fun buildCard(item: ExerciseItem, allSets: List<SetLogEntity>, old: Card?): Card {
        val mode = LoadMode.of(item.se.load_mode)
        val warmup = old?.takeIf { it.item.se.id == item.se.id }?.warmup ?: (planItem(item)?.plan?.role == "warmup")
        val (setIndex, side) = nextSlot(item, warmup)
        val fresh = buildFresh(item, mode, setIndex, side, warmup, allSets)
        val keep = old != null && old.slotKey == fresh.slotKey && !equipmentChanged
        equipmentChanged = false
        return if (keep) old!!.copy(item = item) else fresh
    }

    private fun nextSlot(item: ExerciseItem, warmup: Boolean): Pair<Int, String> {
        val maxIndex = item.sets.maxOfOrNull { it.set_index } ?: 0
        if (!item.unilateral || warmup) return (maxIndex + 1) to (if (item.unilateral) "L" else "both")
        val lastSet = item.sets.filter { it.set_index == maxIndex }
        return if (lastSet.any { it.side == "L" } && lastSet.none { it.side == "R" }) maxIndex to "R" else (maxIndex + 1) to "L"
    }

    private suspend fun buildFresh(item: ExerciseItem, mode: LoadMode, setIndex: Int, side: String, warmup: Boolean, allSets: List<SetLogEntity>): Card {
        val bw = _ui.value.session?.bodyweight_kg?.let { Scaled.toDouble(it, 2) } ?: last?.s?.bodyweight_kg?.let { Scaled.toDouble(it, 2) }
        val (resolved, points) = c.equipment.loadables(item.ex, mode, bw, params)
        val history = history(item)
        val todayWorking = item.sets.filter { it.side == side && (it.set_kind == "warmup") == warmup }
        val todayPrev = todayWorking.maxByOrNull { it.set_index }?.let { valuesOf(it) }
        val default = SetValues(mode, defaultNative(points, mode), if (item.ex.measure == "duration") null else DEFAULT_REPS, null)
        val leftReps = if (side == "R") item.sets.firstOrNull { it.set_index == setIndex && it.side == "L" }?.reps else null
        val pf = UiPrefill.prefill(
            SetSlot(setIndex, side, mode),
            PrefillContext(
                history = if (warmup) emptyList() else history, todayPrevious = todayPrev, default = default,
                // Right side reps never prefill above the left side's (UX 11.7.2); the program rule arrives with M2.
                sideRule = side == "R", leftLoggedReps = leftReps,
            ),
        ).value
        // A challenge item's targets come from its plan (CHALLENGE_ENGINE 6.1), as in-session rules left them.
        val row = plannedRow(item, setIndex, side, warmup)
        val planned = row?.let { r ->
            val native = when (mode) {
                LoadMode.ASSISTED_MACHINE -> r.pin?.toDouble()
                LoadMode.BODYWEIGHT_PLUS_EXTERNAL, LoadMode.EXTERNAL -> r.addedKg?.toDouble()
                else -> null
            }
            pf.copy(
                values = SetValues(mode, native ?: pf.values.native, r.targetReps ?: pf.values.reps, (r.targetHoldS ?: r.durationS)?.toDouble() ?: pf.values.holdS),
                source = PrefillSource.ENGINE_TARGET, sideCapApplied = false,
            )
        }
        val pfv = planned ?: pf
        val idx = indexFor(points, mode, pfv.values.native, resolved.scheme)
        // A round of short attempts (UX 11.7.4): prefilled from the same arm's previous round, else 0.
        val attempts = row?.attempts?.takeIf { it > 1 && item.ex.measure == "duration" && row.setKind != "test" }?.let { n ->
            val prev = item.sets.filter { it.side == side && it.set_index == setIndex - 1 }.sortedBy { it.attempt_index ?: 0 }
                .map { s -> s.duration_s?.let { Scaled.toDouble(it, 1) } ?: 0.0 }
            List(n) { i -> prev.getOrNull(i) ?: 0.0 }
        }
        val holdTarget = if (item.ex.measure == "duration" && attempts == null) pfv.values.holdS else null
        val eccTarget = row?.targetEccS?.toDouble() ?: item.se.planned_ecc_s?.let { Scaled.toDouble(it, 1) }
        val eccPrev = todayWorking.maxByOrNull { it.set_index }?.ecc_s_actual?.let { Scaled.toDouble(it, 1) }
        val fingers = if (mode == LoadMode.ASSISTED_ORDINAL) row?.fingers ?: todayWorking.maxByOrNull { it.set_index }?.assist_ordinal else null
        val now = System.currentTimeMillis()
        val base = Card(
            item = item, mode = mode, points = points, scheme = resolved.scheme, loadIndex = idx,
            reps = pfv.values.reps, holdS = if (attempts != null) null else pfv.values.holdS, setIndex = setIndex, side = side, warmup = warmup,
            prefillSource = pfv.source, target = pfv.values.copy(native = idx?.let { nativeOf(points[it], mode) } ?: pfv.values.native),
            sideCapApplied = pfv.sideCapApplied, deviation = Deviation.NONE, chips = null, reason = null, reasonPosition = null,
            flags = emptySet(), lastLine = lastLine(history, mode), inputMethod = "none", taps = 0, snapped = false,
            shownAt = now, firstTouchAt = null, holdTargetS = holdTarget,
            eccS = if (item.ex.contraction == "eccentric_only") eccPrev ?: eccTarget else null, eccTargetS = eccTarget,
            attemptsS = attempts, fingers = fingers, plannedFingers = row?.fingers,
            stackRowId = resolved.row?.id?.takeIf { (resolved.scheme as? LoadScheme.Stack)?.unit in setOf("kg", "lb") },
            attempt = row?.takeIf { it.logMode == dev.fitnessapp.algo.challenge.materialize.LogMode.ATTEMPT_CHECKLIST }?.let {
                planItem(item)?.plan?.metric?.let { m -> AttemptInfo(m, attemptChecklist()) }
            },
        )
        return withDeviation(base)
    }

    private suspend fun history(item: ExerciseItem): List<HistorySet> {
        val key = item.se.exercise_id
        historyCache[key]?.let { return it }
        val rows = c.sessionRepository.history(key, sessionId).filter { it.set.set_kind != "warmup" }
        val h = rows.map { r ->
            HistorySet(r.set.session_id, java.time.Instant.ofEpochMilli(r.session_started_at ?: r.set.completed_at), r.set.set_index, r.set.side, valuesOf(r.set))
        }
        // Working-set numbering: history set indexes include warm-ups; renumber working sets 1..n per session and side.
        val renum = h.groupBy { it.sessionId to it.side }.flatMap { (_, l) -> l.sortedBy { it.setIndex }.mapIndexed { i, x -> x.copy(setIndex = i + 1) } }
        historyCache[key] = renum
        return renum
    }

    private fun valuesOf(s: SetLogEntity): SetValues {
        val mode = LoadMode.of(s.load_mode)
        val native = when (mode) {
            LoadMode.ASSISTED_MACHINE -> s.assist_pin_native?.let { Scaled.toDouble(it, 2) }
            LoadMode.BODYWEIGHT -> null
            else -> s.input_value?.let { Scaled.toDouble(it, 2) }
        }
        return SetValues(mode, native, s.reps, s.duration_s?.let { Scaled.toDouble(it, 1) })
    }

    private fun nativeOf(p: LoadPoint, mode: LoadMode): Double? = when (mode) {
        LoadMode.ASSISTED_MACHINE -> p.assistPinNative
        LoadMode.BODYWEIGHT -> null
        else -> p.nativeValue
    }

    /** No history: a light, safe start (most assistance, empty bar, lightest dumbbell or stack step). */
    private fun defaultNative(points: List<LoadPoint>, mode: LoadMode): Double? = when (mode) {
        LoadMode.BODYWEIGHT -> null
        else -> points.firstOrNull()?.let { nativeOf(it, mode) }
    }

    private fun indexFor(points: List<LoadPoint>, mode: LoadMode, native: Double?, scheme: LoadScheme): Int? {
        if (points.isEmpty()) return null
        if (mode == LoadMode.BODYWEIGHT) return 0
        if (native == null) return 0
        points.indexOfFirst { nativeOf(it, mode) == native }.takeIf { it >= 0 }?.let { return it }
        // Equipment changed since last time: snap to the nearest loadable (ties to the easier load, C2).
        return when (val r = UiPrefill.snap(native, scheme, points.firstOrNull()?.implementCount ?: 1, points, params)) {
            is SnapResult.Snapped -> points.indexOf(r.point)
            is SnapResult.OutOfRange -> points.indexOf(r.limit)
        }
    }

    private fun lastLine(history: List<HistorySet>, mode: LoadMode): String? {
        val sid = history.maxByOrNull { it.sessionStart }?.sessionId ?: return null
        val sets = history.filter { it.sessionId == sid }.sortedWith(compareBy({ it.setIndex }, { it.side }))
        val first = sets.first()
        val load = when (mode) {
            LoadMode.ASSISTED_MACHINE -> first.values.native?.let { "Pin " + Numbers.num(it) + " lb" }
            LoadMode.BODYWEIGHT -> "BW"
            LoadMode.BODYWEIGHT_PLUS_EXTERNAL -> first.values.native?.let { "BW + " + Numbers.num(it) }
            else -> first.values.native?.let { Numbers.num(it) }
        }
        val perf = sets.joinToString(" ") { s -> (if (s.side != "both") s.side else "") + (s.values.reps?.toString() ?: s.values.holdS?.let { Numbers.num(it, 1) + "s" } ?: "") }
        val date = first.sessionStart.atZone(java.time.ZoneId.of("Europe/Amsterdam")).toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH))
        return "Last " + listOfNotNull(load, perf).joinToString(" × ") + " · " + date
    }

    private fun emit(type: String, sessionExerciseId: String?, payload: Map<String, JsonElement>, entityType: String = "session_exercise") {
        viewModelScope.launch {
            c.sessionRepository.event(
                dev.fitnessapp.data.telemetry.EventDraft(
                    type = type, sessionId = sessionId, entityType = if (sessionExerciseId == null) "none" else entityType,
                    entityId = sessionExerciseId, screen = "session.live", payload = payload,
                ),
            )
        }
    }

    private fun reportDeviation(card: Card) {
        val chips = card.chips ?: return
        val key = card.slotKey + "|" + chips.context.code + "|" + card.deviation.direction.code
        if (key == deviationShownKey) return
        deviationShownKey = key
        emit(
            "deviation_shown", card.item.se.id,
            mapOf(
                "context" to JsonPrimitive(chips.context.code), "direction" to JsonPrimitive(card.deviation.direction.code),
                "fields" to JsonArray(card.deviation.fields.map { JsonPrimitive(it.field) }),
                "codes_shown" to JsonArray(chips.visible.map { JsonPrimitive(it.code) }), "visible_count" to JsonPrimitive(chips.visible.size),
            ),
        )
    }

    // ------------------------------------------------------------------------------------------------ edits

    private fun edit(method: String, change: (Card) -> Card) {
        _ui.update { u ->
            val card = u.card ?: return@update u
            val restRunning = u.rest?.state == "running"
            val touched = card.copy(
                taps = card.taps + 1,
                inputMethod = if (card.inputMethod == "none" || card.inputMethod == method) method else "mixed",
                firstTouchAt = card.firstTouchAt ?: if (restRunning) null else System.currentTimeMillis(),
            )
            u.copy(card = withDeviation(change(touched)))
        }
        _ui.value.card?.let(::reportDeviation)
    }

    fun stepLoad(harder: Boolean) = edit("stepper") { card ->
        val p = card.point ?: return@edit card
        val next = UiPrefill.stepLoad(card.points, p, harder, params)
        card.copy(loadIndex = card.points.indexOf(next), keypadMessage = null)
    }

    fun stepReps(up: Boolean) = edit("stepper") { it.copy(reps = UiPrefill.stepReps(it.reps ?: 0, up)) }

    fun stepHold(up: Boolean) = edit("stepper") { it.copy(holdS = UiPrefill.stepHold(it.holdS ?: 0.0, up, it.holdTargetS, params)) }

    /** "Shortest lowering" (UX 11.7.5), on the hold step of the target. */
    fun stepEcc(up: Boolean) = edit("stepper") { it.copy(eccS = UiPrefill.stepHold(it.eccS ?: 0.0, up, it.eccTargetS ?: it.eccS, params)) }

    /** One attempt of a top-hold round (UX 11.7.4): 0.5 s steps for the short attempts. */
    fun stepAttempt(index: Int, up: Boolean) = edit("stepper") { card ->
        val a = card.attemptsS ?: return@edit card
        card.copy(attemptsS = a.mapIndexed { i, s -> if (i == index) UiPrefill.stepHold(s, up, s, params) else s })
    }

    /** Fingers of assistance (`assisted_ordinal`, 0-4): fewer fingers is harder. */
    fun stepFingers(up: Boolean) = edit("stepper") { it.copy(fingers = ((it.fingers ?: 0) + if (up) 1 else -1).coerceIn(0, MAX_FINGERS)) }

    /** Keypad "Set" (UX 16.7): snaps to the nearest loadable, ties to the easier load; logs `keypad_used`. */
    fun keypad(typed: Double) {
        keypadEdit(typed)
        val card = _ui.value.card ?: return
        val snappedTo = card.point?.let { it.assistPinNative ?: it.nativeValue }
        emit(
            "keypad_used", card.item.se.id,
            mapOf(
                "field" to JsonPrimitive("load"), "typed" to JsonPrimitive(typed),
                "snapped_to" to (snappedTo?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull), "changed" to JsonPrimitive(snappedTo != typed),
            ),
        )
    }

    private fun keypadEdit(typed: Double) = edit("keypad") { card ->
        if (card.points.isEmpty()) return@edit card
        when (val r = UiPrefill.snap(typed, card.scheme, card.point?.implementCount ?: 1, card.points, params)) {
            is SnapResult.Snapped -> card.copy(
                loadIndex = card.points.indexOf(r.point), snapped = card.snapped || r.changed,
                keypadMessage = if (r.changed) "Set to " + Numbers.loadMain(r.point, card.mode) + " (nearest you can load)" else null,
            )
            is SnapResult.OutOfRange -> card.copy(
                keypadMessage = "Not loadable here (" + (if (r.above) "maximum " else "minimum ") + Numbers.loadMain(r.limit, card.mode) + ")",
            )
        }
    }

    fun keypadChips(typed: Double): Pair<LoadPoint?, LoadPoint?> {
        val card = _ui.value.card ?: return null to null
        return UiPrefill.keypadChips(typed, card.scheme, card.point?.implementCount ?: 1, card.points, params)
    }

    fun toggleFlag(flag: String) {
        edit("none") { it.copy(flags = if (flag in it.flags) it.flags - flag else it.flags + flag) }
        viewModelScope.launch {
            val on = _ui.value.card?.flags?.contains(flag) == true
            c.sessionRepository.event(dev.fitnessapp.data.telemetry.EventDraft(type = "flag_toggled", sessionId = sessionId, entityType = "none", payload = mapOf("flag" to JsonPrimitive(flag), "on" to JsonPrimitive(on))))
        }
    }

    fun chooseReason(code: String, position: Int, viaMore: Boolean) {
        _ui.update { u -> u.copy(card = u.card?.copy(reason = if (u.card.reason == code) null else code, reasonPosition = position)) }
        val card = _ui.value.card ?: return
        viewModelScope.launch {
            c.sessionRepository.event(
                dev.fitnessapp.data.telemetry.EventDraft(
                    type = "reason_selected", sessionId = sessionId, entityType = "session_exercise", entityId = card.item.se.id,
                    payload = mapOf(
                        "reason_code" to JsonPrimitive(code), "context" to JsonPrimitive(card.chips?.context?.code ?: "harder"),
                        "chip_position" to JsonPrimitive(position), "via_more" to JsonPrimitive(viaMore),
                    ),
                ),
            )
        }
    }

    fun setWarmup(on: Boolean) {
        val card = _ui.value.card ?: return
        viewModelScope.launch {
            val s = last ?: return@launch
            val (index, side) = nextSlot(card.item, on)
            val fresh = buildFresh(card.item, card.mode, index, side, on, s.sets)
            _ui.update { it.copy(card = fresh) }
        }
    }

    /** The deviation test of UI-04 against the prefilled values, and the reason chips of UI-05. */
    private fun withDeviation(card: Card): Card {
        if (card.warmup) return card.copy(deviation = Deviation.NONE, chips = null)
        val targetPoint = card.target.native?.let { n -> card.points.firstOrNull { nativeOf(it, card.mode) == n } }
        val step = targetPoint?.let { tp -> Rounding.nextHarder(card.points, tp.scaleKg, params)?.let { it.scaleKg - tp.scaleKg } }
            ?: targetPoint?.let { tp -> Rounding.nextEasier(card.points, tp.scaleKg, params)?.let { tp.scaleKg - it.scaleKg } }
        val dev = UiPrefill.deviation(
            SetDraft(load = card.point, reps = card.reps, holdS = if (card.holdPhase == HoldPhase.RESULT) card.holdS else null),
            SetTarget(load = targetPoint, repsLo = card.target.reps, repsHi = card.target.reps, holdLoS = card.holdTargetS),
            step, params,
        )
        val ctx = dev.chipContext
        val chips = ctx?.let { UiReasonChips.chipsFor(it, ExerciseFlags(card.item.ex.flagsOffered), c.reasons, params).value }
        val reason = card.reason?.takeIf { r -> chips != null && (chips.visible + chips.more).any { it.code == r } }
        return card.copy(deviation = dev, chips = chips, reason = reason)
    }

    // ------------------------------------------------------------------------------------------------ logging

    /** One tap logs the set (D9): RIR chip (0-5, 5 = "5+"), felt chip for holds, or Done for warm-ups. */
    /** The definition's attempt checklist (`challengeRules.attemptChecklist`), read once per session. */
    private var checklist: List<String>? = null

    private suspend fun attemptChecklist(): List<String> = checklist ?: runCatching {
        val live = challenge ?: return emptyList()
        c.challenges.folded(live.enrollmentId).def.doc.challengeRules.attemptChecklist.mapNotNull { it["en"] }
    }.getOrDefault(emptyList()).also { checklist = it }

    /** The attempt card (phase 3): a clean attempt is one rep, a failed one none (`failed_rep`); `attempt.logged` follows. */
    fun logAttempt(success: Boolean) {
        _ui.update { u -> u.copy(card = u.card?.copy(reps = if (success) 1 else 0, flags = if (success) emptySet() else setOf("failed_rep"))) }
        log(control = "attempt")
    }

    fun log(rir: Int? = null, feltReserveS: Double? = null, feltLowerBound: Boolean = false, control: String) {
        val u = _ui.value
        val card = u.card ?: return
        val s = u.session ?: return
        if (card.isHold && !card.isRound && card.holdS == null) return
        if (card.mode == LoadMode.ASSISTED_ORDINAL && card.fingers == null) return
        if (pendingLog != null) {
            emit("log_double_tap_dropped", null, mapOf("control" to JsonPrimitive(control), "gap_ms" to JsonPrimitive(System.currentTimeMillis() - pendingAt)))
            return
        }
        // Double-tap guard: the next card is rebuilt from Room once the set is there (rebuild clears it).
        pendingLog = PendingLog(card.item.se.id, card.setIndex, card.side)
        pendingAt = System.currentTimeMillis()
        _ui.update { it.copy(card = null) }
        // The app scope: leaving the screen right after the tap must not cancel the write or the rest alarm.
        c.appScope.launch {
            try {
                commitLog(u, card, rir, feltReserveS, feltLowerBound, control)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                pendingLog = null
                _ui.update { it.copy(banner = "log_failed") }
                viewModelScope.launch { last?.let { rebuild(it) } }
            }
        }
    }

    private suspend fun commitLog(u: LiveUi, card: Card, rir: Int?, feltReserveS: Double?, feltLowerBound: Boolean, control: String) {
        run {
            val now = System.currentTimeMillis()
            val all = last?.sets.orEmpty()
            val prevCompleted = all.maxOfOrNull { it.completed_at }
            val pv = params
            val estDuration = (card.reps ?: 0) * pv.double("time.tempo_s") + pv.double("time.per_set_s") // TIME-1 seed (C19)
            val holdStart = if (card.isHold && card.holdPhase == HoldPhase.RESULT) card.holdPhaseStart else null
            val rest = c.restTimer.onNextSetLogged(sessionId, prevCompleted, now, card.firstTouchAt, holdStart, estDuration)
            val p = card.point
            val telemetry = mutableMapOf<String, JsonElement>(
                "prefill_source" to JsonPrimitive(card.prefillSource.code),
                "side_cap_applied" to JsonPrimitive(card.sideCapApplied),
                "deviation_direction" to JsonPrimitive(card.deviation.direction.code),
                "deviation_fields" to JsonArray(card.deviation.fields.map { JsonPrimitive(it.field) }),
                "input_method" to JsonPrimitive(card.inputMethod),
                "taps" to JsonPrimitive(card.taps),
                "snapped" to JsonPrimitive(card.snapped),
                "log_control" to JsonPrimitive(control),
                "ms_from_card_shown" to JsonPrimitive(now - card.shownAt),
            )
            rir?.let { telemetry["chip_value"] = JsonPrimitive(it.toString()) }
            feltReserveS?.let { telemetry["chip_value"] = JsonPrimitive(feltCode(it)) }
            card.firstTouchAt?.let { telemetry["first_touch_at"] = JsonPrimitive(it) }
            u.rest?.let { r -> if (r.state != "running") telemetry["ms_from_rest_end"] = JsonPrimitive(now - r.end_wall_ms) }
            val flags = card.flags + listOfNotNull(card.reason?.takeIf { it == "torso_rotation" || it == "technique_breakdown" })
            val ordinal = card.mode == LoadMode.ASSISTED_ORDINAL
            val cmd = LogSetCommand(
                sessionId = sessionId, sessionExerciseId = card.item.se.id, exerciseId = card.item.se.exercise_id,
                setIndex = card.setIndex, side = card.side, setKind = if (card.warmup) "warmup" else "working",
                load = if (ordinal) null else p, reps = if (card.isHold) null else card.reps, durationS = if (card.isHold) card.holdS else null,
                rir = rir?.toDouble(), rirLowerBound = rir == 5, holdReserveS = feltReserveS, holdReserveLowerBound = feltLowerBound,
                flags = flags.toList(), deviationReason = card.reason, countsAsWork = card.item.warmupItem,
                planned = PlannedValues(
                    reps = card.target.reps, inputValue = p?.takeIf { card.mode != LoadMode.ASSISTED_MACHINE && !ordinal }?.let { card.target.native },
                    inputUnit = p?.takeIf { !ordinal }?.nativeUnit, assistNative = if (card.mode == LoadMode.ASSISTED_MACHINE) card.target.native else null,
                    durationS = card.holdTargetS, eccS = card.eccTargetS, assistOrdinal = card.plannedFingers,
                    source = when {
                        card.prefillSource == PrefillSource.ENGINE_TARGET ->
                            if (plannedRow(card.item, card.setIndex, card.side, card.warmup)?.changedByRule != null) "challenge_rule" else "engine"
                        card.prefillSource == PrefillSource.DEFAULT -> "default"
                        else -> "prefill_last"
                    },
                ),
                rest = rest, startedAtMs = holdStart, completedAtMs = now, telemetry = telemetry,
                eccSActual = if (card.isEcc) card.eccS else null, eccSTarget = if (card.isEcc) card.eccTargetS else null,
                assistOrdinal = if (ordinal) card.fingers else null,
            )
            // A round logs one row per attempt with the round's effort chip (UX 11.7.4); the rest belongs to the first.
            val cmds = card.attemptsS?.mapIndexed { i, s ->
                cmd.copy(durationS = s, roundIndex = card.setIndex, attemptIndex = i + 1, rest = if (i == 0) rest else null)
            } ?: listOf(cmd)
            val ids = c.sessionRepository.logSets(cmds)
            val setId = ids.first()
            historyCache.remove(card.item.se.exercise_id)
            if (card.item.se.challenge_item_key != null && challenge != null) applyChallengeRules(card.item.se.id)
            // CHALLENGE_ENGINE 4.2: a phase-3 attempt is an `attempt.logged` event; the gate reads the phase's observations.
            card.attempt?.let { a ->
                val live = challenge
                if (live != null) {
                    runCatching {
                        c.challenges.record(
                            live.enrollmentId,
                            dev.fitnessapp.data.challenge.ChallengeEventDraft(
                                dev.fitnessapp.algo.challenge.enrollment.EventTypes.ATTEMPT,
                                dev.fitnessapp.algo.challenge.enrollment.EventPayload.AttemptLogged(a.metric, card.side, card.reps == 1),
                                java.time.Instant.ofEpochMilli(now), u.session?.local_date?.let(java.time.LocalDate::parse) ?: java.time.LocalDate.now(), sourceSessionId = sessionId,
                            ),
                        )
                    }
                }
            }
            val timer = card.holdTimerS
            if (card.isHold && timer != null && card.holdS != null && Numbers.oneDecimal(timer) != Numbers.oneDecimal(card.holdS)) {
                val t = Numbers.oneDecimal(timer).toDouble()
                val l = Numbers.oneDecimal(card.holdS).toDouble()
                c.sessionRepository.event(
                    dev.fitnessapp.data.telemetry.EventDraft(
                        type = "hold_corrected", sessionId = sessionId, entityType = "set", entityId = setId, screen = "session.live",
                        payload = mapOf("timer_s" to JsonPrimitive(t), "logged_s" to JsonPrimitive(l), "delta_s" to JsonPrimitive(Numbers.oneDecimal(l - t).toDouble())),
                    ),
                )
            }
            if (card.reason == "pain") _ui.update { it.copy(banner = "pain") }
            val label = (if (card.side != "both") card.side + " · " else "") +
                when {
                    card.attemptsS != null -> "round ${card.setIndex}: " + card.attemptsS.joinToString(" ") { Numbers.num(it, 1) } + " s"
                    card.isHold -> Numbers.num(card.holdS ?: 0.0, 1) + " s"
                    card.isEcc -> "${card.reps} reps · " + Numbers.num(card.eccS ?: 0.0, 1) + " s down"
                    else -> "${card.reps} reps"
                } +
                (rir?.let { " · $it in reserve" } ?: "")
            _ui.update { it.copy(lastLogged = LastLogged(setId, label, now, ids.drop(1))) }
            startRest(card, setId, now)
        }
    }

    /**
     * True when the set just logged on [card] finishes the last open item's planned working sets (UX 19.8): there is
     * no next set to rest for, so no timer or alarm starts. Adding a set or an exercise afterwards works as usual.
     */
    private fun completesPlannedWork(card: Card): Boolean =
        completesPlannedWork(_ui.value.items, card.item.se.id, card.setNumber, card.item.planned, card.warmup, card.side)

    private fun feltCode(s: Double) = when (s) { 0.0 -> "max"; 2.0 -> "hard"; 5.0 -> "ok"; else -> "easy" }

    /** REST-1 base policy (D21); a left side switches immediately (between-sides 0 s, UX 11.7.2). */
    private suspend fun startRest(card: Card, setId: String, loggedAt: Long) {
        val p = params
        if (completesPlannedWork(card)) return
        if (card.side == "L") {
            _ui.update { it.copy(switchHint = true) }
            kotlinx.coroutines.delay(c.platformConfig.timerSwitchHintMs)
            _ui.update { it.copy(switchHint = false) }
            return
        }
        val decision = if (card.warmup) {
            val toWork = warmupLeadsToWork(_ui.value.items, card.item.se.id, card.setNumber, card.item.planned, card.item.warmupItem)
            RestPolicy.decide(RestInput(RestClass.of(card.item.ex.restClass), prevWasWarmup = true, warmupRestS = WarmupRest.afterWarmup(null, toWork, p)), p)
        } else {
            RestPolicy.decide(RestInput(RestClass.of(card.item.ex.restClass), targetReps = card.target.reps), p)
        }
        // A challenge row prescribes its own rest (`rest_s`, CHALLENGE_ENGINE 6.1); REST-1 keeps the hard maximum.
        val planned = plannedRow(card.item, card.setIndex, card.side, card.warmup)?.restS
        c.restTimer.startAfter(
            sessionId, setId, card.item.se.exercise_id, card.side, loggedAt, planned ?: decision.timerS.toInt(), decision.hardMaxS.toInt(),
            "rest", if (planned != null) "challenge" else "rest_module",
        )
    }

    fun undoLast() {
        val l = _ui.value.lastLogged ?: return
        _ui.update { it.copy(lastLogged = null) }
        c.appScope.launch {
            c.restTimer.cancelForUndo(sessionId, l.at)
            (listOf(l.setId) + l.extraIds).forEach { c.sessionRepository.undoSet(it) }
            historyCache.clear()
        }
    }

    fun dismissUndo() = _ui.update { it.copy(lastLogged = null) }

    /** 6.10: the item's in-session rules after a logged set; a change shows the rule line, a notice the banner. */
    private suspend fun applyChallengeRules(seId: String) {
        val apps = runCatching { c.challengeSessions.onSetLogged(sessionId, seId) }.getOrDefault(emptyList())
        if (apps.isEmpty()) return
        val def = challenge?.enrollmentId?.let { runCatching { c.challenges.folded(it).def }.getOrNull() }
        fun text(msg: String?, values: Map<String, String>): String? {
            var t = msg?.let { m -> def?.doc?.messages?.get(m)?.get("en") } ?: return msg
            for ((k, v) in values) t = t.replace("{$k}", v)
            return t
        }
        val change = apps.lastOrNull { it.kind == "suggest" }
        val notices = apps.filter { it.kind == "notify" }.mapNotNull { text(it.msg, it.msgParams) }
        _ui.update { u ->
            u.copy(
                ruleLine = change?.let { a ->
                    val field = when (a.field) {
                        "assistPin" -> "pin"
                        "added" -> "load"
                        "hold_s" -> "hold"
                        "targetReps" -> "reps"
                        else -> a.field ?: ""
                    }
                    RuleLine(seId, a, "Rule: $field ${a.from ?: "–"} → ${a.to ?: "–"}" + (text(a.msg, a.msgParams)?.let { " · $it" } ?: ""), System.currentTimeMillis())
                } ?: u.ruleLine,
                ruleNotice = notices.joinToString(" ").ifEmpty { null } ?: u.ruleNotice,
            )
        }
    }

    /** Undo of the rule line (C46): the rule's change is taken back and the reason is stored on the exercise. */
    fun undoRule(reasonCode: String) {
        val line = _ui.value.ruleLine ?: return
        _ui.update { it.copy(ruleLine = null) }
        c.appScope.launch { c.challengeSessions.undo(sessionId, line.seId, line.app, reasonCode, System.currentTimeMillis() - line.at) }
    }

    fun dismissRuleLine() = _ui.update { it.copy(ruleLine = null) }

    fun dismissRuleNotice() = _ui.update { it.copy(ruleNotice = null) }

    fun adjustRest(delta: Int) = viewModelScope.launch { c.restTimer.adjust(delta, if (kotlin.math.abs(delta) > c.platformConfig.timerAdjustStepS) "long_press" else "button") }

    fun skipRest() = viewModelScope.launch { c.restTimer.skip("button") }

    /**
     * Start on a timed warm-up item (UX 11.7.6): its alarm-backed countdown (D33, ANDROID.md 12.3); zero logs the item
     * done with its seconds, Done before zero with the measured seconds. Any running rest ends first.
     */
    fun startInterval() {
        val card = _ui.value.card ?: return
        val seconds = (card.holdS ?: return).toInt()
        val source = if (challenge != null) "challenge" else "protocol"
        c.appScope.launch {
            c.restTimer.startInterval(
                sessionId, card.item.se.id, card.item.se.exercise_id, card.setIndex, card.side, card.item.ex.nameEn,
                seconds, maxOf(seconds, params.int("rest.timer_hard_max_s")), source,
            )
        }
    }

    /** `rest_prewarn` (UX 20.6): the 10 s pre-warning shown while the app is visible (C72). */
    fun prewarned(timerId: String) = emit("rest_prewarn", null, mapOf("timer_id" to JsonPrimitive(timerId)))

    // ------------------------------------------------------------------------------------------------ holds

    fun startHold() {
        _ui.update { u -> u.copy(card = u.card?.copy(holdPhase = HoldPhase.LEAD_IN, holdPhaseStart = System.currentTimeMillis())) }
        val card = _ui.value.card ?: return
        emit(
            "hold_started", card.item.se.id,
            buildMap {
                put("mode", JsonPrimitive(if (card.holdTargetS != null) "prescribed" else "max"))
                put("side", JsonPrimitive(card.side))
                card.holdTargetS?.let { put("target_s", JsonPrimitive(it)) }
                put("lead_in_s", JsonPrimitive(c.platformConfig.holdLeadInS))
            },
        )
    }

    fun holdGo() = _ui.update { u -> u.copy(card = u.card?.copy(holdPhase = HoldPhase.HOLDING, holdPhaseStart = System.currentTimeMillis())) }

    fun cancelHold() {
        val card = _ui.value.card
        _ui.update { u -> u.copy(card = u.card?.copy(holdPhase = HoldPhase.READY)) }
        if (card?.holdPhase == HoldPhase.LEAD_IN) {
            emit("hold_lead_in_cancelled", card.item.se.id, mapOf("ms_into_lead_in" to JsonPrimitive(System.currentTimeMillis() - card.holdPhaseStart)))
        }
    }

    /** Stop (tap anywhere, UX 11.7.3): prefill the target when reached, else the time down to the step. */
    fun stopHold(auto: Boolean = false) {
        val card = _ui.value.card ?: return
        if (card.holdPhase != HoldPhase.HOLDING) return
        val elapsed = (System.currentTimeMillis() - card.holdPhaseStart) / 1000.0
        if (!auto && elapsed * 1000 < c.platformConfig.holdStopGuardMs) return
        val stepBasis = card.holdTargetS ?: card.target.holdS
        val result = UiPrefill.holdResult(elapsed, card.holdTargetS, params, stepBasis)
        _ui.update { u -> u.copy(card = u.card?.let { withDeviation(it.copy(holdPhase = HoldPhase.RESULT, holdTimerS = elapsed, holdS = result)) }) }
        viewModelScope.launch {
            c.sessionRepository.event(
                dev.fitnessapp.data.telemetry.EventDraft(
                    type = "hold_stopped", sessionId = sessionId, entityType = "session_exercise", entityId = card.item.se.id,
                    payload = mapOf("elapsed_s" to JsonPrimitive(Numbers.oneDecimal(elapsed).toDouble()), "via" to JsonPrimitive(if (auto) "auto_max" else "tap")),
                ),
            )
        }
    }

    // ------------------------------------------------------------------------------------------------ exercises

    fun focus(id: String) {
        focusOverride = id
        focusVia = "list_tap"
        last?.let { s -> viewModelScope.launch { rebuild(s) } }
    }

    fun addExercise(ex: CatalogExercise) = viewModelScope.launch {
        val id = c.sessionRepository.addExercise(sessionId, ex)
        focusOverride = id
    }

    fun setLoadMode(mode: LoadMode) {
        val card = _ui.value.card ?: return
        viewModelScope.launch { c.sessionRepository.setLoadMode(card.item.se.id, mode) }
    }

    /** "Stack setup" (D43): saves this machine's stack, then rebuilds the card on it (`gym_equipment` is not observed). */
    fun saveStack(unit: String, start: Double, step: Double, max: Double) {
        val rowId = _ui.value.card?.stackRowId ?: return
        viewModelScope.launch {
            c.equipment.saveStack(rowId, unit, start, step, max)
            equipmentChanged = true
            last?.let { rebuild(it) }
        }
    }

    fun skip(seId: String, reason: String, position: Int) = viewModelScope.launch { c.sessionRepository.skip(seId, reason, position) }

    fun endExercise(seId: String, reason: String) = viewModelScope.launch { c.sessionRepository.end(seId, reason) }

    fun remove(seId: String) = viewModelScope.launch { c.sessionRepository.remove(seId) }

    fun addSet(seId: String) = viewModelScope.launch {
        c.sessionRepository.addSet(seId)
        focusOverride = seId
    }

    fun canDoLater(seId: String): Boolean = UiReasonChips.canDoLater(refs(), seId)

    /** Do later (UI-05): after the next not-done exercise; the defer count rises. */
    fun doLater(seId: String) = viewModelScope.launch {
        val item = _ui.value.items.firstOrNull { it.se.id == seId } ?: return@launch
        val r = UiReasonChips.doLater(refs(), seId, item.se.defer_count, params)
        if (r.moved) {
            focusOverride = null
            focusVia = "deferred_return"
            c.sessionRepository.reorder(sessionId, r.order, seId, deferred = true, via = "do_later")
        }
    }

    fun move(seId: String, up: Boolean) {
        val ids = _ui.value.items.map { it.se.id }.toMutableList()
        val i = ids.indexOf(seId)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in ids.indices) return
        ids[i] = ids[j].also { ids[j] = ids[i] }
        reorder(ids, seId, via = "menu")
    }

    /** A new order of the session's exercises (UX 11.4): long-press drag (`drag`) or Move up / down (`menu`). */
    fun reorder(ids: List<String>, movedId: String, via: String) = viewModelScope.launch {
        if (ids == _ui.value.items.map { it.se.id }) return@launch
        focusVia = "reorder"
        c.sessionRepository.reorder(sessionId, ids, movedId, deferred = false, via = via)
    }

    private fun refs() = _ui.value.items.map { SessionExerciseRef(it.se.id, it.open) }

    fun reportPain(region: String, side: String, severity: String, symptoms: List<String> = emptyList()) = viewModelScope.launch {
        val card = _ui.value.card
        val wid = c.sessionRepository.reportPain(sessionId, card?.item?.se?.id, region, side, severity, "in_session", "set_card", symptoms)
        // C33: the challenge's safety rules see every pain report.
        runCatching { c.challengeSessions.forwardPain(wid, region, side, severity, symptoms, sessionId) }
        // UX 11.11 step 1: the current exercise ends; its remaining sets are not done, reason pain.
        card?.item?.se?.id?.let { c.sessionRepository.end(it, "pain") }
        _ui.update { it.copy(banner = "pain") }
    }

    fun dismissBanner() = _ui.update { it.copy(banner = null) }

    // ------------------------------------------------------------------------------------------------ finish

    fun openExercises(): List<ExerciseItem> = _ui.value.items.filter { it.open }

    fun finish(endReason: String?, done: (String) -> Unit) = viewModelScope.launch {
        c.restTimer.sessionFinished(sessionId)
        c.sessionRepository.finish(sessionId, endReason)
        // CHALLENGE_ENGINE 6.12: the slot's `session.completed`; the Summary reads its rule audit.
        if (challenge != null) runCatching { c.challengeSessions.complete(sessionId) }
        stopService("finished")
        done(sessionId)
    }

    fun abandon(done: () -> Unit) = viewModelScope.launch {
        c.restTimer.sessionFinished(sessionId)
        c.sessionRepository.abandon(sessionId)
        stopService("abandoned")
        done()
    }

    private suspend fun stopService(reason: String) {
        SessionService.stop(c.appContext)
        c.notifications.cancelSession()
        c.fgsEvent("fgs_stopped", mapOf("reason" to reason))
    }

    // ------------------------------------------------------------------------------------------------ text

    fun loadLabel(card: Card): String = if (card.points.isEmpty() && card.mode != LoadMode.BODYWEIGHT) "No loads known" else Numbers.loadMain(card.point, card.mode)

    fun loadSecondary(card: Card): String? =
        Numbers.loadSecondary(card.point, card.mode, card.item.unilateral, _ui.value.session?.bodyweight_kg?.let { Scaled.toDouble(it, 2) })

    private fun nextLine(card: Card): String {
        // No load point (an equipment-free hold) shows the hold alone, not a dash.
        val load = when {
            card.mode == LoadMode.BODYWEIGHT -> "BW"
            card.point == null -> null
            else -> Numbers.loadMain(card.point, card.mode)
        }
        val perf = if (card.isHold) (card.holdTargetS?.let { Numbers.num(it, 1) + " s" } ?: "hold") else "× ${card.reps ?: 0}"
        val side = when (card.side) { "L" -> "Left · "; "R" -> "Right · "; else -> "" }
        return side + listOfNotNull(load, perf).joinToString(" ")
    }

    private fun nextTitle(card: Card): String {
        val count = if (card.warmup) "warm-up" else "${card.setNumber}/${maxOf(card.item.planned, card.setNumber)}"
        return "${card.item.ex.nameEn} · $count"
    }

    private fun updateNotification(s: WorkoutSessionEntity?, card: Card?) {
        val title = _ui.value.title ?: "Free session"
        val current = card?.let { "Set ${it.setNumber} of ${maxOf(it.item.planned, it.setNumber)} · ${it.item.ex.nameEn}" } ?: "Add an exercise"
        c.notifications.text = SessionNotificationText(
            sessionTitle = title, currentLine = current,
            nextTitle = card?.let { "${it.item.ex.nameEn} ${it.setNumber}/${maxOf(it.item.planned, it.setNumber)}" } ?: "Next set",
            nextLoad = card?.let(::nextLine) ?: "", sessionStartWall = s?.started_at ?: 0L,
        )
        // The controller picks countdown, overtime or count-up from Room; the UI's rest state can lag a logged set.
        if (s?.status == "in_progress") viewModelScope.launch { c.restTimer.refreshSessionNotification(sessionId) }
    }

    companion object {
        const val DEFAULT_REPS = 8

        /** The most fingers of assistance (`assisted_ordinal`): the other hand's four fingers. */
        private const val MAX_FINGERS = 4

        /** See [completesPlannedWork]; [setNumber] is the working set just logged on item [seId]. */
        fun completesPlannedWork(items: List<ExerciseItem>, seId: String, setNumber: Int, planned: Int, warmup: Boolean, side: String): Boolean {
            if (warmup || side == "L") return false
            if (setNumber < planned) return false
            return items.none { it.se.id != seId && it.open && it.workingDone < it.planned }
        }

        /**
         * The last set of the challenge warm-up block (WARMUP-1, UX 19.8): [setNumber] finishes warm-up item [seId], no other
         * warm-up item has sets left and a working item does, so the rest is `warmup.rest_to_work_s`.
         */
        fun warmupLeadsToWork(items: List<ExerciseItem>, seId: String, setNumber: Int, planned: Int, warmupItem: Boolean): Boolean {
            if (!warmupItem || setNumber < planned) return false
            val left = items.filter { it.se.id != seId && it.open && it.workingDone < it.planned }
            return left.none { it.warmupItem } && left.isNotEmpty()
        }
    }
}
