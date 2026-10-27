package dev.fitnessapp.data.engine

import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.estimation.CurveClass
import dev.fitnessapp.algo.estimation.ExerciseFacts
import dev.fitnessapp.algo.estimation.LoggedSet
import dev.fitnessapp.algo.pipeline.Transfer
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.ParamsSnapshotEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.repo.CatalogRepository
import dev.fitnessapp.data.repo.WriteContext
import java.time.Instant
import java.util.TreeMap

/** The facts both engine runners read the same way: a logged set as the estimators see it, catalog facts, transfers. */
internal object EngineFacts {
    fun logged(r: SetLogEntity, contraction: String, ctx: WriteContext): LoggedSet? {
        val mode = runCatching { LoadMode.of(r.load_mode) }.getOrNull() ?: return null
        val at = Instant.ofEpochMilli(r.completed_at)
        return LoggedSet(
            id = r.id,
            sessionId = r.session_id,
            exerciseId = r.exercise_id,
            side = r.side,
            performedAt = at,
            localDate = Week.localDate(at, ctx.zone()),
            setKind = r.set_kind,
            contraction = contraction,
            loadMode = mode,
            wSys = r.effective_load_kg?.let { Scaled.toDouble(it, 2) },
            reps = r.reps ?: 0,
            rirReported = r.rir_reported?.let { Scaled.toDouble(it, 1) },
            rirLowerBound = r.rir_is_lower_bound,
            failedRep = "failed_rep" in r.flags,
            reasonCode = r.deviation_reason_code ?: if (r.pain_flag) "pain" else null,
            calibExtraReps = r.calib_extra_reps,
            targetReps = r.planned_reps,
            qStar = r.planned_rir?.let { Scaled.toDouble(it, 1) },
            restBeforeS = r.rest_before_s?.let { Scaled.toDouble(it, 1) },
            plannedRestBeforeS = r.rest_timer_planned_s?.toDouble(),
        )
    }

    fun facts(catalog: CatalogRepository, model: Catalog, e: CatalogExercise): ExerciseFacts {
        val groups = TreeMap<String, Double>()
        model.taxonomy.activeGroups.forEach { g -> model.groupCoeff(e, g.id).takeIf { it > 0.0 }?.let { groups[g.id] = it } }
        return ExerciseFacts(catalog.idOf(e), e.curveClass?.let { CurveClass.of(it) }, e.bwFraction, e.implementBaseKg ?: 0.0, groups)
    }

    fun exerciseFacts(catalog: CatalogRepository, model: Catalog): Map<String, ExerciseFacts> {
        val out = TreeMap<String, ExerciseFacts>()
        for (e in catalog.doc.exercises) out[catalog.idOf(e)] = facts(catalog, model, e)
        return out
    }

    fun transfers(catalog: CatalogRepository): List<Transfer> = catalog.doc.transferRatios.mapNotNull { t ->
        val from = catalog.bySlug(t.fromSlug) ?: return@mapNotNull null
        val to = catalog.bySlug(t.toSlug) ?: return@mapNotNull null
        Transfer(catalog.idOf(from), catalog.idOf(to), t.ratio)
    }

    fun paramsSnapshot(ctx: WriteContext, userId: String, params: ParamView, paramsVersion: String): ParamsSnapshotEntity = ParamsSnapshotEntity(
        id = Ids.fitness("params_snapshots:$userId:${params.hash}").toString(),
        user_id = userId, device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
        params_hash = params.hash, params_version = paramsVersion, params = Jcs.canonicalize(params.values()),
    )
}
