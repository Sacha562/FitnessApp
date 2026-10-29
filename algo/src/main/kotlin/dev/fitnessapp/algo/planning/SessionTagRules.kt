package dev.fitnessapp.algo.planning

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One rule of the challenge's `integration.sessionTagRules` (ALGORITHMS_PLANNING 0.3, C11): a session carries [tag]
 * when its hard sets for [groups] reach [minHardSets], or (grip) when it has [minHardSets] hard sets of exercises with
 * grip demand of at least [minGripDemand]. [orJointStress] is the exercise-level alternative (any exercise with that
 * joint stress), which ELIG-1 enforces through the derived tags.
 */
data class SessionTagRule(
    val tag: String,
    val groups: List<String>,
    val minHardSets: Double,
    val minGripDemand: Int? = null,
    val orJointStress: Map<String, Int> = emptyMap(),
) {
    /** A group-count rule: the allocator and SEL-1 cap it by FS. */
    val countsGroups: Boolean get() = groups.isNotEmpty()

    /** Whether per-group hard-set credit [credit] reaches the rule (group-count rules only). */
    fun reached(credit: Map<String, Double>): Boolean = countsGroups && groups.sumOf { credit[it] ?: 0.0 } >= minHardSets - 1e-9

    companion object {
        /** The rules of a `sessionTagRules` object, sorted by tag; keys that are not rule objects (`tag`, `note`) are skipped. */
        fun parse(rules: JsonObject): List<SessionTagRule> = rules.entries
            .filter { (_, v) -> v is JsonObject && v.jsonObject.containsKey("minHardSets") }
            .map { (tag, v) ->
                val o = v.jsonObject
                SessionTagRule(
                    tag = tag,
                    groups = o["groups"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                    minHardSets = o.getValue("minHardSets").jsonPrimitive.content.toDouble(),
                    minGripDemand = (o["minGripDemand"] as? JsonPrimitive)?.content?.toInt(),
                    orJointStress = o["orJointStress"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content.toInt() }?.toSortedMap().orEmpty(),
                )
            }
            .sortedBy { it.tag }
    }
}
