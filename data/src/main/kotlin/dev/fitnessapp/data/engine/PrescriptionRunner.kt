package dev.fitnessapp.data.engine

import androidx.room.withTransaction
import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.estimation.ExerciseFacts
import dev.fitnessapp.algo.estimation.LoggedSet
import dev.fitnessapp.algo.estimation.CurveClass
import dev.fitnessapp.algo.estimation.rircal.CheckCandidate
import dev.fitnessapp.algo.estimation.rircal.ClassCal
import dev.fitnessapp.algo.estimation.sse.Sse
import dev.fitnessapp.algo.prescription.autoreg.Autoreg
import dev.fitnessapp.algo.prescription.autoreg.AutoregInput
import dev.fitnessapp.algo.prescription.autoreg.AutoregJson
import dev.fitnessapp.algo.prescription.autoreg.AutoregResult
import dev.fitnessapp.algo.estimation.rircal.RirCal
import dev.fitnessapp.algo.estimation.rircal.RirCalState
import dev.fitnessapp.algo.estimation.sse.SseState
import dev.fitnessapp.algo.estimation.sse.TrendState
import dev.fitnessapp.algo.pipeline.Exposure
import dev.fitnessapp.algo.pipeline.PreviousRx
import dev.fitnessapp.algo.pipeline.Prescribing
import dev.fitnessapp.algo.prescription.PrescriptionJson
import dev.fitnessapp.algo.prescription.rest.RestClass
import dev.fitnessapp.algo.prescription.rest.RestInput
import dev.fitnessapp.algo.prescription.rest.RestPolicy
import dev.fitnessapp.algo.prescription.rx.ItemScheme
import dev.fitnessapp.algo.prescription.rx.RepPref
import dev.fitnessapp.algo.prescription.rx.Rx
import dev.fitnessapp.algo.prescription.rx.RxMode
import dev.fitnessapp.algo.prescription.rx.RxOutput
import dev.fitnessapp.algo.prescription.rx.RxSet
import dev.fitnessapp.algo.prescription.rx.Schemes
import dev.fitnessapp.algo.pipeline.RxRequest
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.ExerciseModelStateEntity
import dev.fitnessapp.data.db.entity.RecommendationEntity
import dev.fitnessapp.data.db.entity.SessionExerciseEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.repo.CatalogRepository
import dev.fitnessapp.data.repo.EquipmentRepository
import dev.fitnessapp.data.repo.RepPreferenceRepository
import dev.fitnessapp.data.repo.SessionRepository
import dev.fitnessapp.data.repo.WriteContext
import dev.fitnessapp.data.sync.SyncScheduler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap

/** What the caller knows about a session exercise besides its row: a `delegate:rx` item's scheme, deload, rest. */
data class RxSlot(val sessionExerciseId: String, val item: ItemScheme? = null, val deload: Boolean = false, val restS: Double? = null)

/** One planned working set of a stored prescription, as the set card reads it. */
data class RxTarget(val setIndex: Int, val side: String, val targetReps: Int?, val rir: Double?, val wSys: Double?, val rirPred: Double?, val restS: Double?)

/** One generated warm-up set (WARMUP-1). */
data class WarmupTarget(val wSys: Double, val reps: Int, val restS: Double, val bodyweight: Boolean)

/** A stored `rx.prescription` decoded for the screens; [outputs], [trace] and [inputs] are the snapshot's JSON (Why sheet). */
data class Prescription(
    val recommendationId: String,
    val sessionExerciseId: String?,
    val mode: String,
    val probe: Boolean,
    val sets: List<RxTarget>,
    val warmup: List<WarmupTarget>,
    val rLo: Int,
    val rHi: Int,
    val qStar: Double,
    val mUse: Double?,
    val steps: List<String>,
    val moduleVersion: String,
    val paramsHash: String,
    val outputs: JsonObject,
    val trace: JsonObject,
    val inputs: JsonObject?,
    /** The working set that is today's explicit RIR check (F14, B4.3), or null. */
    val calibrationSet: Int? = null,
) {
    fun target(setIndex: Int, side: String): RxTarget? =
        sets.firstOrNull { it.setIndex == setIndex && it.side == side } ?: sets.firstOrNull { it.setIndex == setIndex && it.side == "both" }
}

/**
 * AUTO-1's verdict after a logged working set (B13): [result] with the stored `autoreg.suggestion` id when a card is
 * shown, and whether the exercise is `high_risk` (WE-S5 reads `rir_next_cur`).
 */
data class Suggestion(val recommendationId: String?, val sessionExerciseId: String, val side: String, val result: AutoregResult, val highRisk: Boolean)

/**
 * L6 on the phone for the exercises the user picks (D49, M3.4): builds RX-1's request for a session exercise from the
 * stored facts (SSE-1, RCM-1 and RIRCAL-1 states, the earlier exposures and their prescriptions, the loadables), runs
 * [Prescribing], stores the `rx.prescription` snapshot and pins it on the session exercise (`recommendation_id` and
 * the planned columns). A prescription is made once per session exercise; the probe's follow-up supersedes it (B9.8).
 * The user's Sets and reps preferences (M3.5) enter the scheme; timed holds and eccentric-only items keep "last time".
 */
