package dev.fitnessapp.data.repo

import androidx.room.withTransaction
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.targets.GoalPresets
import dev.fitnessapp.algo.targets.GroupGoal
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.entity.MuscleGoalEntity
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.sync.SyncScheduler
import dev.fitnessapp.data.telemetry.EventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The per-group muscle goals (D6, UX 8.9 and 15.4): one `muscle_goals` row per group with a UUIDv5 id (DATA_MODEL
 * 9.3.5), so two devices converge on the same row. A preset writes all 18 groups; a single change marks the goals
 * `custom`. Each changed group logs `goal_changed`. Before the first save there are no rows and `targets` reports
 * `setup_needed` (D55: the Today card "Set your goals" stays until then).
 */
class GoalRepository(
    private val db: FitnessDatabase,
    private val outbox: OutboxWriter,
    private val telemetry: TelemetryWriter,
    private val ctx: WriteContext,
    private val scheduler: SyncScheduler,
    private val profiles: ProfileRepository,
) {
    /** group -> goal of the saved rows; empty until the goals are saved. */
    fun observe(): Flow<Map<String, GroupGoal>> = db.muscleGoals().observeLive().map(::goals)

    suspend fun current(): Map<String, GroupGoal> = goals(db.muscleGoals().live())

    /** Applies a preset of `goal-presets` to all 18 groups (`source = preset`). */
    suspend fun applyPreset(presets: GoalPresets, presetId: String) {
        val goals = presets.goals(presetId)
        db.withTransaction {
            for (g in Groups.CANONICAL) write(g, goals.getValue(g), "preset", presetId, "preset")
        }
        profiles.setGoalPreset(presetId)
        scheduler.request()
    }

    /**
     * One group's goal (`source = user`); the other groups keep theirs, and a group without a row yet starts from
     * [baseline] (the goals on screen), so the first manual change saves all 18 groups.
     */
    suspend fun set(group: String, goal: GroupGoal, baseline: Map<String, GroupGoal>) {
        require(!goal.focus || goal.goal == "improve") { "focus needs improve" }
        db.withTransaction {
            val saved = current()
            for (g in Groups.CANONICAL) {
                when {
                    g == group -> write(g, goal, "user", CUSTOM, "manual")
                    g !in saved -> baseline[g]?.let { write(g, it, "user", CUSTOM, "manual") }
                }
            }
        }
        profiles.setGoalPreset(CUSTOM)
        scheduler.request()
    }

    /** Writes one row when it changes and logs `goal_changed`. */
    private suspend fun write(group: String, goal: GroupGoal, source: String, preset: String, via: String) {
        val uid = ctx.requireUser()
        val id = idOf(uid, group)
        val old = db.muscleGoals().get(id)?.takeIf { it.deleted_at == null }
        if (old != null && old.goal == goal.goal && old.focus == goal.focus && old.preset_code == preset) return
        val row = (old ?: MuscleGoalEntity(id = id, user_id = uid, client_updated_at = 0, device_id = "", app_version = "", muscle_group_id = group, goal = goal.goal))
            .copy(
                goal = goal.goal, focus = goal.focus, source = source, preset_code = preset, deleted_at = null,
                client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(), app_version = ctx.appVersion,
            )
        db.muscleGoals().upsert(row)
        outbox.enqueue("muscle_goals", id, ctx.nowMs())
        if (old != null && old.goal == goal.goal && old.focus == goal.focus) return
        telemetry.write(
            EventDraft(
                type = "goal_changed", entityType = "none", screen = "you.preferences",
                payload = mapOf(
                    "group" to JsonPrimitive(group), "from" to (old?.goal?.let(::JsonPrimitive) ?: JsonNull), "to" to JsonPrimitive(goal.goal),
                    "focus_from" to JsonPrimitive(old?.focus ?: false), "focus_to" to JsonPrimitive(goal.focus), "via" to JsonPrimitive(via),
                    "preset_id" to (if (via == "preset") JsonPrimitive(preset) else JsonNull),
                ),
            ),
        )
    }

    private fun goals(rows: List<MuscleGoalEntity>): Map<String, GroupGoal> =
        rows.associateTo(sortedMapOf()) { it.muscle_group_id to GroupGoal(it.goal, it.focus) }

    companion object {
        const val CUSTOM = "custom"

        fun idOf(userId: String, group: String): String = Ids.fitness("muscle_goals:$userId:$group").toString()
    }
}
