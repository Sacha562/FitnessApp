package dev.fitnessapp.app.ui.live

import dev.fitnessapp.algo.challenge.cds.ProtectedWindowSpec
import dev.fitnessapp.algo.challenge.contract.ContractBuilder
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.prescription.warnings.ConstraintFacts
import dev.fitnessapp.algo.prescription.warnings.Warning
import dev.fitnessapp.algo.prescription.warnings.Warnings
import dev.fitnessapp.algo.prescription.warnings.WindowHit
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.algo.selection.elig.AddOnFilter
import dev.fitnessapp.algo.selection.elig.EligContext
import dev.fitnessapp.algo.selection.elig.HardWindow
import dev.fitnessapp.algo.selection.elig.LightPullRule
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.challenges.ChallengeWeek
import dev.fitnessapp.app.ui.challenges.HabitDays
import dev.fitnessapp.data.challenge.HabitExercise
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

    /** What the active challenge says about a session's day and time (C11, C12, C33, C34); null without an active one. */
    class Day(
        val contract: dev.fitnessapp.algo.challenge.contract.ChallengeContract,
        val restDay: Boolean,
        val challengeDay: Boolean,
        val gapTags: List<String>,
        val windows: List<WindowHit>,
        val addOnSlot: dev.fitnessapp.algo.challenge.contract.AddOnSlot?,
        val slot: String?,
        /** The definition's protected windows by id, for the warning text. */
        val windowSpecs: Map<String, ProtectedWindowSpec> = emptyMap(),
        /** The habits due on the session's day with their exercise and dose (D59). */
        val habits: List<HabitExercise> = emptyList(),
    )

    suspend fun day(c: AppContainer, session: WorkoutSessionEntity): Day? = day(
        c, Instant.ofEpochMilli(session.started_at ?: System.currentTimeMillis()), LocalDate.parse(session.local_date),
        session.challenge_slot, session.habit_id,
    )

    /** [day] for a session that does not exist yet (the New session sheet, D62): its start [at], [date] and [slot]. */
    suspend fun day(c: AppContainer, at: Instant, date: LocalDate, slot: String?, habitId: String? = null): Day? {
        val enrollment = c.challenges.open().firstOrNull() ?: return null
        val f = c.challenges.folded(enrollment.id)
        if (f.state.status != EnrollmentState.ACTIVE) return null
        val (input, plan) = ChallengeWeek.plan(c, f, c.challengeFacts.facts(f.def, f.state), at)
        val contract = ContractBuilder.build(f.def, c.params, f.state, plan, AppContainer.CHANGEOVER_MIN)
        val habits = if (slot == null && habitId == null) {
            runCatching { c.challengeSessions.habitExercises(HabitDays.of(c, f, date, input, plan)) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return Day(
            contract,
            restDay = plan.blockedDays.any { it.date == date && it.scope == "all_gym" },
            challengeDay = slot != null || plan.sessions.any { it.date == date },
            gapTags = plan.blockedDays.filter { it.date == date && it.scope == "tags" }.flatMap { it.tags }.distinct(),
            windows = contract.protectedWindows.filter { it.mode == "hard" && !at.isBefore(it.from) && at.isBefore(it.to) }
                .map { WindowHit(it.id, it.groups, it.regions, it.sessionTags, it.scope == "all_gym") },
            addOnSlot = slot?.let { sl -> contract.reservedSessions.firstOrNull { it.slot == sl && it.date == date }?.addOnSlot },
            slot = slot,
            windowSpecs = f.def.doc.integration.protectedWindows.associateBy { it.id },
            habits = habits,
        )
    }

    /** WE-S4 notices with their copy (keyed by [textKey]): the window or rule and what of the exercise it keeps out. */
    class Checked(val warnings: List<Warning>, val texts: Map<String, String>)

    fun textKey(w: Warning): String = w.entity + "|" + w.suggestion

    /**
     * ELIG-1's constraints for a general exercise of [session] (12.2, D53): stations, preferences, the contract's joint
     * flags and forbidden tags, the gap day's session tags and light pull (C50), the hard windows and the add-on slot.
     */
    suspend fun eligContext(c: AppContainer, session: WorkoutSessionEntity, prefs: Map<String, dev.fitnessapp.algo.selection.pref.PrefValue>): EligContext =
        eligContext(c, day(c, session), prefs)

    /** [eligContext] from a [Day] already read (null without an active challenge). */
    suspend fun eligContext(c: AppContainer, d: Day?, prefs: Map<String, dev.fitnessapp.algo.selection.pref.PrefValue>): EligContext {
        val stations = c.selection.stations()
        if (d == null) return EligContext(availableStations = stations, prefs = prefs)
        val p = c.params.fork()
        return EligContext(
            availableStations = stations, prefs = prefs, jointFlags = d.contract.jointFlags,
            forbidExerciseTags = d.contract.forbiddenExerciseTags,
            gapDay = !d.challengeDay, forbiddenSessionTags = d.gapTags,
            hardWindows = d.windows.filter { !it.allGym }.map { HardWindow(it.id, it.groups, it.regions) },
            lightPull = LightPullRule(p.int("challenge.contract.gap_day_pull_max_grip_demand"), p.double("challenge.contract.gap_day_pull_biceps_coef_below")),
            addOn = d.addOnSlot?.let { AddOnFilter(it.allowPatterns, it.maxGripDemand, it.forbidGroups, emptyList()) },
        )
    }

    /**
     * WE-S4 for the general exercises of [session]. A due habit's exercise within the habit's dose is that habit (D59):
     * it is checked like the habit, which the challenge plans itself, so neither its own window check nor its sets
     * toward a session tag apply. A session tag is charged only to the exercises that contribute to it: three
     * pressing exercises do not become grip-heavy because a hang made the session so.
     */
    suspend fun warnings(c: AppContainer, session: WorkoutSessionEntity, items: List<ExerciseItem>): Checked {
        val none = Checked(emptyList(), emptyMap())
        val general = items.filter { it.se.challenge_item_key == null && it.se.status != "removed" }
        if (general.isEmpty()) return none
        val d = day(c, session) ?: return none
        val added = general.filterNot { habitDose(it, general, d.habits) }
        if (added.isEmpty()) return none
        val p = c.params.fork()
        val contract = d.contract
        // The add-on block of a challenge session (D5, C34): its forbidden groups and tags bind like a hard window.
        val addOn = d.addOnSlot?.let { a -> WindowHit("addon.${d.slot}", a.forbidGroups, emptyList(), a.forbidSessionTags, allGym = false) }
        val windows = d.windows + listOfNotNull(addOn)
        val tags = sessionTags(c, added, contract.sessionTagRules)
        val maxGrip = p.int("challenge.contract.gap_day_pull_max_grip_demand")
        val bicepsBelow = p.double("challenge.contract.gap_day_pull_biceps_coef_below")
        val out = ArrayList<Warning>()
        val texts = HashMap<String, String>()
        for (item in added) {
            val groups = groups(c, item.ex)
            val regions = item.ex.jointStress.filterValues { it >= 2 }.keys.toList()
            val own = tags.filter { contributes(c, item, contract.sessionTagRules[it]) }.toSet()
            val facts = ConstraintFacts(
                restDayBefore = d.restDay, hardWindows = windows,
                gapDay = !d.challengeDay, gapForbiddenTags = d.gapTags, sessionTags = own, groups = groups, regions = regions,
                pullFamily = PULL_FAMILY.any { (groups[it] ?: 0.0) >= CREDIT_COEFF },
                lightPull = item.ex.gripDemand <= maxGrip && (groups["biceps"] ?: 0.0) < bicepsBelow,
            )
            for (w in Warnings.constraint("session_exercise:${item.se.id}", facts)) {
                out += w
                val id = w.suggestion?.removePrefix("rule_override:") ?: continue
                val hit = windows.firstOrNull { it.id == id } ?: continue
                windowText(c, item.ex.nameEn, hit, d.windowSpecs[id], groups, regions, own)?.let { texts[textKey(w)] = it }
            }
        }
        return Checked(out, texts)
    }

    /** D59: [item] is a due habit's exercise and the session's sets of it stay within the habit's dose. */
    private fun habitDose(item: ExerciseItem, items: List<ExerciseItem>, habits: List<HabitExercise>): Boolean {
        val h = habits.firstOrNull { it.exerciseId == item.se.exercise_id } ?: return false
        return items.filter { it.se.exercise_id == h.exerciseId }.sumOf { sets(it) } <= h.maxRows
    }

    /** "<name>: <what> within <n> h before|after session <slot>; the challenge keeps this out." from the definition. */
    private fun windowText(
        c: AppContainer, name: String, hit: WindowHit, spec: ProtectedWindowSpec?, groups: Map<String, Double>, regions: List<String>, tags: Set<String>,
    ): String? {
        spec ?: return null
        val slots = spec.slots.joinToString(" or ") { if (it == "TEST") "a test day" else "session $it" }
        val window = "within ${spec.hours} h ${spec.relation} $slots"
        if (hit.allGym) return "$name: no gym training $window."
        val labels = c.catalogModel.taxonomy
        val what = hit.groups.filter { (groups[it] ?: 0.0) >= CREDIT_COEFF }.map { g -> labels.activeGroups.firstOrNull { it.id == g }?.labelEn ?: g } +
            hit.regions.filter { it in regions }.map { r -> labels.regions.firstOrNull { it.id == r }?.labelEn ?: r.replace('_', ' ') } +
            hit.sessionTags.filter { it in tags }.map { it.replace('_', '-') + " work" }
        if (what.isEmpty()) return null
        return "$name: ${what.distinct().joinToString(", ")} $window; the challenge keeps this out."
    }

    private fun groups(c: AppContainer, ex: CatalogExercise): Map<String, Double> =
        c.catalogModel.taxonomy.activeGroups.associate { it.id to c.catalogModel.groupCoeff(ex, it.id) }.filterValues { it > 0.0 }

    private fun sets(i: ExerciseItem) = maxOf(i.planned, i.workingDone).toDouble()

    /** The session tags of the general exercises (planned sets times the group credit, C44), per the definition's rules. */
    private fun sessionTags(c: AppContainer, items: List<ExerciseItem>, rules: JsonObject): Set<String> {
        val out = HashSet<String>()
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

    /** Whether [item] counts toward a session tag under its [rule]: the rule's group credit, grip demand or joint stress. */
    private fun contributes(c: AppContainer, item: ExerciseItem, rule: kotlinx.serialization.json.JsonElement?): Boolean {
        val r = runCatching { rule?.jsonObject }.getOrNull() ?: return false
        val groups = r["groups"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val g = groups(c, item.ex)
        val byGroup = groups.isNotEmpty() && groups.any { (g[it] ?: 0.0) >= CREDIT_COEFF }
        val byGrip = groups.isEmpty() && r["minGripDemand"]?.jsonPrimitive?.intOrNull?.let { item.ex.gripDemand >= it } == true
        val byJoint = r["orJointStress"]?.jsonObject?.any { (k, v) -> (item.ex.jointStress[k] ?: 0) >= (v.jsonPrimitive.intOrNull ?: Int.MAX_VALUE) } == true
        return byGroup || byGrip || byJoint
    }

    /** A group coefficient that earns set credit (C44). */
    private const val CREDIT_COEFF = 0.5
}
