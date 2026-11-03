package dev.fitnessapp.app.format

import dev.fitnessapp.algo.prescription.warnings.Severity
import dev.fitnessapp.algo.prescription.warnings.Warning
import dev.fitnessapp.algo.prescription.warnings.Warnings
import dev.fitnessapp.app.ui.common.WhyContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Copy for WarningEngine violations (UX 17): state the number, the bound and the consequence; name the evidence level
 * in the Why sheet; never "you must". [name] is the affected exercise or group as the user knows it.
 */
object WarnTexts {
    fun text(w: Warning, name: String): String {
        val n = w.numbers
        fun num(k: String, d: Int = 0) = n[k]?.let { Numbers.num(it, d) } ?: "?"
        return when (w.ruleId) {
            "WE-S1" -> if (w.enforced) "$name: target raised to 1 rep in reserve. Safety rule: no sets to failure on this exercise."
            else "$name: 0 reps in reserve logged. Safety rule: stop at least 1 rep before failure on this exercise."
            "WE-S2" -> when {
                w.enforced -> "$name: the load was capped at ${num("w_cap", 2)} kg, the largest safe jump from last time."
                w.severity == Severity.WARN -> "$name: a bigger jump than the general cap; your program rule applies."
                else -> "$name: ${num("w", 2)} kg is above the usual jump limit of ${num("w_cap", 2)} kg from last time."
            }
            "WE-S3" -> "Pain reported twice in two weeks in this area. Consider a substitute and get it checked if it persists."
            "WE-S4" -> when {
                w.suggestion == "gap_day_light_pull" -> "$name: pull work on a challenge gap day; only light pull is planned here."
                w.suggestion?.startsWith("rule_override:rest_day_before") == true -> "No gym session the day before session B or a test day."
                w.suggestion?.startsWith("rule_override:gap_day") == true -> "$name: this makes today a pull or grip-heavy day, which the challenge keeps free."
                else -> "$name: falls in a protected window before or after a challenge session."
            }
            "WE-S5" -> "$name: the next set at this load is predicted near failure (${num("rir_next", 1)} in reserve). An easier load keeps 1 in reserve."
            "WE-P1" -> "$name: about ${num("reps")} reps per set. Above about 30, sets add little; a heavier load in your range works better."
            "WE-P2" -> "$name: aiming for ${num("q_star", 1)} in reserve. Ending sets that far from failure gives less growth; about 2 works better."
            "WE-P3" -> "$name: one set per exercise. Two sets give more stimulus for little extra time."
            "WE-P4" -> "$name: more than ${num("reps")} reps per set. Reps-in-reserve estimates are less accurate that high."
            "WE-P5" -> "$name: ${num("rep_min")}+ reps on a strength goal. Strength gains are larger with 3-5 heavy reps."
            "WE-P6" -> "$name: ${num("rest_s")} s rest, below about ${num("bound_s")} s. Short rests trade performance on the next set."
            "WE-P7" -> "$name: the rep range is narrow for the load steps. A wider range or smaller steps lets the plan progress."
            "WE-P9" -> "$name: aiming for failure. On this lift stopping 1 rep short gives nearly the same benefit."
            "WE-P10" -> "$name: grip failed ${num("events")} times in four weeks. Grip work or straps can help."
            "WE-P11" -> "$name: many reps at bodyweight for two sessions. A weighted mode or a harder variant progresses better."
            "WE-P12" -> "$name: about ${num("cap_over_q")} reps possible at your target, above the top of the range. Add weight with the dip belt to keep progressing."
            "WE-T1" -> "$name: progress has stalled. The plan suggests a change; your setting is kept."
            "WE-T2" -> "Several tired or bad days lately. Check sleep, food and stress before the next hard session."
            "WE-V1" -> "$name: about ${num("x")} hard sets per week planned, below the growth range."
            "WE-V3" -> "$name: ${num("x")} sets in one session; above about 11 adds little (preliminary evidence)."
            else -> "$name: ${w.ruleId}"
        }
    }

    fun why(w: Warning, name: String): WhyContent = WhyContent(
        title = when (w.severity) {
            Severity.SAFETY -> "Safety note"
            Severity.INFO -> "Note"
            else -> "Evidence note"
        },
        summary = text(w, name),
        numbers = buildList {
            w.numbers.forEach { (k, v) -> add("$k  " + Numbers.num(v, 2)) }
            add("Severity  " + w.severity.code + if (w.enforced) " (applied by the plan)" else "")
            w.suggestion?.let { add("Suggestion  $it") }
        },
        module = "warnings@${Warnings.ref.version} · ${w.ruleId}",
        evidence = w.evidenceGrade + if (w.citations.isNotEmpty()) " · " + w.citations.joinToString(", ") else "",
        trace = dev.fitnessapp.data.db.JsonText.canonical(
            JsonObject(
                mapOf(
                    "rule_id" to JsonPrimitive(w.ruleId), "severity" to JsonPrimitive(w.severity.code), "entity" to JsonPrimitive(w.entity),
                    "magnitude" to JsonPrimitive(w.magnitude), "enforced" to JsonPrimitive(w.enforced),
                    "citations" to JsonArray(w.citations.map { JsonPrimitive(it) }),
                ),
            ),
        ),
        recommendationId = w.entity,
        codes = listOf(w.ruleId),
    )
}