class PrescriptionRunner(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
    private val catalog: CatalogRepository,
    private val catalogModel: () -> Catalog,
    private val equipment: EquipmentRepository,
    private val params: ParamView,
    private val paramsVersion: String,
    private val repPrefs: RepPreferenceRepository? = null,
) {
    private val mutex = Mutex()

    /** Whether RX-1 prescribes this exercise in M3.4: dynamic, counted in reps. */
    fun eligible(e: CatalogExercise): Boolean = e.measure == "reps" && e.contraction == "dynamic"

    /** Makes and stores the prescription of [slot] when it has none; returns its recommendation id (null when not eligible). */
    suspend fun ensure(slot: RxSlot): String? = mutex.withLock {
        val user = ctx.userId() ?: return@withLock null
        val se = db.sessionExercises().get(slot.sessionExerciseId) ?: return@withLock null
        se.recommendation_id?.let { return@withLock it }
        val session = db.sessions().get(se.session_id) ?: return@withLock null
        val ex = catalog.byId(se.exercise_id)?.takeIf(::eligible) ?: return@withLock null
        val requests = requests(session, se, ex, slot) ?: return@withLock null
        val results = requests.map { Prescribing.plan(it, params.fork()) }
        val out = combine(results.map { it.output })
        val check = checkSet(user, session, se, ex, out, slot, requests.first())
        store(user, session, se, requests, out, results.firstNotNullOfOrNull { it.warmup }?.let { PrescriptionJson.warmup(it) }, null, check)
    }

    /**
     * The explicit RIR check of the session (F14, B4.3): with the opt-in on and no check yet in this session, RIRCAL-1
     * chooses among the session's exercises in plan order; when it is [se], its last working set is the check.
     */
    private suspend fun checkSet(
        user: String, session: WorkoutSessionEntity, se: SessionExerciseEntity, ex: CatalogExercise, out: RxOutput, slot: RxSlot, req: RxRequest,
    ): Int? {
        if (db.profiles().get(user)?.rir_calibration_opt_in != true) return null
        if (out.probe || (out.mode != RxMode.MODEL && out.mode != RxMode.DOUBLE) || se.challenge_item_key != null) return null
        val taken = db.recommendations().bySession(KIND, session.id).any { r ->
            Json.parseToJsonElement(r.outputs).jsonObject["calibration_set_index"]?.let { it !is JsonNull } == true
        }
        if (taken) return null
        val candidates = db.sessionExercises().bySession(session.id).filter { it.status !in setOf("removed", "skipped", "done") }.mapNotNull { o ->
            val e = catalog.byId(o.exercise_id) ?: return@mapNotNull null
            CheckCandidate(
                exerciseId = o.exercise_id, planPosition = o.position.toInt(), curveClass = e.curveClass?.let { CurveClass.of(it) },
                safetyClass = e.safetyClass, challenge = o.challenge_item_key != null, ruleMode = o.progression_mode == "rule", jointFlagged = false,
            )
        }
        val chosen = RirCal.chooseCheck(candidates, true, slot.deload, LocalDate.parse(session.local_date), req.rircal, params) ?: return null
        if (chosen.exerciseId != se.exercise_id || chosen.planPosition != se.position.toInt()) return null
        return out.sets.maxOfOrNull { it.setIndex }
    }

    /**
     * After the probe of [slot] is logged on every side (B9.8): RX-1 for the remaining sets from SSE-1's update with the
     * probe; the new prescription keeps set 1 and supersedes the old one. Returns the new id, or null when there is
     * nothing to follow up (no probe, already followed up from these probe sets, or a side still to log). A probe that
     * was undone and logged again plans the remaining sets anew from the original probe prescription.
     */
    suspend fun followUp(slot: RxSlot): String? = mutex.withLock {
        val user = ctx.userId() ?: return@withLock null
        val se = db.sessionExercises().get(slot.sessionExerciseId) ?: return@withLock null
        val oldId = se.recommendation_id ?: return@withLock null
        val oldRow = db.recommendations().byIds(listOf(oldId)).firstOrNull() ?: return@withLock null
        val current = decode(oldRow) ?: return@withLock null
        if (!current.probe) return@withLock null
        val probes = db.setLogs().bySession(se.session_id).filter { it.session_exercise_id == se.id && it.set_kind != "warmup" && it.set_index == 1 }
        val probeIds = probes.map { it.id }.sorted()
        val old = if (FOLLOWUP in current.steps) {
            if (current.trace["probe_set_ids"]?.jsonArray?.map { it.jsonPrimitive.content } == probeIds) return@withLock null
            original(oldRow) ?: return@withLock null
        } else current
        val session = db.sessions().get(se.session_id) ?: return@withLock null
        val ex = catalog.byId(se.exercise_id) ?: return@withLock null
        val requests = requests(session, se, ex, slot) ?: return@withLock null
        val follows = requests.map { r ->
            val row = probes.firstOrNull { it.side == r.side } ?: return@withLock null
            val probe = EngineFacts.logged(row, ex.contraction, ctx) ?: return@withLock null
            Prescribing.followUp(r, probe, params.fork()) ?: return@withLock null
        }
        val follow = combine(follows)
        val fresh = PrescriptionJson.rx(follow)
        val oldSets = old.outputs.getValue("sets").jsonArray
        val keep = oldSets.indices.filter { oldSets[it].jsonObject["set_index"]?.jsonPrimitive?.intOrNull == 1 }
        fun merged(key: String) = JsonArray(keep.map { old.outputs[key]?.jsonArray?.getOrNull(it) ?: JsonNull } + fresh.getValue(key).jsonArray)
        val outputs = JsonObject(old.outputs + mapOf("sets" to merged("sets"), "rir_pred" to merged("rir_pred"), "rest_s" to merged("rest_s"), "double" to fresh.getValue("double")))
        val trace = JsonObject(
            Prescribing.trace(follow).jsonObject + ("steps" to JsonArray((old.steps + follow.trace).map { JsonPrimitive(it) })) +
                ("probe_set_ids" to JsonArray(probeIds.map { JsonPrimitive(it) })),
        )
        storeRaw(user, session, se, requests, follow, outputs, trace, oldId, "followup:" + probes.joinToString(",") { it.id })
    }

    /** The probe prescription a chain of follow-ups started from (the first one without [FOLLOWUP]). */
    private suspend fun original(row: RecommendationEntity): Prescription? {
        var r = row
        repeat(MAX_CHAIN) {
            val p = decode(r) ?: return null
            if (FOLLOWUP !in p.steps) return p
            r = r.supersedes_id?.let { db.recommendations().byIds(listOf(it)).firstOrNull() } ?: return null
        }
        return null
    }

    /** The stored prescription [id], decoded. */
    suspend fun get(id: String): Prescription? = db.recommendations().byIds(listOf(id)).firstOrNull()?.let(::decode)

    /**
     * The prescription the exercise would get next time (Summary "Next time", UX 12.3): the same request as at a new
     * session one day later, after the L8 update of this session. A `delegate:rx` item keeps the scheme and rest its
     * stored prescription had. Not stored; a real session makes its own.
     */
    suspend fun preview(sessionExerciseId: String): Pair<Prescription, RxOutput>? {
        val se = db.sessionExercises().get(sessionExerciseId) ?: return null
        val now = se.recommendation_id?.let { get(it) } ?: return null
        val session = db.sessions().get(se.session_id) ?: return null
        val ex = catalog.byId(se.exercise_id)?.takeIf(::eligible) ?: return null
        val stored = now.inputs?.get("requests")?.jsonArray?.firstOrNull()?.jsonObject
        val item = stored?.get("item")?.takeIf { it !is JsonNull }?.jsonObject?.let { i ->
            ItemScheme(
                i.getValue("rep_min").jsonPrimitive.content.toInt(), i.getValue("rep_max").jsonPrimitive.content.toInt(),
                i["rir_target"]?.jsonPrimitive?.doubleOrNull, i["sets"]?.jsonPrimitive?.intOrNull,
            )
        }
        val slot = RxSlot(se.id, item, restS = item?.let { stored["rest_s"]?.jsonPrimitive?.doubleOrNull })
        val nextDay = LocalDate.parse(session.local_date).plusDays(1)
        val at = Instant.ofEpochMilli(session.finished_at ?: ctx.nowMs())
        val requests = requests(session, se, ex, slot, includeThisSession = true)?.map { it.copy(at = at, today = nextDay) } ?: return null
        return now to combine(requests.map { Prescribing.plan(it, params.fork()).output })
    }

    /**
     * AUTO-1 after a logged working set of [slot] on [side] (B13, UI-06): `delta_ex` from today's sets, the trigger and,
     * when it fires, a proposal for the remaining sets. A shown card is stored as `autoreg.suggestion` (scope
     * `session_exercise:<id>`); at most `autoreg.max_suggestions_per_exercise` per exercise and session. RULE items
     * have no prescription here, so their shadow waits for the RULE shadow snapshot.
     */
    suspend fun suggest(slot: RxSlot, side: String, feelingGood: Boolean = false): Suggestion? = mutex.withLock {
        val user = ctx.userId() ?: return@withLock null
        val se = db.sessionExercises().get(slot.sessionExerciseId) ?: return@withLock null
        val session = db.sessions().get(se.session_id) ?: return@withLock null
        val ex = catalog.byId(se.exercise_id)?.takeIf(::eligible) ?: return@withLock null
        val sideKey = if (se.side_mode == "bilateral") "both" else side
        val sessionSets = sessionLogged(session)
        val base = autoregInput(session, se, ex, slot, sideKey, sessionSets) ?: return@withLock null
        val scope = "session_exercise:${se.id}"
        val cards = db.recommendations().byScope(AUTOREG_KIND, scope)
            .count { Json.parseToJsonElement(it.outputs).jsonObject["card"]?.jsonPrimitive?.booleanOrNull == true }
        val delta = deltaToday(session, se, sessionSets, feelingGood)
        val input = base.copy(deltaStart = delta, cardsShown = cards)
        val r = Autoreg.evaluate(input, params.fork())
        val highRisk = ex.highRiskIn(se.load_mode)
        if (!r.card) return@withLock Suggestion(null, se.id, sideKey, r, highRisk)
        val lastSet = input.logged.last().set.id
        val inputs = buildJsonObject {
            put("session_exercise_id", se.id)
            put("side", sideKey)
            put("set_ids", JsonArray(input.logged.map { JsonPrimitive(it.set.id) }))
            put("prescription_id", se.recommendation_id?.let { JsonPrimitive(it) } ?: JsonNull)
            put("delta_start", JsonPrimitive(delta))
            put("cards_shown", JsonPrimitive(cards))
            put("technique_events", JsonPrimitive(input.techniqueEvents))
        }
        val id = storeSnapshot(
            user, session, se, AUTOREG_KIND, Autoreg.ref.id.value, Autoreg.ref.version.toString(), scope, inputs,
            AutoregJson.result(r), JsonArray(r.trace.map { JsonPrimitive(it) }), lastSet,
        )
        Suggestion(id, se.id, sideKey, r, highRisk)
    }

    /**
     * The next set's target of [slot] on [side] after [restS] seconds of rest since its last logged set (B13.7, D69):
     * the reps at the planned load from that set's own result, or null when the target stays. Not stored: the logged
     * set keeps the target it was done against as its `planned_reps`.
     */
    suspend fun restTarget(slot: RxSlot, side: String, restS: Double): RxSet? = mutex.withLock {
        val se = db.sessionExercises().get(slot.sessionExerciseId) ?: return@withLock null
        val session = db.sessions().get(se.session_id) ?: return@withLock null
        val ex = catalog.byId(se.exercise_id)?.takeIf(::eligible) ?: return@withLock null
        val sideKey = if (se.side_mode == "bilateral") "both" else side
        val sessionSets = sessionLogged(session)
        val base = autoregInput(session, se, ex, slot, sideKey, sessionSets) ?: return@withLock null
        Autoreg.restTarget(base.copy(deltaStart = deltaToday(session, se, sessionSets, false)), restS, params.fork())
    }

    /** The session's logged sets as the estimators read them (every exercise, for FAT-1's carry-over). */
    private suspend fun sessionLogged(session: WorkoutSessionEntity): List<Pair<SetLogEntity, LoggedSet>> =
        db.setLogs().bySession(session.id).mapNotNull { r ->
            val c = catalog.byId(r.exercise_id)?.contraction ?: return@mapNotNull null
            EngineFacts.logged(r, c, ctx)?.let { r to it }
        }

    /** AUTO-1's input for one exercise and side: the prescription's RX-1 input and output and today's working sets. */
    private suspend fun autoregInput(
        session: WorkoutSessionEntity, se: SessionExerciseEntity, ex: CatalogExercise, slot: RxSlot, side: String,
        sessionSets: List<Pair<SetLogEntity, LoggedSet>>,
    ): AutoregInput? {
        val rx = se.recommendation_id?.let { get(it) } ?: return null
        val req = requests(session, se, ex, slot)?.firstOrNull { it.side == side } ?: return null
        val mine = sessionSets.filter { (row, _) -> row.session_exercise_id == se.id && row.set_kind in WORKING && (side == "both" || row.side == side) }
        if (mine.isEmpty()) return null
        val model = catalogModel()
        val facts = TreeMap<String, ExerciseFacts>()
        sessionSets.map { it.first.exercise_id }.distinct().forEach { id -> catalog.byId(id)?.let { facts[id] = EngineFacts.facts(catalog, model, it) } }
        val ids = mine.map { it.second.id }.toSet()
        val prepared = Sse.prepare(sessionSets.map { it.second }, facts, req.rircal, params.fork()).filter { it.set.id in ids }
        val (input, plan) = if ("rx.probe.followup" in rx.steps) {
            val i = Prescribing.followUpInput(req, mine.first().second, params.fork()) ?: return null
            val out = Rx.prescribe(i, params.fork())
            i to out.copy(sets = out.sets.map { it.copy(setIndex = it.setIndex + 1) }, trace = out.trace + "rx.probe.followup")
        } else {
            val i = Prescribing.input(req, params.fork())
            i to Rx.prescribe(i, params.fork())
        }
        val technique = mine.count { it.second.reasonCode in TECHNIQUE }
        return AutoregInput(input, plan, prepared, techniqueEvents = technique, ruleItem = false)
    }

    /**
     * `delta_today` for [se] (B13.2): from the other exercises of the session with an engine prescription whose working
     * sets were all done before [se] started, through [Autoreg.deltaToday].
     */
    private suspend fun deltaToday(
        session: WorkoutSessionEntity, se: SessionExerciseEntity, sessionSets: List<Pair<SetLogEntity, LoggedSet>>, feelingGood: Boolean,
    ): Double {
        val firstMine = sessionSets.filter { it.first.session_exercise_id == se.id }.minOfOrNull { it.first.completed_at } ?: Long.MAX_VALUE
        val finished = db.sessionExercises().bySession(session.id).filter { o ->
            o.id != se.id && o.recommendation_id != null &&
                sessionSets.filter { it.first.session_exercise_id == o.id && it.first.set_kind in WORKING }
                    .let { s -> s.isNotEmpty() && s.maxOf { it.first.completed_at } < firstMine }
        }
        val deltas = finished.mapNotNull { o ->
            val ex = catalog.byId(o.exercise_id)?.takeIf(::eligible) ?: return@mapNotNull null
            val side = if (o.side_mode == "bilateral") "both" else "L"
            autoregInput(session, o, ex, RxSlot(o.id), side, sessionSets)?.let { Autoreg.evaluate(it, params.fork()).deltaEx }
        }
        return Autoreg.deltaToday(deltas, feelingGood, params)
    }

    private suspend fun storeSnapshot(
        user: String, session: WorkoutSessionEntity, se: SessionExerciseEntity, kind: String, moduleId: String, moduleVersion: String,
        scope: String, inputs: JsonObject, outputs: JsonObject, trace: JsonElement, setLogId: String?,
    ): String {
        val inputsText = JsonText.canonical(inputs)
        val inputsHash = dev.fitnessapp.algo.core.det.Jcs.sha256Hex(inputs)
        val id = Ids.fitness(listOf("recommendations", user, kind, scope, params.hash, inputsHash, "").joinToString(":")).toString()
        val now = ctx.nowMs()
        val date = LocalDate.parse(session.local_date)
        db.withTransaction {
            val paramsRow = EngineFacts.paramsSnapshot(ctx, user, params, paramsVersion)
            if (db.paramsSnapshots().insertIgnore(paramsRow) != -1L) outbox.enqueue("params_snapshots", paramsRow.id, now)
            val row = RecommendationEntity(
                id = id, user_id = user, device_id = ctx.device.deviceId(), app_version = ctx.appVersion, kind = kind,
                module_id = moduleId, module_version = moduleVersion, engine_version = EngineVersion.ENGINE_VERSION,
                params_hash = params.hash, catalog_version = catalog.version, inputs_hash = inputsHash, inputs = inputsText,
                inputs_bytes = inputsText.toByteArray(Charsets.UTF_8).size, outputs = JsonText.canonical(outputs), trace = JsonText.canonical(trace),
                scope_key = scope, scope_date = date.toString(), iso_week = Week.isoWeek(date), session_id = session.id,
                session_exercise_id = se.id, set_log_id = setLogId,
            )
            if (db.recommendations().insertIgnore(listOf(row)).first() != -1L) outbox.enqueue("recommendations", id, now)
        }
        scheduler.request()
        return id
    }

    // ------------------------------------------------------------------------------------------------ request

    private suspend fun requests(
        session: WorkoutSessionEntity,
        se: SessionExerciseEntity,
        ex: CatalogExercise,
        slot: RxSlot,
        includeThisSession: Boolean = false,
    ): List<RxRequest>? {
        val p = params.fork()
        val mode = runCatching { LoadMode.of(se.load_mode) }.getOrNull() ?: return null
        val bw = session.bodyweight_kg?.let { Scaled.toDouble(it, 2) }
        val (_, points) = equipment.loadables(ex, mode, bw, p)
        val loadables = points.mapNotNull { it.wSys }.map { Grid.kg(it) }.distinct().sorted()
        val bodyweightW = bw?.takeIf { mode == LoadMode.BODYWEIGHT || mode == LoadMode.BODYWEIGHT_PLUS_EXTERNAL }?.let { Grid.kg(it * ex.bwFraction) }
        val model = catalogModel()
        val facts = EngineFacts.facts(catalog, model, ex)
        val sse = db.modelStates().forExercise("sse", se.exercise_id).associateBy { it.side }
        val rcmK = db.modelStates().forExercise("rcm", se.exercise_id).firstOrNull()?.let { num(it, "k_used") }
        val rircal = rircal()
        val transferM = if (sse.isEmpty()) transfer(se.exercise_id) else null
        val prefs = repPrefs?.resolve(ex, se.exercise_id, primaryGroup(ex, model)).orEmpty()
        val restPref = Schemes.restPref(prefs)
        val restS = slot.restS ?: RestPolicy.decide(
            RestInput(RestClass.of(ex.restClass), userRestPrefS = restPref?.restPrefS, userRestMult = restPref?.restPrefMult), p,
        ).timerS
        val history = db.setLogs().historyForExercise(se.exercise_id, if (includeThisSession) "" else session.id, HISTORY_LIMIT)
            .filter { it.set.set_kind != "warmup" && it.session_started_at != null && (it.session_started_at <= (session.started_at ?: Long.MAX_VALUE) || includeThisSession) }
        val bySession = history.groupBy { it.set.session_id }
        val order = history.map { it.set.session_id }.distinct()
        val rxBySe = db.recommendations().byIds(
            db.sessionExercises().byIds(history.map { it.set.session_exercise_id }.distinct()).mapNotNull { it.recommendation_id },
        ).associateBy { it.session_exercise_id }
        val (warm, first) = groupContext(session, se, ex, model)
        val sides = if (se.side_mode == "bilateral") listOf("both") else listOf("L", "R")
        return sides.map { side ->
            val exposures = order.mapNotNull { sid ->
                val rows = bySession.getValue(sid).filter { side == "both" || it.set.side == side }
                val sets = rows.mapNotNull { EngineFacts.logged(it.set, ex.contraction, ctx) }.sortedWith(compareBy({ it.performedAt }, { it.id }))
                if (sets.isEmpty()) return@mapNotNull null
                val prev = rows.firstNotNullOfOrNull { rxBySe[it.set.session_exercise_id] }?.let { previous(it, side) }
                Exposure(sid, LocalDate.parse(rows.first().session_local_date), sets, prev)
            }
            RxRequest(
                exerciseId = se.exercise_id, side = side, facts = facts, isolation = ex.mechanics == "isolation",
                highRisk = ex.highRiskIn(mode.code), bodyweightOnly = mode == LoadMode.BODYWEIGHT,
                prefs = prefs, item = slot.item, sets = se.planned_sets ?: SessionRepository.DEFAULT_SETS, restS = restS,
                loadables = loadables, bodyweightW = bodyweightW,
                sse = (sse[side] ?: sse["both"])?.let(::sseState), rcmK = rcmK, rircal = rircal, transferM = transferM,
                exposures = exposures, at = Instant.ofEpochMilli(session.started_at ?: ctx.nowMs()),
                today = LocalDate.parse(session.local_date), deload = slot.deload, warm = warm, firstForGroup = first,
            )
        }
    }

    /** WARMUP-1's context (B10): the primary group already worked today, and whether this is its first exercise. */
    private suspend fun groupContext(session: WorkoutSessionEntity, se: SessionExerciseEntity, ex: CatalogExercise, model: Catalog): Pair<Boolean, Boolean> {
        val group = primaryGroup(ex, model) ?: return false to true
        val before = db.sessionExercises().bySession(session.id).filter { it.position < se.position && it.status !in setOf("removed", "skipped") }
        val same = before.filter { o -> catalog.byId(o.exercise_id)?.let { primaryGroup(it, model) } == group }
        val sets = db.setLogs().bySession(session.id).filter { s -> s.set_kind != "warmup" && same.any { it.id == s.session_exercise_id } }
        val units = sets.sumOf { if (it.side == "both") 2 else 1 } / 2
        return (units >= params.int("warmup.warm_min_sets")) to same.isEmpty()
    }

    /** The rest preference that applies to [ex] (B9.3, D50): seconds or a multiplier, from the most specific scope. */
    suspend fun userRest(ex: CatalogExercise, exerciseId: String): RepPref? =
        repPrefs?.let { Schemes.restPref(it.resolve(ex, exerciseId, primaryGroup(ex, catalogModel()))) }

    private fun primaryGroup(e: CatalogExercise, model: Catalog): String? =
        model.taxonomy.activeGroups.map { it.id to model.groupCoeff(e, it.id) }.filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).firstOrNull()?.first

    private suspend fun transfer(exerciseId: String): Double? {
        val states = db.modelStates().live().filter { it.model == "sse" && it.exercise_id != null }
        return EngineFacts.transfers(catalog).filter { it.toExerciseId == exerciseId }.sortedBy { it.fromExerciseId }
            .firstNotNullOfOrNull { t -> states.filter { it.exercise_id == t.fromExerciseId }.minByOrNull { it.side }?.let { t.ratio * Det.exp(num(it, "l")!!) } }
    }

    private suspend fun rircal(): RirCalState {
        val rows = db.modelStates().live().filter { it.model == "rircal" }
        val user = rows.firstOrNull { it.class_key == "user" } ?: return RirCal.initial(params)
        val u = Json.parseToJsonElement(user.state).jsonObject
        val classes = TreeMap<String, ClassCal>()
        rows.filter { it.class_key != null && it.class_key != "user" }.forEach { r ->
            val o = Json.parseToJsonElement(r.state).jsonObject
            classes[r.class_key!!] = ClassCal(
                o.getValue("b_c").jsonPrimitive.content.toDouble(), o.getValue("n_c").jsonPrimitive.content.toInt(),
                o["last_check_date"]?.jsonPrimitive?.contentOrNull?.let { LocalDate.parse(it) },
            )
        }
        return RirCalState(
            u.getValue("b").jsonPrimitive.content.toDouble(), u.getValue("s_q").jsonPrimitive.content.toDouble(),
            u.getValue("n").jsonPrimitive.content.toInt(), classes, u["last_check_week"]?.jsonPrimitive?.contentOrNull,
            u["checks_in_last_check_week"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private fun sseState(r: ExerciseModelStateEntity): SseState {
        val o = Json.parseToJsonElement(r.state).jsonObject
        fun d(k: String) = o.getValue(k).jsonPrimitive.content.toDouble()
        return SseState(
            TrendState(d("l"), d("g"), d("p_ll"), d("p_lg"), d("p_gg"), Instant.parse(o.getValue("t_last").jsonPrimitive.content), d("n_obs").toInt()),
            d("k"),
        )
    }

    private fun num(r: ExerciseModelStateEntity, key: String): Double? =
        Json.parseToJsonElement(r.state).jsonObject[key]?.jsonPrimitive?.doubleOrNull

    private fun previous(r: RecommendationEntity, side: String): PreviousRx? {
        if (r.kind != KIND) return null
        val o = Json.parseToJsonElement(r.outputs).jsonObject
        val mode = RxMode.entries.firstOrNull { it.code == o["mode"]?.jsonPrimitive?.contentOrNull } ?: return null
        val d = o["double"]?.takeIf { it !is JsonNull }?.jsonObject
        val sets = o["sets"]?.jsonArray.orEmpty().map { it.jsonObject }.filter { side == "both" || it["side"]?.jsonPrimitive?.contentOrNull == side }
        val steps = Json.parseToJsonElement(r.trace).let { t -> (t as? JsonObject)?.get("steps")?.jsonArray?.map { it.jsonPrimitive.content } }.orEmpty()
        return PreviousRx(
            mode = mode,
            targets = d?.get("targets")?.jsonArray?.map { it.jsonPrimitive.content.toInt() }
                ?: sets.mapNotNull { it["target_reps"]?.jsonPrimitive?.intOrNull }.takeIf { it.isNotEmpty() },
            ext = d?.get("ext")?.jsonPrimitive?.intOrNull ?: 0,
            probe = o["probe"]?.jsonPrimitive?.booleanOrNull ?: false,
            deload = "rx.deload" in steps,
        )
    }

    /** One output per side; a unilateral exercise keeps each side's own targets (B9.13 `independent`). */
    private fun combine(outs: List<RxOutput>): RxOutput {
        if (outs.size == 1) return outs.single()
        val (weak, strong) = Rx.combineSides(outs[0], outs[1], matchWeaker = false, oneSetting = false)
        return weak.copy(sets = (weak.sets + strong.sets).sortedWith(compareBy({ it.setIndex }, { it.side })), trace = weak.trace + strong.trace)
    }

    // ------------------------------------------------------------------------------------------------ storage

    private suspend fun store(
        user: String, session: WorkoutSessionEntity, se: SessionExerciseEntity, requests: List<RxRequest>, out: RxOutput,
        warmup: JsonObject?, supersedes: String?, check: Int? = null,
    ): String {
        val outputs = JsonObject(
            PrescriptionJson.rx(out) + ("warmup" to (warmup ?: JsonNull)) +
                ("calibration_set_index" to (check?.let { JsonPrimitive(it) } ?: JsonNull)),
        )
        return storeRaw(user, session, se, requests, out, outputs, Prescribing.trace(out).jsonObject, supersedes, null)
    }

    private suspend fun storeRaw(
        user: String, session: WorkoutSessionEntity, se: SessionExerciseEntity, requests: List<RxRequest>, out: RxOutput,
        outputs: JsonObject, trace: JsonObject, supersedes: String?, salt: String?,
    ): String {
        val inputs = JsonObject(mapOf("requests" to JsonArray(requests.map { Prescribing.inputs(it) }), "session_exercise_id" to JsonPrimitive(se.id)))
        val inputsText = JsonText.canonical(inputs)
        val inputsHash = dev.fitnessapp.algo.core.det.Jcs.sha256Hex(inputs)
        val scope = "session_exercise:${se.id}"
        val id = Ids.fitness(listOf("recommendations", user, KIND, scope, params.hash, inputsHash, salt ?: "").joinToString(":")).toString()
        val now = ctx.nowMs()
        val date = LocalDate.parse(session.local_date)
        val first = out.sets.firstOrNull()
        db.withTransaction {
            val paramsRow = EngineFacts.paramsSnapshot(ctx, user, params, paramsVersion)
            if (db.paramsSnapshots().insertIgnore(paramsRow) != -1L) outbox.enqueue("params_snapshots", paramsRow.id, now)
            val row = RecommendationEntity(
                id = id, user_id = user, device_id = ctx.device.deviceId(), app_version = ctx.appVersion, kind = KIND,
                module_id = Rx.ref.id.value, module_version = Rx.ref.version.toString(), engine_version = EngineVersion.ENGINE_VERSION,
                params_hash = params.hash, catalog_version = catalog.version, inputs_hash = inputsHash, inputs = inputsText,
                inputs_bytes = inputsText.toByteArray(Charsets.UTF_8).size, outputs = JsonText.canonical(outputs), trace = JsonText.canonical(trace),
                scope_key = scope, scope_date = date.toString(), iso_week = Week.isoWeek(date), session_id = session.id,
                session_exercise_id = se.id, supersedes_id = supersedes,
            )
            if (db.recommendations().insertIgnore(listOf(row)).first() != -1L) outbox.enqueue("recommendations", id, now)
            val fresh = db.sessionExercises().get(se.id) ?: return@withTransaction
            db.sessionExercises().upsert(
                fresh.copy(
                    recommendation_id = id,
                    progression_mode = when (out.mode) { RxMode.MODEL -> "model"; RxMode.DOUBLE -> "double"; RxMode.RULE -> "rule"; else -> "none" },
                    planned_rep_min = out.scheme.rLo, planned_rep_max = out.scheme.rHi,
                    planned_rir = Scaled.toLong(java.math.BigDecimal.valueOf(out.scheme.qStar), 1),
                    planned_effective_load_kg = first?.wSys?.let { Scaled.toLong(java.math.BigDecimal.valueOf(it), 2) } ?: fresh.planned_effective_load_kg,
                    planned_rest_s = first?.restS?.toInt() ?: fresh.planned_rest_s,
                    // A sets preference sets S for an exercise the user picked (B9.3); challenge items keep theirs.
                    planned_sets = if (fresh.challenge_item_key == null) out.sets.maxOfOrNull { it.setIndex } ?: fresh.planned_sets else fresh.planned_sets,
                    client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
                ),
            )
            outbox.enqueue("session_exercises", se.id, now)
        }
        scheduler.request()
        return id
    }

    private fun decode(r: RecommendationEntity): Prescription? {
        if (r.kind != KIND) return null
        val o = Json.parseToJsonElement(r.outputs).jsonObject
        val t = Json.parseToJsonElement(r.trace) as? JsonObject ?: JsonObject(emptyMap())
        fun dbl(e: kotlinx.serialization.json.JsonElement?) = e?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull
        val rirPred = o["rir_pred"]?.jsonArray.orEmpty()
        val rest = o["rest_s"]?.jsonArray.orEmpty()
        val sets = o["sets"]?.jsonArray.orEmpty().mapIndexed { i, e ->
            val s = e.jsonObject
            RxTarget(
                s.getValue("set_index").jsonPrimitive.content.toInt(), s.getValue("side").jsonPrimitive.content,
                s["target_reps"]?.jsonPrimitive?.intOrNull, dbl(s["rir"]), dbl(s["effective_load_kg"]), dbl(rirPred.getOrNull(i)), dbl(rest.getOrNull(i)),
            )
        }
        val warm = o["warmup"]?.takeIf { it !is JsonNull }?.jsonObject?.get("sets")?.jsonArray.orEmpty().map { e ->
            val s = e.jsonObject
            WarmupTarget(dbl(s["w_sys"])!!, s.getValue("reps").jsonPrimitive.content.toInt(), dbl(s["rest_s"]) ?: 0.0, s["bodyweight"]?.jsonPrimitive?.booleanOrNull ?: false)
        }
        val scheme = o.getValue("scheme").jsonObject
        return Prescription(
            recommendationId = r.id, sessionExerciseId = r.session_exercise_id, mode = o.getValue("mode").jsonPrimitive.content,
            probe = o["probe"]?.jsonPrimitive?.booleanOrNull ?: false, sets = sets, warmup = warm,
            rLo = scheme.getValue("r_lo").jsonPrimitive.content.toInt(), rHi = scheme.getValue("r_hi").jsonPrimitive.content.toInt(),
            qStar = scheme.getValue("q_star").jsonPrimitive.content.toDouble(), mUse = dbl(t["m_use"]),
            steps = t["steps"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(), moduleVersion = r.module_version, paramsHash = r.params_hash,
            outputs = o, trace = t, inputs = r.inputs?.let { Json.parseToJsonElement(it).jsonObject },
            calibrationSet = o["calibration_set_index"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull,
        )
    }

    companion object {
        const val KIND = "rx.prescription"
        const val AUTOREG_KIND = "autoreg.suggestion"
        private const val FOLLOWUP = "rx.probe.followup"

        /** Longest follow-up chain [original] walks; each undo and re-log of the probe adds one link. */
        private const val MAX_CHAIN = 64

        private val WORKING = setOf("working", "top", "backoff", "amrap")
        private val TECHNIQUE = setOf("technique_breakdown", "torso_rotation")

        /** Page size of the history read; far more than any exposure window RX-1 looks at. */
        private const val HISTORY_LIMIT = 2000
    }
}
