package dev.fitnessapp.app.ui.live

import dev.fitnessapp.algo.challenge.contract.ContractBuilder
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.prescription.warnings.ConstraintFacts
import dev.fitnessapp.algo.prescription.warnings.Warning
import dev.fitnessapp.algo.prescription.warnings.Warnings
import dev.fitnessapp.algo.prescription.warnings.WindowHit
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.challenges.ChallengeWeek
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate

/**
 * WE-S4 for the exercises the user added to a session (B15.2, C11, C12, C50, D5): the active challenge's week plan says
 * whether the day is the rest day before B or TEST and which session tags a gap day keeps out; its contract gives the
 * hard protected windows and the add-on limits of a challenge session. Session tags follow the definition's
 * `sessionTagRules` over the session's general exercises and their planned sets (CHALLENGE_ENGINE 8.3).
 */
object ChallengeConstraints {
    private val PULL_FAMILY = listOf("lats", "upper_back", "biceps", "forearms")

    suspend fun warnings(c: AppContainer, session: WorkoutSessionEntity, items: List<ExerciseItem>): List<Warning> {
        val added = items.filter { it.se.challenge_item_key == null && it.se.status != "removed" }
        if (added.isEmpty()) return emptyList()
        val enrollment = c.challenges.open().firstOrNull() ?: return emptyList()
        val f = c.challenges.folded(enrollment.id)
        if (f.state.status != EnrollmentState.ACTIVE) return emptyList()
        val p = c.params.fork()
        val at = Instant.ofEpochMilli(session.started_at ?: System.currentTimeMillis())
        val date = LocalDate.parse(session.local_date)
        val (_, plan) = ChallengeWeek.plan(c, f, c.challengeFacts.facts(f.def, f.state), at)
        val contract = ContractBuilder.build(f.def, c.params, f.state, plan, AppContainer.CHANGEOVER_MIN)
        val restDay = plan.blockedDays.any { it.date == date && it.scope == "all_gym" }
        val challengeDay = session.challenge_slot != null || plan.sessions.any { it.date == date }
        val gapTags = plan.blockedDays.filter { it.date == date && it.scope == "tags" }.flatMap { it.tags }.distinct()
        val windows = contract.protectedWindows.filter { it.mode == "hard" && !at.isBefore(it.from) && at.isBefore(it.to) }
            .map { WindowHit(it.id, it.groups, it.regions, it.sessionTags, it.scope == "all_gym") }
        // The add-on block of a challenge session (D5, C34): its forbidden groups and tags bind like a hard window.
        val addOn = session.challenge_slot?.let { slot -> contract.reservedSessions.firstOrNull { it.slot == slot && it.date == date }?.addOnSlot }
            ?.let { a -> WindowHit("addon.${session.challenge_slot}", a.forbidGroups, emptyList(), a.forbidSessionTags, allGym = false) }
        val tags = sessionTags(c, added, contract.sessionTagRules)
        val maxGrip = p.int("challenge.contract.gap_day_pull_max_grip_demand")
        val bicepsBelow = p.double("challenge.contract.gap_day_pull_biceps_coef_below")
        val out = ArrayList<Warning>()
        for (item in added) {
            val groups = groups(c, item.ex)
            val facts = ConstraintFacts(
                restDayBefore = restDay, hardWindows = windows + listOfNotNull(addOn),
                gapDay = !challengeDay, gapForbiddenTags = gapTags, sessionTags = tags, groups = groups,
                regions = item.ex.jointStress.filterValues { it >= 2 }.keys.toList(),
                pullFamily = PULL_FAMILY.any { (groups[it] ?: 0.0) >= CREDIT_COEFF },
                lightPull = item.ex.gripDemand <= maxGrip && (groups["biceps"] ?: 0.0) < bicepsBelow,
            )
            out += Warnings.constraint("session_exercise:${item.se.id}", facts)
        }
        return out
    }

    private fun groups(c: AppContainer, ex: CatalogExercise): Map<String, Double> =
        c.catalogModel.taxonomy.activeGroups.associate { it.id to c.catalogModel.groupCoeff(ex, it.id) }.filterValues { it > 0.0 }

    /** The session tags of the general exercises (planned sets times the group credit, C44), per the definition's rules. */
    private fun sessionTags(c: AppContainer, items: List<ExerciseItem>, rules: JsonObject): Set<String> {
        val out = HashSet<String>()
        fun sets(i: ExerciseItem) = maxOf(i.planned, i.workingDone).toDouble()
        for ((tag, rule) in rules) {
            val r = runCatching { rule.jsonObject }.getOrNull() ?: continue
            val min = r["minHardSets"]?.jsonPrimitive?.intOrNull ?: continue
            val groups = r["groups"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val credit = if (groups.isNotEmpty()) {
                items.sumOf { i -> val g = groups(c, i.ex); sets(i) * (groups.maxOfOrNull { g[it] ?: 0.0 }?.takeIf { it >= CREDIT_COEFF } ?: 0.0) }
            } else {
                val grip = r["minGripDemand"]?.jsonPrimitive?.intOrNull ?: continue
                items.filter { it.ex.gripDemand >= grip }.sumOf { sets(it) }
            }
            val joint = r["orJointStress"]?.jsonObject?.any { (k, v) -> items.any { (it.ex.jointStress[k] ?: 0) >= (v.jsonPrimitive.intOrNull ?: Int.MAX_VALUE) } } == true
            if (credit >= min || joint) out += tag
        }
        return out
    }

    /** A group coefficient that earns set credit (C44). */
    private const val CREDIT_COEFF = 0.5
}
