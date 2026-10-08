package dev.fitnessapp.data.repo

import androidx.room.withTransaction
import dev.fitnessapp.algo.core.bodyweight.BwObservation
import dev.fitnessapp.algo.core.bodyweight.BwSession
import dev.fitnessapp.algo.core.bodyweight.BwSnapshot
import dev.fitnessapp.algo.core.bodyweight.Bodyweight
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.MetricObservationEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/** Weigh-ins (`metric_observations`, metric `bw`) and the BW-1 session snapshot (C32). */
class BodyweightRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
) {
    fun observeLatest(): Flow<MetricObservationEntity?> = db.metrics().observeLatest("bw")

    suspend fun logWeighIn(kg: Double, sessionId: String? = null): String {
        val uid = ctx.requireUser()
        val now = ctx.nowMs()
        val id = ctx.ids.next()
        db.withTransaction {
            db.metrics().upsert(
                MetricObservationEntity(
                    id = id, user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
                    app_version = ctx.appVersion, metric_code = "bw", value_num = Scaled.fromDouble(kg, 3), unit = "kg",
                    observed_at = now, local_date = Instant.ofEpochMilli(now).atZone(ctx.zone()).toLocalDate().toString(),
                    source = "manual", session_id = sessionId,
                ),
            )
            outbox.enqueue("metric_observations", id, now)
        }
        scheduler.request()
        return id
    }

    suspend fun observations(): List<BwObservation> = db.metrics().byMetric("bw").mapNotNull { m ->
        m.value_num?.let { v ->
            BwObservation(m.id, Instant.ofEpochMilli(m.observed_at), LocalDate.parse(m.local_date), Scaled.toDouble(v, 3), m.session_id)
        }
    }

    suspend fun snapshot(session: BwSession, fallbackKg: Double?, params: ParamView): BwSnapshot =
        Bodyweight.sessionSnapshot(session, observations(), fallbackKg, params)
}
