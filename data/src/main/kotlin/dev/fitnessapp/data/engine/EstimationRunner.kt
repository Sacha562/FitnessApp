package dev.fitnessapp.data.engine

import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.estimation.LoggedSet
import dev.fitnessapp.algo.estimation.hold.HoldFacts
import dev.fitnessapp.algo.estimation.hold.HoldSet
import dev.fitnessapp.algo.pipeline.EstimationReplay
import dev.fitnessapp.algo.pipeline.FinishedSession
import dev.fitnessapp.algo.pipeline.ModelStateRow
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.ExerciseModelStateEntity
import dev.fitnessapp.data.db.entity.RecommendationEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.repo.CatalogRepository
import dev.fitnessapp.data.repo.WriteContext
import dev.fitnessapp.data.sync.SyncScheduler
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap

/**
 * The L8 estimation layer on the phone (ALGORITHMS.md A12, M3.2): replays every finished session in order through
 * [EstimationReplay] and stores what is new. Snapshot ids are derived from their content, so a replay of unchanged
 * facts writes nothing, and an edit of a past set adds the snapshots it changes (append-only). The estimator caches
 * in `exercise_model_state` are rewritten when their state hash changes and tombstoned when no fact supports them.
 *
 * Ordering with the challenge engine (CHALLENGE_ENGINE 3.9): a challenge session's `session.completed` (and a test
 * day's `test.completed`) captures RCM-1's `k` before the session's own L8 update. So the replay stops at a
 * challenge session whose completion event is missing, for [completionWaitMs] after it finished; after that the
 * completion is taken as failed and the session is estimated anyway.
 */
