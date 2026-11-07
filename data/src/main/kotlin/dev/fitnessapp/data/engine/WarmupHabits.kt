package dev.fitnessapp.data.engine

import dev.fitnessapp.data.db.FitnessDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * D107: notices when the user skips all warm-ups of an exercise, or does fewer than planned, in two sessions in a row,
 * so the Session Summary can offer to plan that count from now on. It never changes anything by itself.
 */
class WarmupHabits(private val db: FitnessDatabase, private val prescriptions: PrescriptionRunner) {
    /** One session's warm-ups of an exercise: planned by WARMUP-1 (after any stored count) and done. */
    data class Obs(val planned: Int, val done: Int)

    /** The offer: plan [count] warm-up sets (0 = none) for [exerciseId]. */
    data class Proposal(val exerciseId: String, val count: Int, val skippedAll: Boolean)

    /** The first exercise of [sessionId] (in session order) with an offer, or null. */
    suspend fun proposal(sessionId: String): Proposal? {
        val sets = db.setLogs().bySession(sessionId)
        val answered = keptAnswers()
        val ses = db.sessionExercises().bySession(sessionId)
            .filter { it.challenge_item_key == null && it.deleted_at == null && it.recommendation_id != null }
            .sortedBy { it.position }
        for (se in ses) {
            val planned = prescriptions.get(se.recommendation_id!!)?.warmup?.size ?: continue
            val mine = sets.filter { it.session_exercise_id == se.id }
            if (planned == 0 || mine.none { it.set_kind != "warmup" }) continue
            val today = Obs(planned, mine.count { it.set_kind == "warmup" })
            val previous = previous(se.exercise_id, sessionId) ?: continue
            val stored = db.exercisePrefs().forExercise(se.exercise_id)?.warmup_sets
            val k = propose(today, previous, stored, answered[se.exercise_id].orEmpty()) ?: continue
            return Proposal(se.exercise_id, k, k == 0)
        }
        return null
    }

    /** The newest earlier session of [exerciseId] with working sets and planned warm-ups, outside a challenge item. */
    private suspend fun previous(exerciseId: String, sessionId: String): Obs? {
        val rows = db.setLogs().historyForExercise(exerciseId, sessionId, HISTORY_LIMIT)
        val bySe = rows.groupBy { it.set.session_exercise_id }
        val order = rows.sortedWith(compareByDescending<dev.fitnessapp.data.db.dao.SetWithSessionStart> { it.session_started_at ?: it.set.completed_at })
            .map { it.set.session_exercise_id }.distinct()
        val ses = db.sessionExercises().byIds(order).associateBy { it.id }
        for (id in order) {
            val se = ses[id] ?: continue
            if (se.challenge_item_key != null) continue
            val planned = se.recommendation_id?.let { prescriptions.get(it) }?.warmup?.size ?: 0
            if (planned == 0) continue
            val mine = bySe.getValue(id).map { it.set }
            if (mine.none { it.set_kind != "warmup" }) continue
            return Obs(planned, mine.count { it.set_kind == "warmup" })
        }
        return null
    }

    /** exercise id -> the counts the user answered "Keep" to (`prompt_answered`, `value_code` `keep_<k>`). */
    private suspend fun keptAnswers(): Map<String, Set<Int>> =
        db.events().ofTypes(listOf("prompt_answered")).mapNotNull { e ->
            val o = runCatching { Json.parseToJsonElement(e.payload).jsonObject }.getOrNull() ?: return@mapNotNull null
            if (o["prompt"]?.jsonPrimitive?.contentOrNull != PROMPT) return@mapNotNull null
            val k = o["value_code"]?.jsonPrimitive?.contentOrNull?.removePrefix("keep_")?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }?.toInt()
            val id = e.entity_id
            if (k == null || id == null) null else id to k
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

    companion object {
        /** The prompt id in `prompt_shown` / `prompt_answered` (D107). */
        const val PROMPT = "warmup_sets"
        private const val HISTORY_LIMIT = 120

        /**
         * D107 (2): none done twice in a row -> 0; fewer than planned twice in a row -> the higher of the two counts.
         * No offer when the stored count is already that low, or when the user kept the plan for that count before.
         */
        fun propose(today: Obs, previous: Obs, stored: Int?, kept: Set<Int>): Int? {
            val k = when {
                today.done == 0 && previous.done == 0 -> 0
                today.done < today.planned && previous.done < previous.planned -> maxOf(today.done, previous.done)
                else -> return null
            }
            if (stored != null && stored <= k) return null
            if (k in kept) return null
            return k
        }
    }
}
