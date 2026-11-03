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
import dev.fitnessapp.algo.selection.substitute.SubSessionItem
import dev.fitnessapp.algo.selection.substitute.Substitute
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.planning.SessionBuild
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.notify.SessionNotificationText
import dev.fitnessapp.app.session.SessionService
import dev.fitnessapp.algo.prescription.autoreg.SuggestionKind
import dev.fitnessapp.algo.prescription.warnings.Warning
import dev.fitnessapp.app.format.WarnTexts
import dev.fitnessapp.app.ui.common.WhyContent
import dev.fitnessapp.app.uidet.Dismissal
import dev.fitnessapp.app.uidet.UiWarnDisplay
import dev.fitnessapp.app.uidet.WarnDisplayResult
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
import dev.fitnessapp.data.engine.Prescription
import dev.fitnessapp.data.engine.RxSlot
import dev.fitnessapp.data.engine.RxTarget
import dev.fitnessapp.data.engine.Suggestion
import dev.fitnessapp.data.engine.WarmupTarget
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One exercise of the session with its catalog row and today's sets. */
data class ExerciseItem(
    val se: SessionExerciseEntity,
    val ex: CatalogExercise,
    val sets: List<SetLogEntity>,
    /** A challenge warm-up item (the row, face pulls, ...): its warm-up sets are its work. */
    val warmupItem: Boolean = false,
    /** The engine prescription pinned on the item (RX-1, M3.4); null for items RX-1 does not prescribe. */
    val rx: Prescription? = null,
) {
    val working: List<SetLogEntity> get() = if (warmupItem) sets else sets.filter { it.set_kind != "warmup" }
    val unilateral: Boolean get() = se.side_mode != "bilateral"
    /** Working sets done; an L+R pair counts as one (C7), and the attempts of a round count as one set. */
    val workingDone: Int get() = working.distinctBy { it.set_index to it.side }.sumOf { if (it.side == "both") 2 else 1 } / 2
    val planned: Int get() = se.planned_sets ?: 0
    val open: Boolean get() = se.status in setOf("pending", "active", "deferred")

    /** WARMUP-1's generated warm-up sets still to do: only before the first working set (UX 11.5). */
    val generatedWarmupsLeft: Int
        get() = if (warmupItem || rx == null || working.isNotEmpty()) 0 else maxOf(0, rx.warmup.size - sets.count { it.set_kind == "warmup" })
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
    /** The engine's planned set for this slot (RX-1) and the generated warm-up set (WARMUP-1), when there is one. */
    val rxTarget: RxTarget? = null,
    val warmupTarget: WarmupTarget? = null,
    /** The working set number on this side (1-based), the index RX-1 and "last time" use. */
    val workIndex: Int = 1,
    /** A calibration probe without a load (B9.8): the reps "a weight you could lift about N times" refers to. */
    val probePickReps: Int? = null,
    /** Today's explicit RIR check (F14, B4.3): the chip is the predicted reserve, then extra reps to failure. */
    val calibration: Boolean = false,
    /** The rest the target was refitted for (B13.7, D69), in seconds; null while the target is the plan's. */
    val restFitS: Int? = null,
    val calibRir: Int? = null,
    val calibExtra: Int = 0,
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

/** An AUTO-1 card (UX 11.10): the proposal for the remaining sets of one exercise and side, never applied by itself. */
data class SuggestionUi(
    val seId: String,
    val side: String,
    val recommendationId: String,
    val kind: SuggestionKind,
    val stopReason: String?,
    val title: String,
    val reason: String,
    val sets: List<RxTarget>,
    val shownAt: Long,
    val why: WhyContent,
)

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
    /** UX 11.10: an engine suggestion waiting for Apply or Keep, and the "applied · Undo" line after Apply. */
    val suggestion: SuggestionUi? = null,
    val applied: String? = null,
    /** UX 17: what UI-10 shows of the session's warnings. */
    val warn: WarnDisplayResult? = null,
    /** "Session C" for a challenge session (the session notification and the header). */
    val title: String? = null,
    /** The last swap, for its Undo bar (P6). */
    val swapUndo: SwapUndo? = null,
    /** The add-on block of a challenge session (D62): its header, family and Skip. */
    val addOn: AddOnUi? = null,
    /** C63's overrun line: the session runs more than `session.overrunLineMin` over its budget. */
    val overrun: OverrunUi? = null,
)

/**
 * The add-on header (UX 11.4, D62): [family] the type its exercises train (null when none are left), [choices] the
 * families the slot can serve today, [estMin] TIME-1's minutes of its open sets, [firstItemId] the row the header sits on.
 */
data class AddOnUi(
    val blockId: String,
    val status: String,
    val family: SessionType?,
    val choices: List<SessionType>,
    val estMin: Int,
    val firstItemId: String?,
    val working: Boolean,
)

data class OverrunUi(val overMin: Int, val budgetMin: Int)