class EstimationRunner(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
    private val catalog: CatalogRepository,
    private val catalogModel: () -> Catalog,
    private val params: ParamView,
    private val paramsVersion: String,
    private val completionWaitMs: Long = 6 * 3_600_000L,
) {
    private val mutex = Mutex()

    data class Report(val sessions: Int, val deferredSessionId: String?, val snapshotsAdded: Int, val statesWritten: Int, val statesRetired: Int)

    /** One run; null when nobody is signed in. Serialized: a run that starts while another runs waits for it. */
    suspend fun run(): Report? = mutex.withLock {
        val userId = ctx.userId() ?: return@withLock null
        val nowMs = ctx.nowMs()
        val (sessions, deferred) = replayable(nowMs)
        val result = EstimationReplay.run(
            sessions.map { finished(it) }, EngineFacts.exerciseFacts(catalog, catalogModel()), holdFacts(), EngineFacts.transfers(catalog), params.fork(),
        )

        var added = 0
        var written = 0
        var retired = 0
        db.withTransaction {
            val paramsRow = EngineFacts.paramsSnapshot(ctx, userId, params, paramsVersion)
            if (db.paramsSnapshots().insertIgnore(paramsRow) != -1L) outbox.enqueue("params_snapshots", paramsRow.id, nowMs)

            val known = db.recommendations().idsOfKinds(KINDS).toSet()
            val heads = heads(db.recommendations().chain(KINDS))
            val lastByScope = TreeMap<String, String>()
            val rows = ArrayList<RecommendationEntity>()
            for (snap in result.snapshots) {
                val scopeId = snap.kind + "|" + snap.scopeKey
                val id = Ids.fitness(
                    listOf("recommendations", userId, snap.kind, snap.scopeKey, snap.sessionId, snap.module.version.toString(), params.hash, snap.inputsHash)
                        .joinToString(":"),
                ).toString()
                // The previous snapshot of the same scope: this session's last stored one (a past set was edited), else
                // the one before it in the replay (RCM-1's exercise scope runs through the sessions).
                val supersedes = (heads[scopeId + "|" + snap.sessionId] ?: lastByScope[scopeId])?.takeIf { it != id }
                lastByScope[scopeId] = id
                if (id in known) continue
                val inputs = JsonText.canonical(snap.inputs)
                rows += RecommendationEntity(
                    id = id, user_id = userId, device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
                    kind = snap.kind, module_id = snap.module.id.value, module_version = snap.module.version.toString(),
                    engine_version = EngineVersion.ENGINE_VERSION, params_hash = params.hash, catalog_version = catalog.version,
                    inputs_hash = snap.inputsHash, inputs = inputs, inputs_bytes = inputs.toByteArray(Charsets.UTF_8).size,
                    outputs = JsonText.canonical(snap.outputs), trace = JsonText.canonical(snap.trace),
                    scope_key = snap.scopeKey, scope_date = snap.scopeDate.toString(), iso_week = Week.isoWeek(snap.scopeDate),
                    session_id = snap.sessionId, set_log_id = snap.setLogId, supersedes_id = supersedes,
                )
            }
            db.recommendations().insertIgnore(rows).forEachIndexed { i, rowId ->
                if (rowId != -1L) {
                    outbox.enqueue("recommendations", rows[i].id, nowMs)
                    added++
                }
            }

            val existing = db.modelStates().live().associateBy { it.id }
            val produced = TreeMap<String, ModelStateRow>()
            result.states.forEach { produced[stateId(userId, it)] = it }
            for ((id, st) in produced) {
                val old = existing[id]
                if (old != null && old.state_hash == st.stateHash && old.params_hash == params.hash) continue
                val row = ExerciseModelStateEntity(
                    id = id, user_id = userId, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
                    app_version = ctx.appVersion, model = st.model, exercise_id = st.exerciseId, side = st.side, class_key = st.classKey,
                    module_version = st.module.version.toString(), engine_version = EngineVersion.ENGINE_VERSION, params_hash = params.hash,
                    state = JsonText.canonical(st.state), state_hash = st.stateHash, n_observations = st.nObservations,
                    as_of = st.asOf.toEpochMilli(), as_of_session_id = st.asOfSessionId,
                )
                db.modelStates().upsert(row)
                outbox.enqueue("exercise_model_state", id, nowMs)
                written++
            }
            for ((id, old) in existing) {
                if (id in produced) continue
                db.modelStates().upsert(old.copy(deleted_at = nowMs, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion))
                outbox.enqueue("exercise_model_state", id, nowMs)
                retired++
            }
        }
        if (added + written + retired > 0) scheduler.request()
        Report(sessions.size, deferred, added, written, retired)
    }

    /** The newest stored row per `kind|scope_key|session_id`: the one no other row of the group supersedes. */
    private fun heads(links: List<dev.fitnessapp.data.db.dao.SnapshotLink>): Map<String, String> {
        val out = TreeMap<String, String>()
        for ((key, group) in links.groupBy { it.kind + "|" + it.scope_key + "|" + it.session_id }) {
            val superseded = group.mapNotNull { it.supersedes_id }.toSet()
            group.filter { it.id !in superseded }.maxByOrNull { it.id }?.let { out[key] = it.id }
        }
        return out
    }

    /** The finished sessions in replay order, cut before a challenge session still waiting for its completion. */
    private suspend fun replayable(nowMs: Long): Pair<List<WorkoutSessionEntity>, String?> {
        val all = db.sessions().finishedChronological()
        val out = ArrayList<WorkoutSessionEntity>()
        for (s in all) {
            if (s.enrollment_id != null && s.challenge_slot != null) {
                val types = db.challengeEvents().bySourceSession(s.id).map { it.event_type }.toSet()
                val needed = if (s.kind == "test") setOf(EventTypes.SESSION_COMPLETED, EventTypes.TEST_COMPLETED) else setOf(EventTypes.SESSION_COMPLETED)
                val waiting = nowMs - (s.finished_at ?: nowMs) < completionWaitMs
                if (!types.containsAll(needed) && waiting) return out to s.id
            }
            out += s
        }
        return out to null
    }

    private suspend fun finished(s: WorkoutSessionEntity): FinishedSession {
        val sets = db.setLogs().bySession(s.id)
        val dynamic = ArrayList<LoggedSet>()
        val holds = ArrayList<HoldSet>()
        for (r in sets) {
            val ex = catalog.byId(r.exercise_id)
            val contraction = ex?.contraction ?: "dynamic"
            if (contraction == "isometric" || contraction == "hang") {
                holds += hold(r) ?: continue
            } else {
                dynamic += EngineFacts.logged(r, contraction, ctx) ?: continue
            }
        }
        return FinishedSession(
            s.id, Instant.ofEpochMilli(s.started_at!!), LocalDate.parse(s.local_date), s.bodyweight_kg?.let { Scaled.toDouble(it, 2) },
            dynamic, holds,
        )
    }

    private fun hold(r: SetLogEntity): HoldSet? {
        val t = r.duration_s?.let { Scaled.toDouble(it, 1) } ?: return null
        return HoldSet(
            id = r.id,
            exerciseId = r.exercise_id,
            side = r.side,
            performedAt = Instant.ofEpochMilli(r.completed_at),
            setKind = r.set_kind,
            wSys = r.effective_load_kg?.let { Scaled.toDouble(it, 2) },
            externalKg = Scaled.toDouble(r.added_kg, 2),
            tDoneS = t,
            reserveS = r.hold_reserve_s?.let { Scaled.toDouble(it, 1) } ?: 0.0,
            reserveLowerBound = r.hold_reserve_is_lower_bound,
            torsoRotation = "torso_rotation" in r.flags,
            reasonCode = r.deviation_reason_code ?: if (r.pain_flag) "pain" else null,
            restBeforeS = r.rest_before_s?.let { Scaled.toDouble(it, 1) },
            plannedRestBeforeS = r.rest_timer_planned_s?.toDouble(),
            roundId = r.round_index?.let { "${r.session_exercise_id}:$it" },
        )
    }

    private fun holdFacts(): Map<String, HoldFacts> {
        val out = TreeMap<String, HoldFacts>()
        for (e in catalog.doc.exercises.filter { it.contraction == "isometric" || it.contraction == "hang" }) {
            val id = catalog.idOf(e)
            out[id] = HoldFacts(id, e.holdClass, e.pattern, e.bwFraction)
        }
        return out
    }

    private fun stateId(userId: String, s: ModelStateRow): String =
        Ids.fitness("exercise_model_state:$userId:${s.model}:${s.exerciseId ?: "-"}:${s.side}:${s.classKey ?: "-"}").toString()

    private companion object {
        val KINDS = listOf("estimation.sse", "estimation.rcm", "estimation.hold", "estimation.rircal")
    }
}
