package dev.fitnessapp.algo.planning.allocator

import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.planning.SessionTagRule
import dev.fitnessapp.algo.targets.GoalPresets
import dev.fitnessapp.algo.targets.Targets
import dev.fitnessapp.algo.targets.TargetsInput
import dev.fitnessapp.algo.targets.WeeklyTargets
import dev.fitnessapp.algo.testing.TestCatalog
import java.time.Instant
import java.time.LocalDate

/** The W41 worked example's planning inputs (ALGORITHMS_PLANNING 21.3-21.8), shared by the allocator and selection tests. */
object W41Support {
    val presets: GoalPresets by lazy { GoalPresets.parse(TestCatalog.resource("presets/goal-presets-1.0.0.json")) }

    /** Done + committed Vhyp before any general session (21.4). */
    val vBefore: Map<String, Double> = mapOf("rear_delts" to 1.80, "upper_back" to 1.85, "abs" to 4.42, "lats" to 11.30, "biceps" to 5.43, "forearms" to 11.58)

    val owned = listOf("lats", "biceps", "forearms")

    /** The OAP's group-count session tag rules (0.3). */
    val tagRules = listOf(
        SessionTagRule("elbow_flexor_heavy", listOf("biceps"), 3.0, orJointStress = mapOf("elbow_medial" to 2)),
        SessionTagRule("grip_heavy", emptyList(), 2.0, minGripDemand = 2),
        SessionTagRule("pull_heavy", listOf("lats", "upper_back"), 4.0),
    )

    val gapDayTags = setOf("pull_heavy", "grip_heavy", "elbow_flexor_heavy")

    fun targets(params: ParamView, trainingAge: String? = "intermediate", preset: String = "balanced"): WeeklyTargets = Targets.compute(
        TargetsInput(
            isoWeek = "2026-W41", goals = presets.goals(preset), trainingAge = trainingAge, age = null, ownedGroups = owned,
            contractActive = true, challengeDeloadWeek = true, committed = mapOf("abs" to 2.98, "rear_delts" to 0.9, "upper_back" to 0.45),
            done = mapOf("abs" to 1.45, "rear_delts" to 0.9, "upper_back" to 1.4), trainedDates = emptyMap(), today = LocalDate.of(2026, 10, 7),
        ),
        params,
    )

    /** Every group available except the ones a gap day leaves without a direct exercise (12.4, C50). */
    val gapDayAvailable: Map<String, Boolean> = Groups.CANONICAL.associateWith { it !in setOf("upper_back", "upper_traps") }

    val thuStart: Instant = Instant.parse("2026-10-08T18:36:00Z")
    val friStart: Instant = Instant.parse("2026-10-09T17:45:00Z")

    /** Readiness at Fri 19:45 with the Thursday legs add-on (21.4). */
    val friReadiness: Map<String, Double> = mapOf(
        "lats" to 0.52, "biceps" to 0.70, "forearms" to 0.50, "rear_delts" to 0.85, "upper_back" to 0.88, "abs" to 0.71,
        "quads" to 0.86, "hamstrings" to 0.85, "glutes" to 0.94, "adductors" to 0.95,
    )

    fun legsAddOnSlot() = AddOnSlot("LEGS", Groups.FAMILIES.getValue("LEGS").toSet(), owned.toSet(), maxSets = 4.0, minRir = 2)
}