data class SwapUndo(val newId: String, val eventId: String, val label: String, val at: Long)

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

    /** Decoded prescriptions by recommendation id, and the items whose prescription was already requested. */
    private val rxCache = java.util.concurrent.ConcurrentHashMap<String, Prescription>()
    private val rxRequested: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * The warm-up switch of an item as the user set it (UX 11.5); absent = the default (generated warm-ups first).
     * Restored from the session's `warmup_switched` events before the first card (D81).
     */
    private val warmupOverride = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Applied suggestions (UX 11.10): "seId|side" -> set index -> the accepted target, UI-04's first prefill source. */
    private val accepted = java.util.concurrent.ConcurrentHashMap<String, Map<Int, RxTarget>>()
    @Volatile private var lastApplied: String? = null

    /** B13.7 (D69): the next set's target refitted to its last set and the rest so far, by "seId|side|workIndex". */
    private val restFit = java.util.concurrent.ConcurrentHashMap<String, Pair<RxTarget, Int>>()

    /** AUTO-1's last verdict per session exercise; WE-S5 reads its next-set prediction. */
    private val verdicts = java.util.concurrent.ConcurrentHashMap<String, Suggestion>()

    /** UI-10's dismissal store and "use mine" flags (from the event log), the SAFETY notices acknowledged today. */
    @Volatile private var dismissals: Map<String, Dismissal> = emptyMap()
    @Volatile private var useMine: Set<String> = emptySet()
    private val acknowledged: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    private val warningsShown: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    @Volatile private var warnings: List<Warning> = emptyList()
    @Volatile private var constraintTexts: Map<String, String> = emptyMap()

    init {
        viewModelScope.launch {
            loadWarningStore()
            refreshWarnings()
        }
        viewModelScope.launch {
            improveGroups = runCatching { c.goals.current().filterValues { it.goal == "improve" }.keys }.getOrDefault(emptySet())
        }
        viewModelScope.launch {
            restoreWarmupSwitches()
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
            while (true) {
                kotlinx.coroutines.delay(REST_FIT_TICK_MS)
                runCatching { refitForRest() }
            }
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

    /**
     * RX-1's slot for an item (D49): a general exercise, or a challenge item with `progression: delegate:rx` (the B row,
     * the dips) whose scheme, rest and deload come from the plan. Other challenge items keep their RULE rows.
     */
    private fun rxSlot(item: ExerciseItem): RxSlot? {
        val key = item.se.challenge_item_key ?: return RxSlot(item.se.id)
        val live = challenge ?: return null
        val p = live.item(key)?.plan ?: return null
        if (p.progressionMode != "delegate:rx") return null
        val row = p.rows.firstOrNull { it.setKind != "warmup" } ?: return null
        val lo = row.targetReps ?: return null
        return RxSlot(
            item.se.id, dev.fitnessapp.algo.prescription.rx.ItemScheme(lo, row.targetRepsMax ?: lo, p.rirTarget?.toDouble(), null),
            deload = live.plan.mode == "deload", restS = row.restS?.toDouble(),
        )
    }

    /** Asks the runner once per item for the prescriptions still missing; Room's update rebuilds the cards. */
    private fun requestPrescriptions(items: List<ExerciseItem>) {
        for (item in items) {
            if (!item.open || item.se.recommendation_id != null || !c.prescriptions.eligible(item.ex)) continue
            val slot = rxSlot(item) ?: continue
            if (!rxRequested.add(item.se.id)) continue
            viewModelScope.launch { runCatching { c.prescriptions.ensure(slot) } }
        }
    }

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
        val items = snap.exs.filter { it.status != "removed" }.mapNotNull { e ->
            c.catalog.byId(e.exercise_id)?.let { ex ->
                ExerciseItem(
                    e, ex, snap.sets.filter { it.session_exercise_id == e.id }.sortedBy { it.completed_at },
                    warmupItem = challenge?.item(e.challenge_item_key)?.plan?.role == "warmup",
                    rx = e.recommendation_id?.let { id -> rxCache[id] ?: runCatching { c.prescriptions.get(id) }.getOrNull()?.also { rxCache[id] = it } },
                )
            }
        }
        if (snap.s?.status == "in_progress") requestPrescriptions(items)
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
        val addOn = runCatching { addOnState(snap, items) }.getOrNull()
        val overrun = overrunState(snap.s, items)
        _ui.update {
            it.copy(
                session = snap.s, items = items, focusedId = focus, card = card, rest = activeRest,
                restNext = card?.let(::nextLine), restNextTitle = card?.let(::nextTitle), pendingCount = snap.pending,
                addOn = addOn, overrun = overrun,
            )
        }
        updateNotification(snap.s, card)
    }

    /** Keeps the draft while the slot is the same; otherwise builds a fresh prefilled card. */
    private suspend fun buildCard(item: ExerciseItem, allSets: List<SetLogEntity>, old: Card?): Card {
        val mode = LoadMode.of(item.se.load_mode)
        val warmup = warmupOverride[item.se.id] ?: (planItem(item)?.plan?.role == "warmup" || item.generatedWarmupsLeft > 0)
        val (setIndex, side) = nextSlot(item, warmup)
        val fresh = buildFresh(item, mode, setIndex, side, warmup, allSets)
        // An untouched card takes a target that arrived after it was shown (the prescription or the probe's follow-up).
        val keep = old != null && old.slotKey == fresh.slotKey && !equipmentChanged &&
            (old.taps > 0 || (old.target == fresh.target && old.rxTarget == fresh.rxTarget && old.warmupTarget == fresh.warmupTarget))
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
        // The working set number on this side: RX-1's set index and "last time"'s renumbered index (warm-ups excluded).
        val workIndex = item.working.count { it.side == side } + 1
        val adjusted = if (warmup) null else accepted["${item.se.id}|$side"]?.get(workIndex)
        val planTarget = if (warmup) null else adjusted ?: item.rx?.target(workIndex, side)
        // B13.7 (D69): the reps refitted to the last set and the rest so far, at the same load.
        val fit = planTarget?.let { t -> restFit["${item.se.id}|$side|$workIndex"]?.takeIf { it.first.wSys == t.wSys } }
        val rxTarget = fit?.let { (f, _) -> planTarget.copy(targetReps = f.targetReps, rirPred = f.rirPred) } ?: planTarget
        val warmupTarget = if (warmup && warmupOverride[item.se.id] == null) {
            item.rx?.warmup?.getOrNull(item.sets.count { it.set_kind == "warmup" && it.side == side })
        } else null
        val engine = (rxTarget?.let { t -> SetValues(mode, t.wSys?.let { w -> nativeAt(points, mode, w) }, t.targetReps, null) }
            ?: warmupTarget?.let { t -> SetValues(mode, nativeAt(points, mode, t.wSys), t.reps, null) })
        val pf = UiPrefill.prefill(
            SetSlot(if (warmup) setIndex else workIndex, side, mode),
            PrefillContext(
                acceptedAdjustment = (if (fit != null) rxTarget else adjusted)?.let { t -> SetValues(mode, t.wSys?.let { w -> nativeAt(points, mode, w) }, t.targetReps, null) },
                target = engine,
                history = if (warmup) emptyList() else history, todayPrevious = todayPrev, default = default,
                // Right side reps never prefill above the left side's (UX 11.7.2); the program rule arrives with M2.
                sideRule = side == "R", leftLoggedReps = leftReps,
            ),
        ).value
        // A challenge item's targets come from its plan (CHALLENGE_ENGINE 6.1), as in-session rules left them; a
        // `delegate:rx` item's come from RX-1 once its prescription exists.
        val row = plannedRow(item, setIndex, side, warmup)
        val planned = row?.takeIf { rxTarget == null }?.let { r ->
            val native = when (mode) {
                LoadMode.ASSISTED_MACHINE -> r.pin?.toDouble()
                LoadMode.BODYWEIGHT_PLUS_EXTERNAL, LoadMode.EXTERNAL -> r.addedKg?.toDouble()?.let { kg -> UiPrefill.nativeForAddedKg(kg, points, params) ?: kg }
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
            rxTarget = rxTarget, warmupTarget = warmupTarget, workIndex = workIndex,
            calibration = !warmup && side == "both" && item.rx?.calibrationSet == workIndex,
            restFitS = fit?.second,
            probePickReps = rxTarget?.takeIf { item.rx?.probe == true && workIndex == 1 && it.wSys == null }?.let { t ->
                (t.targetReps ?: 0) + Math.round(t.rir ?: 0.0).toInt()
            },
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

    /** The native value of the loadable point whose system load is [wSys] (RX-1 and WARMUP-1 plan in `w_sys`). */
    private fun nativeAt(points: List<LoadPoint>, mode: LoadMode, wSys: Double): Double? {
        if (mode == LoadMode.BODYWEIGHT) return null
        return points.filter { it.wSys != null }.minByOrNull { kotlin.math.abs(it.wSys!! - wSys) }
            ?.takeIf { kotlin.math.abs(it.wSys!! - wSys) < LOAD_EPS_KG }?.let { nativeOf(it, mode) }
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

    private fun emit(
        type: String, sessionExerciseId: String?, payload: Map<String, JsonElement>, entityType: String = "session_exercise",
        recommendationId: String? = null,
    ) {
        viewModelScope.launch {
            c.sessionRepository.event(
                dev.fitnessapp.data.telemetry.EventDraft(
                    type = type, sessionId = sessionId, entityType = if (sessionExerciseId == null) "none" else entityType,
                    entityId = sessionExerciseId, screen = "session.live", payload = payload, recommendationId = recommendationId,
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
        warmupOverride[card.item.se.id] = on
        // D81: the event keeps the choice for the session; restoreWarmupSwitches reads it back.
        emit("warmup_switched", card.item.se.id, mapOf("on" to JsonPrimitive(on)))
        viewModelScope.launch {
            val s = last ?: return@launch
            val (index, side) = nextSlot(card.item, on)
            val fresh = buildFresh(card.item, card.mode, index, side, on, s.sets)
            _ui.update { it.copy(card = fresh) }
        }
    }

    /** D81: the last `warmup_switched` of each session exercise, so a new screen keeps the user's choice. */
    private suspend fun restoreWarmupSwitches() {
        val rows = runCatching { c.sessionRepository.sessionEventsOfType(sessionId, "warmup_switched") }.getOrDefault(emptyList())
        for (r in rows) {
            val seId = r.entity_id ?: continue
            val on = runCatching { Json.parseToJsonElement(r.payload).jsonObject["on"]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: continue
            warmupOverride[seId] = on
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
            // UX 11.10: logging the next set of the exercise without Apply or Keep is a Keep.
            _ui.value.suggestion?.takeIf { it.seId == card.item.se.id && !card.warmup }?.let { open ->
                emit("suggestion_kept", open.seId, mapOf("implicit" to JsonPrimitive(true)), recommendationId = open.recommendationId)
                _ui.update { it.copy(suggestion = null) }
            }
            val check = card.calibration && card.calibRir != null
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
                setIndex = card.setIndex, side = card.side, setKind = when { card.warmup -> "warmup"; check -> "calibration"; else -> "working" },
                load = if (ordinal) null else p, reps = if (card.isHold) null else card.reps, durationS = if (card.isHold) card.holdS else null,
                rir = rir?.toDouble(), rirLowerBound = rir == 5, holdReserveS = feltReserveS, holdReserveLowerBound = feltLowerBound,
                flags = flags.toList(), deviationReason = card.reason, countsAsWork = card.item.warmupItem,
                planned = PlannedValues(
                    reps = card.target.reps, inputValue = p?.takeIf { card.mode != LoadMode.ASSISTED_MACHINE && !ordinal }?.let { card.target.native },
                    inputUnit = p?.takeIf { !ordinal }?.nativeUnit, assistNative = if (card.mode == LoadMode.ASSISTED_MACHINE) card.target.native else null,
                    durationS = card.holdTargetS, eccS = card.eccTargetS, assistOrdinal = card.plannedFingers,
                    effectiveKg = card.rxTarget?.wSys, rir = card.rxTarget?.rir,
                    repMin = card.rxTarget?.let { card.item.rx?.rLo }, repMax = card.rxTarget?.let { card.item.rx?.rHi },
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
                calibExtraReps = if (check) card.calibExtra else null,
            )
            // A round logs one row per attempt with the round's effort chip (UX 11.7.4); the rest belongs to the first.
            val cmds = card.attemptsS?.mapIndexed { i, s ->
                cmd.copy(durationS = s, roundIndex = card.setIndex, attemptIndex = i + 1, rest = if (i == 0) rest else null)
            } ?: listOf(cmd)
            val ids = c.sessionRepository.logSets(cmds)
            val setId = ids.first()
            historyCache.remove(card.item.se.exercise_id)
            // B9.8: once the probe is logged on every side, RX-1 plans the remaining sets from it.
            if (!card.warmup && card.workIndex == 1 && card.item.rx?.probe == true && card.side != "L") {
                rxSlot(card.item)?.let { slot -> runCatching { c.prescriptions.followUp(slot) } }
            }
            if (card.item.se.challenge_item_key != null && challenge != null) applyChallengeRules(card.item.se.id)
            if (!card.warmup && card.item.rx != null && card.side != "L") autoreg(card)
            refreshWarnings()
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

    /** REST-1 base policy (D21) with the user's rest preference (D50); a left side switches immediately (between-sides 0 s, UX 11.7.2). */
    private suspend fun startRest(card: Card, setId: String, loggedAt: Long) {
        val p = params
        if (completesPlannedWork(card)) return
        if (card.side == "L") {
            _ui.update { it.copy(switchHint = true) }
            kotlinx.coroutines.delay(c.platformConfig.timerSwitchHintMs)
            _ui.update { it.copy(switchHint = false) }
            return
        }
        val generatedRest = card.warmupTarget?.restS
        val decision = if (card.warmup && generatedRest != null) {
            RestPolicy.decide(RestInput(RestClass.of(card.item.ex.restClass), prevWasWarmup = true, warmupRestS = generatedRest), p)
        } else if (card.warmup) {
            val toWork = warmupLeadsToWork(_ui.value.items, card.item.se.id, card.setNumber, card.item.planned, card.item.warmupItem)
            RestPolicy.decide(RestInput(RestClass.of(card.item.ex.restClass), prevWasWarmup = true, warmupRestS = WarmupRest.afterWarmup(null, toWork, p)), p)
        } else {
            // The user's rest preference for an exercise outside a challenge (D50); challenge rows keep their own rest.
            val pref = if (card.item.se.challenge_item_key == null) runCatching { c.prescriptions.userRest(card.item.ex, card.item.se.exercise_id) }.getOrNull() else null
            RestPolicy.decide(
                RestInput(RestClass.of(card.item.ex.restClass), targetReps = card.target.reps, userRestPrefS = pref?.restPrefS, userRestMult = pref?.restPrefMult), p,
            )
        }
        // A challenge row prescribes its own rest (`rest_s`, CHALLENGE_ENGINE 6.1); REST-1 keeps the hard maximum.
        val planned = plannedRow(card.item, card.setIndex, card.side, card.warmup)?.restS
        c.restTimer.startAfter(
            sessionId, setId, card.item.se.exercise_id, card.side, loggedAt, planned ?: decision.timerS.toInt(), decision.hardMaxS.toInt(),
            "rest", if (planned != null) "challenge" else "rest_module",
        )
    }

    /**
     * B13.7 (D69): while the next working set of a general exercise waits untouched, its reps follow the last set's
     * result and the rest taken so far (same load, inside the range). The card is rebuilt only when the target moves.
     */
    private suspend fun refitForRest() {
        val card = _ui.value.card ?: return
        val item = card.item
        if (card.warmup || card.taps > 0 || card.workIndex < 2 || item.rx == null || item.se.challenge_item_key != null) return
        val lastSet = item.working.filter { it.side == card.side }.maxByOrNull { it.completed_at } ?: return
        val restS = ((System.currentTimeMillis() - lastSet.completed_at) / 1000L).toInt()
        val slot = rxSlot(item) ?: return
        val key = "${item.se.id}|${card.side}|${card.workIndex}"
        val t = c.prescriptions.restTarget(slot, card.side, restS.toDouble())
        val before = restFit[key]?.first?.targetReps
        if (t == null) restFit.remove(key) else restFit[key] = RxTarget(t.setIndex, t.side, t.targetReps, t.rir, t.wSys, t.rirPred, t.restS) to restS
        if (t?.targetReps != before) last?.let { rebuild(it) }
    }

    /** AUTO-1 after a working set (B13, UI-06): both sides after an L+R pair; the first card that fires is shown. */
    private suspend fun autoreg(card: Card) {
        val slot = rxSlot(card.item) ?: return
        val sides = if (card.side == "both") listOf("both") else listOf("L", "R")
        for (side in sides) {
            val v = runCatching { c.prescriptions.suggest(slot, side, feelingGood = card.reason == "feeling_good") }.getOrNull() ?: continue
            verdicts[card.item.se.id] = v
            val id = v.recommendationId
            if (v.result.card && id != null && _ui.value.suggestion == null) showSuggestion(card, v, id)
        }
    }

    private suspend fun showSuggestion(card: Card, v: Suggestion, id: String) {
        val r = v.result
        val item = card.item
        val mode = card.mode
        val sets = r.proposed.map { RxTarget(it.setIndex, it.side, it.targetReps, it.rir, it.wSys, it.rirPred, it.restS) }
        val load = r.wNew?.let { w -> card.points.firstOrNull { p -> p.wSys != null && kotlin.math.abs(p.wSys!! - w) < LOAD_EPS_KG }?.let { Numbers.loadMain(it, mode) } ?: (Numbers.num(w, 2) + " kg") }
        val title = when (r.kind) {
            SuggestionKind.STOP -> "End ${item.ex.nameEn} here"
            else -> "Next sets: " + (load?.let { "$it × " } ?: "") + sets.joinToString(" ") { it.targetReps?.toString() ?: "?" }
        }
        val d = r.deltas.lastOrNull()
        val reason = when {
            r.kind == SuggestionKind.STOP -> when (r.stopReason) {
                "fatigue" -> "Fatigue: no load near the plan still reaches the rep range."
                "short_at_failure" -> "Two sets short of the range at 0 in reserve."
                else -> "Technique broke down twice."
            }
            r.trigger == "reps_short" -> "${card.reps ?: 0} reps, short of the target."
            r.trigger == "reason" -> if (card.reason == "too_easy") "You marked the set too easy." else "You marked the set too hard."
            d != null && d > 0 -> "About ${Numbers.num(d, 1)} more in reserve than predicted."
            d != null -> "About ${Numbers.num(-d, 1)} less in reserve than predicted."
            else -> "Today differs from the plan."
        }
        val why = WhyContent(
            title = "Why this suggestion?", summary = reason,
            numbers = buildList {
                add("Readiness today  " + Numbers.num(r.deltaEx * 100, 1) + " %")
                r.rirNextCur?.let { add("Next set at the current load  " + Numbers.num(it, 1) + " in reserve") }
                r.rirNextNew?.let { add("Next set as suggested  " + Numbers.num(it, 1) + " in reserve") }
                r.hysteresis?.let { add("Aim  " + Numbers.num(it.qStar, 1) + " in reserve") }
            },
            module = "autoreg@${dev.fitnessapp.algo.prescription.autoreg.Autoreg.ref.version} · keep is the default",
            evidence = "moderate (RIR-based autoregulation: Helms 2018, Zhang 2021); clamps and hysteresis expert-opinion",
            trace = dev.fitnessapp.data.db.JsonText.canonical(dev.fitnessapp.algo.prescription.autoreg.AutoregJson.result(r)),
            recommendationId = id, codes = r.trace,
        )
        val ui = SuggestionUi(item.se.id, v.side, id, r.kind!!, r.stopReason, title, reason, sets, System.currentTimeMillis(), why)
        emit(
            "suggestion_shown", item.se.id,
            mapOf(
                "trigger" to JsonPrimitive(r.trigger ?: r.stopReason ?: "stop"),
                "deltas" to JsonArray(r.deltas.map { x -> x?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull }),
                "proposed" to dev.fitnessapp.algo.prescription.autoreg.AutoregJson.result(r).getValue("proposed"),
                "recommendation_id" to JsonPrimitive(id),
            ),
            recommendationId = id,
        )
        _ui.update { it.copy(suggestion = ui) }
    }

    /** Apply (UX 11.10): the proposal becomes the accepted adjustment of the remaining sets; a stop ends the exercise. */
    fun applySuggestion() {
        val sg = _ui.value.suggestion ?: return
        emit(
            "suggestion_applied", sg.seId,
            mapOf("recommendation_id" to JsonPrimitive(sg.recommendationId), "latency_ms" to JsonPrimitive(System.currentTimeMillis() - sg.shownAt)),
            recommendationId = sg.recommendationId,
        )
        if (sg.kind == SuggestionKind.STOP) {
            _ui.update { it.copy(suggestion = null) }
            endExercise(sg.seId, if (sg.stopReason == "technique") "technique_breakdown" else "too_hard")
            return
        }
        val key = "${sg.seId}|${sg.side}"
        accepted[key] = sg.sets.associateBy { it.setIndex }
        lastApplied = key
        _ui.update { it.copy(suggestion = null, applied = sg.title) }
        viewModelScope.launch { last?.let { rebuild(it) } }
    }

    fun keepSuggestion() {
        val sg = _ui.value.suggestion ?: return
        emit("suggestion_kept", sg.seId, mapOf("implicit" to JsonPrimitive(false)), recommendationId = sg.recommendationId)
        _ui.update { it.copy(suggestion = null) }
    }

    /** Undo of an applied suggestion: the remaining sets go back to the prescription. */
    fun undoApplied() {
        lastApplied?.let { accepted.remove(it) }
        lastApplied = null
        _ui.update { it.copy(applied = null) }
        viewModelScope.launch { last?.let { rebuild(it) } }
    }

    fun dismissApplied() = _ui.update { it.copy(applied = null) }

    // ------------------------------------------------------------------------------------------------ warnings (UX 17)

    private suspend fun loadWarningStore() {
        val rows = runCatching { c.sessionRepository.eventsOfTypes(listOf("warning_dismissed", "warning_use_mine_toggled")) }.getOrDefault(emptyList())
        val d = HashMap<String, Dismissal>()
        val acks = ArrayList<Pair<String, java.time.Instant>>()
        val mine = HashSet<String>()
        for (r in rows) {
            val o = runCatching { Json.parseToJsonElement(r.payload).jsonObject }.getOrNull() ?: continue
            val rule = o["rule_id"]?.jsonPrimitive?.contentOrNull ?: continue
            val entity = o["entity"]?.jsonPrimitive?.contentOrNull ?: continue
            val key = UiWarnDisplay.key(rule, entity)
            when (r.event_type) {
                "warning_dismissed" -> {
                    d[key] = Dismissal(java.time.Instant.ofEpochMilli(r.occurred_at), o["magnitude"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
                    acks += key to java.time.Instant.ofEpochMilli(r.occurred_at)
                }
                else -> if (o["on"]?.jsonPrimitive?.booleanOrNull == true) mine += key else mine -= key
            }
        }
        dismissals = d
        useMine = mine
        acknowledged += UiWarnDisplay.acknowledgedToday(acks, c.clock.now(), dev.fitnessapp.app.planning.PlanningFacts.ZONE)
    }

    private fun refreshWarnings() = viewModelScope.launch {
        val session = _ui.value.session ?: c.db.sessions().get(sessionId)
        val checked = session?.let { s -> runCatching { ChallengeConstraints.warnings(c, s, _ui.value.items) }.getOrNull() }
        constraintTexts = checked?.texts.orEmpty()
        val ws = dev.fitnessapp.algo.prescription.warnings.Warnings.rank(
            runCatching { c.warnings.session(sessionId, verdicts.toMap()) }.getOrDefault(emptyList()) + checked?.warnings.orEmpty(),
        )
        warnings = ws
        val shown = UiWarnDisplay.display(ws, dismissals, useMine, acknowledged.toSet(), java.time.Instant.now(), params)
        (shown.safety + listOfNotNull(shown.banner)).forEach { w ->
            if (warningsShown.add(UiWarnDisplay.key(w.ruleId, w.entity))) {
                emit(
                    "warning_shown", seIdOf(w.entity),
                    mapOf(
                        "rule_id" to JsonPrimitive(w.ruleId), "severity" to JsonPrimitive(w.severity.code), "entity" to JsonPrimitive(w.entity),
                        "magnitude" to JsonPrimitive(w.magnitude), "surface" to JsonPrimitive("session.live"),
                    ),
                )
            }
        }
        _ui.update { it.copy(warn = shown) }
    }

    private fun seIdOf(entity: String): String? = entity.removePrefix("session_exercise:").takeIf { entity.startsWith("session_exercise:") }

    /** The copy of [w]: a challenge constraint's own text (its window and what it keeps out), else WarnTexts'. */
    fun warningText(w: Warning): String = constraintTexts[ChallengeConstraints.textKey(w)] ?: dev.fitnessapp.app.format.WarnTexts.text(w, warningName(w))

    /** The name the copy uses for a warning's entity. */
    fun warningName(w: Warning): String = seIdOf(w.entity)?.let { id -> _ui.value.items.firstOrNull { it.se.id == id }?.ex?.nameEn }
        ?: w.entity.substringAfter(':').let { id -> c.catalog.byId(id)?.nameEn ?: id.replace('_', ' ') }

    /** SAFETY notices are acknowledged, advisory ones dismissed (snoozed, UX 19.5); both are `warning_dismissed`. */
    fun dismissWarning(w: Warning) {
        val key = UiWarnDisplay.key(w.ruleId, w.entity)
        if (w.severity == dev.fitnessapp.algo.prescription.warnings.Severity.SAFETY) acknowledged += key
        else dismissals = dismissals + (key to Dismissal(java.time.Instant.now(), w.magnitude))
        emit(
            "warning_dismissed", seIdOf(w.entity),
            mapOf("rule_id" to JsonPrimitive(w.ruleId), "entity" to JsonPrimitive(w.entity), "magnitude" to JsonPrimitive(w.magnitude)),
        )
        refreshWarnings()
    }

    fun warningWhy(w: Warning): WhyContent {
        emit("warning_why_opened", seIdOf(w.entity), mapOf("rule_id" to JsonPrimitive(w.ruleId), "entity" to JsonPrimitive(w.entity)))
        return WarnTexts.why(w, warningName(w))
    }

    // ------------------------------------------------------------------------------------------------ RIR check (F14)

    /** The chip on a check set is the predicted reserve (`q_pred`); the set is logged after the extra reps. */
    fun calibChip(rir: Int) = _ui.update { u -> u.copy(card = u.card?.copy(calibRir = rir)) }

    fun stepCalibExtra(up: Boolean) = _ui.update { u -> u.copy(card = u.card?.copy(calibExtra = ((u.card.calibExtra) + if (up) 1 else -1).coerceIn(0, MAX_CALIB_EXTRA))) }

    fun logCalibration() {
        val rir = _ui.value.card?.calibRir ?: return
        log(rir = rir, control = "calibration")
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
        // A capTarget (the right side matches the left) has no message of its own; the banner states its result.
        val caps = apps.filter { it.kind == "capTarget" && it.to != null }.map { a ->
            val side = when (a.rowsBefore.firstOrNull()?.side) {
                "L" -> "Left side"
                "R" -> "Right side"
                else -> "Next set"
            }
            "$side reduced to ${a.to} reps."
        }
        val notices = apps.filter { it.kind == "notify" }.mapNotNull { text(it.msg, it.msgParams) } + caps
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
        // The focus stays on the current exercise; the new one takes it only once nothing before it is open.
        c.sessionRepository.addExercise(sessionId, ex)
        // WE-S4 reads the new exercise once Room has it in the items.
        kotlinx.coroutines.delay(WARN_AFTER_ADD_MS)
        refreshWarnings()
    }

    fun setLoadMode(mode: LoadMode) {
        val card = _ui.value.card ?: return
        viewModelScope.launch {
            c.sessionRepository.setLoadMode(card.item.se.id, mode)
            rxRequested.remove(card.item.se.id) // the row may have lost its prescription: RX-1 plans the new mode (D80)
        }
    }

    /** "Stack setup" (D43): saves this machine's stack, then rebuilds the card on it (`gym_equipment` is not observed). */
    fun saveStack(unit: String, start: Double, step: Double, max: Double) {
        val rowId = _ui.value.card?.stackRowId ?: return
        viewModelScope.launch {
            c.equipment.saveStack(rowId, unit, start, step, max)
            rxRequested.removeAll(c.sessionRepository.replanOnEquipment(sessionId, rowId).toSet())
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

    /** Swap is offered for general exercises before their first working set (challenge items are pinned). */
    fun canSwap(seId: String): Boolean = _ui.value.items.firstOrNull { it.se.id == seId }?.let { it.se.challenge_item_key == null && it.workingDone == 0 } == true

    /** From `substitute.max_defers` defers on, or with nothing to move behind, Swap is the primary choice (UX 11.12). */
    fun swapPrimary(seId: String): Boolean {
        val item = _ui.value.items.firstOrNull { it.se.id == seId } ?: return false
        return item.se.defer_count >= params.int("substitute.max_defers") || !canDoLater(seId)
    }

    // ------------------------------------------------------------------------------------------ add-on and time (D62)

    /** The add-on block's header state; the first time the challenge block is done it is rebuilt from the live capacity. */
    private suspend fun addOnState(snap: Snapshot, items: List<ExerciseItem>): AddOnUi? {
        val s = snap.s ?: return null
        val b = c.db.blocks().bySession(sessionId).firstOrNull { it.origin == "addon" } ?: return null
        if (addOnChoices == null && !addOnChoicesLoading && s.status == "in_progress") {
            addOnChoicesLoading = true
            viewModelScope.launch {
                addOnChoices = runCatching {
                    val f = SessionBuild.facts(c, c.clock.now(), s.challenge_slot)
                    SessionBuild.addOnChoices(c, f, (b.budget_min ?: 0).toDouble()).map { it.first }
                }.getOrDefault(emptyList())
                last?.let { rebuild(it) }
            }
        }
        val rows = items.filter { it.se.block_id == b.id }
        val family = addOnFamily(rows)
        val challengeOpen = items.any { it.se.block_id != b.id && it.se.challenge_item_key != null && it.open }
        if (!challengeOpen && b.status == "planned" && rows.none { it.sets.isNotEmpty() } && !addOnLiveDone && s.status == "in_progress" && family != null) {
            addOnLiveDone = true
            val cap = liveCapacity(s)
            if (cap != null && cap >= params.int("time.min_addon_min")) viewModelScope.launch { rebuildAddOn(family, cap, "no_time", live = true) }
        }
        return AddOnUi(
            b.id, b.status, family, addOnChoices.orEmpty(), kotlin.math.round(LiveTime.remainingMin(rows, c.catalogModel, params)).toInt(),
            rows.firstOrNull()?.se?.id, addOnWorking,
        )
    }

    /** The add-on type its exercises train: legs, push, both, or core (D62). */
    private fun addOnFamily(rows: List<ExerciseItem>): SessionType? {
        val fams = rows.filter { it.se.status != "skipped" }.flatMap { r ->
            val e = c.catalogModel.exercise(r.ex.slug) ?: return@flatMap emptyList()
            listOf("LEGS", "PUSH", "CORE").filter { f -> dev.fitnessapp.algo.common.Groups.FAMILIES.getValue(f).any { c.catalogModel.groupCoeff(e, it) >= 0.5 } }
        }.toSet()
        return when {
            "LEGS" in fams && "PUSH" in fams -> SessionType.PUSH_LEGS
            "LEGS" in fams -> SessionType.LEGS
            "PUSH" in fams -> SessionType.PUSH
            "CORE" in fams -> SessionType.CORE
            else -> null
        }
    }

    /** The live capacity (CHALLENGE_ENGINE 8.6): the session budget minus the elapsed minutes, floored; null without a budget. */
    private fun liveCapacity(s: WorkoutSessionEntity): Int? {
        val budget = s.time_budget_min ?: return null
        val started = s.started_at ?: return null
        return maxOf(0, kotlin.math.floor(budget - (System.currentTimeMillis() - started) / 60_000.0).toInt())
    }

    /** Rebuilds the add-on for [type] within [capacityMin]; [live] marks the one rebuild after the challenge block. */
    private suspend fun rebuildAddOn(type: SessionType, capacityMin: Int, reason: String, live: Boolean) {
        val s = _ui.value.session ?: last?.s ?: return
        val b = c.db.blocks().bySession(sessionId).firstOrNull { it.origin == "addon" } ?: return
        addOnWorking = true
        last?.let { rebuild(it) }
        runCatching {
            val f = SessionBuild.facts(c, c.clock.now(), s.challenge_slot)
            val req = SessionBuild.addOnRequest(c, f, type, capacityMin.toDouble()) ?: return@runCatching
            val r = SessionBuild.build(c, req)
            if (r.exercises.isEmpty()) return@runCatching
            if (c.sessionRepository.rebuildBlock(b.id, sessionId, SessionBuild.items(c, r), capacityMin, reason, if (live) "active" else "planned")) {
                c.sessionPlans.record(sessionId, b.id, r, capacityMin)
            }
        }
        addOnWorking = false
        last?.let { rebuild(it) }
    }

    /** A family chip on the add-on header (D62): the block is rebuilt for [type] within the capacity it has now. */
    fun setAddOnFamily(type: SessionType) = viewModelScope.launch {
        val s = _ui.value.session ?: return@launch
        val b = c.db.blocks().bySession(sessionId).firstOrNull { it.origin == "addon" } ?: return@launch
        val challengeOpen = _ui.value.items.any { it.se.block_id != b.id && it.se.challenge_item_key != null && it.open }
        val cap = if (challengeOpen) b.budget_min ?: return@launch else liveCapacity(s) ?: return@launch
        emit(
            "template_picked", null,
            mapOf("kind" to JsonPrimitive("addon"), "slot_or_type" to JsonPrimitive(type.code), "rank" to JsonPrimitive(addOnChoices.orEmpty().indexOf(type) + 1)),
        )
        rebuildAddOn(type, cap, "other", live = !challengeOpen)
    }

    /** Skip on the add-on header (UX 11.4): the block and its open exercises, with [reason]. */
    fun skipAddOn(reason: String) = viewModelScope.launch {
        val b = _ui.value.addOn ?: return@launch
        c.sessionRepository.skipBlock(b.blockId, sessionId, reason)
    }

    /** C63's overrun line: elapsed plus TIME-1's remaining estimate over the budget by more than the margin; logged once. */
    private fun overrunState(s: WorkoutSessionEntity?, items: List<ExerciseItem>): OverrunUi? {
        if (s == null || s.status != "in_progress") return null
        val budget = s.time_budget_min ?: return null
        val started = s.started_at ?: return null
        val elapsed = (System.currentTimeMillis() - started) / 60_000.0
        val remaining = LiveTime.remainingMin(items, c.catalogModel, params)
        val over = elapsed + remaining - budget
        if (over <= c.platformConfig.sessionOverrunLineMin) return null
        if (!overrunLogged) {
            overrunLogged = true
            viewModelScope.launch {
                if (c.db.events().bySession(sessionId).none { it.event_type == "session_overrun_shown" }) {
                    emit(
                        "session_overrun_shown", null,
                        mapOf(
                            "elapsed_min" to JsonPrimitive(kotlin.math.round(elapsed).toInt()),
                            "remaining_est_min" to JsonPrimitive(kotlin.math.round(remaining).toInt()), "budget_min" to JsonPrimitive(budget),
                        ),
                    )
                }
            }
        }
        return OverrunUi(kotlin.math.ceil(over).toInt(), budget)
    }

    /**
     * "Time budget" in the session menu (UX 11.3, D62): stores [minutes] and trims the remaining general work with UI-02
     * (challenge items are tier 0 and stay); returns the trim line, or null when nothing had to go.
     */
    suspend fun setTimeBudget(minutes: Int): String? {
        val s = _ui.value.session ?: return null
        c.sessionRepository.setTimeBudget(sessionId, minutes, "chip")
        val started = s.started_at ?: System.currentTimeMillis()
        val left = minutes - (System.currentTimeMillis() - started) / 60_000.0
        val goals = runCatching { c.goals.current() }.getOrDefault(emptyMap())
        val items = _ui.value.items.filter { it.open }
        val trim = items.mapNotNull { it ->
            val t = LiveTime.item(it, c.catalogModel) ?: return@mapNotNull null
            val e = c.catalogModel.exercise(it.ex.slug)
            val primary = e?.let { x -> dev.fitnessapp.algo.selection.elig.Elig.primaryGroup(x, c.catalogModel) }
            val tier = when {
                it.se.challenge_item_key != null || e == null || "prehab" in e.tags -> 0
                primary != null && goals[primary]?.goal == "maintain" -> 2
                e.mechanics == "compound" -> 1
                else -> 3
            }
            // Later exercises go first among equals: the engine's priority is the session order.
            dev.fitnessapp.algo.selection.time.TrimItem(t, tier, 1.0, -it.se.position.toDouble())
        }
        val r = dev.fitnessapp.algo.selection.time.TimeModel.trim(trim, dev.fitnessapp.algo.selection.time.WarmupKind.CHALLENGE, maxOf(0.0, left), 1.0, params)
        val kept = r.items.associateBy { it.time.key }
        val changes = LinkedHashMap<String, Int?>()
        val lines = ArrayList<String>()
        for (t in trim) {
            val item = items.first { it.se.id == t.time.key }
            val k = kept[t.time.key]
            when {
                k == null -> {
                    changes[item.se.id] = null
                    lines += "${item.ex.nameEn.lowercase()} left out"
                }
                k.time.sets < t.time.sets -> {
                    changes[item.se.id] = item.workingDone + k.time.sets
                    val n = t.time.sets - k.time.sets
                    lines += "$n set${if (n == 1) "" else "s"} fewer on ${item.ex.nameEn.lowercase()}"
                }
            }
        }
        if (changes.isNotEmpty()) c.sessionRepository.trimPlanned(sessionId, changes, minutes, r.coreDoesNotFit)
        emit(
            "time_budget_selected", null,
            mapOf(
                "minutes" to JsonPrimitive(minutes), "source" to JsonPrimitive("chip"), "trimmed_count" to JsonPrimitive(changes.size),
                "core_does_not_fit" to JsonPrimitive(r.coreDoesNotFit),
            ),
        )
        return when {
            r.coreDoesNotFit -> "The challenge work alone runs past $minutes min."
            lines.isEmpty() -> null
            else -> "To fit $minutes min: " + lines.joinToString("; ") + "."
        }
    }

    /** The improve groups, for ORD-1's order of the remaining exercises in SUB-1's "Do {k} now" (D62). */
    @Volatile private var improveGroups: Set<String> = emptySet()

    /** The add-on families the slot can serve (D62), loaded once; the live rebuild runs once; the overrun is logged once. */
    @Volatile private var addOnChoices: List<SessionType>? = null
    @Volatile private var addOnChoicesLoading = false
    @Volatile private var addOnLiveDone = false
    @Volatile private var addOnWorking = false
    @Volatile private var overrunLogged = false

    /**
     * SUB-1's reorder first (D62): the first remaining exercise in ORD-1 order that is not pinned and on another station,
     * when it is not simply the next one. Challenge items keep their program position as ORD-1's pinned rank.
     */
    fun doNowTarget(seId: String): ExerciseItem? {
        val items = _ui.value.items
        val stations = c.equipment.types.filter { it.category == "station" }.map { it.key }.toSet()
        val order = items.mapIndexed { i, it ->
            val pinned = it.se.challenge_item_key != null && challenge != null
            SubSessionItem(
                it.se.id, it.ex.slug, it.open, pinned, it.ex.requiresStation, c.catalogModel.exercise(it.ex.slug),
                it.se.planned_sets ?: 3, if (pinned) i else 99,
            )
        }
        val k = Substitute.doNow(order, seId, improveGroups, stations, c.catalogModel, params) ?: return null
        val i = items.indexOfFirst { it.se.id == seId }
        val next = items.drop(i + 1).firstOrNull { it.open }
        return if (next?.se?.id == k.id) null else items.firstOrNull { it.se.id == k.id }
    }

    /** "Do {k} now, come back after": moves [seId] right after k and counts as a defer. */
    fun doNow(seId: String) = viewModelScope.launch {
        val k = doNowTarget(seId) ?: return@launch
        val ids = _ui.value.items.map { it.se.id }.toMutableList()
        ids.remove(seId)
        ids.add(ids.indexOf(k.se.id) + 1, seId)
        focusOverride = null
        focusVia = "deferred_return"
        c.sessionRepository.reorder(sessionId, ids, seId, deferred = true, via = "do_later")
    }

    /** The Swap sheet's candidates (SUB-1); logs `swap_sheet_shown` with [trigger] (occupied, equipment_missing, user). */
    suspend fun swapOptions(seId: String, trigger: String): List<SwapOption> {
        val ui = _ui.value
        val item = ui.items.firstOrNull { it.se.id == seId } ?: return emptyList()
        val session = ui.session ?: return emptyList()
        val inSession = ui.items.map { it.ex.slug }.toSet()
        val options = runCatching { SwapCandidates.forSession(c, session, item.ex, inSession, occupied = trigger == "occupied") }.getOrDefault(emptyList())
        c.sessionRepository.swapSheetShown(seId, options.map { c.catalog.idOf(it.ex) to it.candidate.score }, trigger)
        return options
    }

    /** A substitute chosen from the list ([option] set) or by search; the new item takes the focus. */
    fun swap(seId: String, to: CatalogExercise, reason: String?, option: SwapOption?) = viewModelScope.launch {
        val r = c.sessionRepository.swap(seId, to, reason, option?.rank, option?.candidate?.similarity, if (option != null) "list" else "search") ?: return@launch
        focusOverride = r.first
        focusVia = "auto"
        _ui.update { it.copy(swapUndo = SwapUndo(r.first, r.second, to.nameEn, System.currentTimeMillis())) }
    }

    fun undoSwap() {
        val u = _ui.value.swapUndo ?: return
        _ui.update { it.copy(swapUndo = null) }
        c.appScope.launch { c.sessionRepository.undoSwap(u.newId, u.eventId, System.currentTimeMillis() - u.at) }
    }

    fun dismissSwapUndo() = _ui.update { it.copy(swapUndo = null) }

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
        runCatching { c.warnings.store(sessionId, c.warnings.session(sessionId, verdicts.toMap())) }
        c.restTimer.sessionFinished(sessionId)
        c.sessionRepository.finish(sessionId, endReason)
        // CHALLENGE_ENGINE 6.12: the slot's `session.completed`; the Summary reads its rule audit.
        if (challenge != null) runCatching { c.challengeSessions.complete(sessionId) }
        // D59: a due habit's exercise logged here is that habit, so Today shows it done.
        else runCatching { creditHabits() }
        stopService("finished")
        done(sessionId)
    }

    private suspend fun creditHabits() {
        val s = c.db.sessions().get(sessionId) ?: return
        val at = java.time.Instant.ofEpochMilli(s.started_at ?: System.currentTimeMillis())
        val day = dev.fitnessapp.app.ui.challenges.HabitDays.of(c, java.time.LocalDate.parse(s.local_date), at) ?: return
        c.challengeSessions.creditHabits(sessionId, day)
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

    /** The target line under the title (UX 11.6 item 2): RX-1's plan for this set, or the probe's instruction. */
    fun targetLine(card: Card): String? {
        val rx = card.item.rx ?: return planTargetLine(card)
        if (card.warmup) return null
        card.probePickReps?.let { n -> return "Pick a weight you could lift about $n times · do ${card.rxTarget?.targetReps ?: n}" }
        val t = card.rxTarget ?: return null
        if (rx.mode == "none") return "Same as last time · no estimate yet"
        val point = t.wSys?.let { w -> card.points.firstOrNull { p -> p.wSys != null && kotlin.math.abs(p.wSys!! - w) < LOAD_EPS_KG } }
        val load = when {
            card.mode == LoadMode.BODYWEIGHT -> "BW"
            point != null -> Numbers.loadMain(point, card.mode)
            else -> null
        }
        val reps = t.targetReps?.let { "× $it" }
        val aim = t.rir?.let { " · aim: ${Numbers.num(it, 1)} in reserve" } ?: ""
        val fit = card.restFitS?.let { " · for ${it / 60}:${"%02d".format(it % 60)} rest" } ?: ""
        return "Target " + listOfNotNull(load, reps).joinToString(" ") + aim + fit
    }

    /** A challenge RULE item's line (UX 11.6 item 2): the plan's load and reps for this set with its RIR aim. */
    private fun planTargetLine(card: Card): String? {
        val aim = planAim(card)?.let { "aim: ${Numbers.num(it, 1)} in reserve" } ?: return null
        if (card.prefillSource != PrefillSource.ENGINE_TARGET) return aim.replaceFirstChar { it.uppercase() }
        val point = card.target.native?.let { n -> card.points.firstOrNull { nativeOf(it, card.mode) == n } }
        val load = when {
            card.mode == LoadMode.BODYWEIGHT -> "BW"
            point != null -> Numbers.loadMain(point, card.mode)
            else -> null
        }
        val reps = card.target.reps?.let { "× $it" }
        return "Target " + listOfNotNull(load, reps).joinToString(" ") + " · " + aim
    }

    /** The plan's RIR aim of a challenge item (CDS `rirTarget`); null for warm-up sets and items without one. */
    private fun planAim(card: Card): Double? = if (card.warmup) null else planItem(card.item)?.plan?.rirTarget?.toDouble()

    /** The Why sheet of the card's prescription (UX 16.1); null when the set has no engine target. */
    fun why(card: Card): WhyContent? {
        val rx = card.item.rx ?: return null
        val t = card.rxTarget ?: return null
        val steps = rx.steps
        val q = Numbers.num(rx.qStar, 1)
        val summary = dev.fitnessapp.app.format.RxTexts.reason(steps, rx.mode, rx.qStar, card.probePickReps != null || (rx.probe && card.workIndex == 1))
        val numbers = buildList {
            // A probe set plans no load; once logged, its logged system load fills the gap.
            val logged = card.item.working.filter { it.side == card.side }.sortedBy { it.set_index }
            add("Plan  " + rx.sets.filter { it.side == card.side || it.side == "both" }.withIndex().joinToString(", ") { (i, s) ->
                val w = s.wSys ?: logged.getOrNull(i)?.effective_load_kg?.let { Scaled.toDouble(it, 2) }
                (w?.let { Numbers.num(it, 2) + " kg" } ?: "?") + " × " + (s.targetReps?.toString() ?: "?")
            } + " (system load)")
            add("Range ${rx.rLo}-${rx.rHi} reps, aim $q in reserve")
            t.rirPred?.let { add("Predicted reserve on this set ${Numbers.num(it, 1)}") }
            rx.mUse?.let { add("Strength used  " + Numbers.num(it, 1) + " kg max (cautious estimate)") }
            card.lastLine?.let { add(it) }
        }
        val evidence = dev.fitnessapp.app.format.RxTexts.evidence(rx.mode)
        val trace = dev.fitnessapp.data.db.JsonText.canonical(
            kotlinx.serialization.json.JsonObject(
                mapOf("recommendation_id" to JsonPrimitive(rx.recommendationId), "outputs" to rx.outputs, "trace" to rx.trace) +
                    (rx.inputs?.let { mapOf("inputs" to it) } ?: emptyMap()),
            ),
        )
        val title = targetLine(card)?.removePrefix("Target ")?.substringBefore(" · ") ?: "this set"
        return WhyContent(
            title = "Why $title?", summary = summary, numbers = numbers,
            module = "rx@${rx.moduleVersion} · params ${rx.paramsHash.take(8)} · ${rx.recommendationId.take(8)}",
            evidence = evidence, trace = trace, recommendationId = rx.recommendationId, codes = steps,
        )
    }

    fun whyOpened(w: WhyContent) = emit(
        "why_opened", null,
        mapOf("subject_kind" to JsonPrimitive("prescription"), "subject_id" to JsonPrimitive(w.recommendationId), "codes" to JsonArray(w.codes.map { JsonPrimitive(it) })),
    )

    fun traceExported() = emit("trace_exported", null, mapOf("via" to JsonPrimitive("clipboard")))

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

        /** How often the waiting next set's target is refitted to the rest so far (B13.7, D69). */
        private const val REST_FIT_TICK_MS = 15_000L

        /** Half of the 0.01 kg grid: two system loads closer than this are the same load. */
        private const val LOAD_EPS_KG = 0.005

        /** The most fingers of assistance (`assisted_ordinal`): the other hand's four fingers. */
        private const val MAX_FINGERS = 4

        /** How long after adding an exercise WE-S4 is evaluated (the item arrives through Room first). */
        private const val WARN_AFTER_ADD_MS = 600L

        /** The most extra reps a RIR check takes (B4.3). */
        private const val MAX_CALIB_EXTRA = 30

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
