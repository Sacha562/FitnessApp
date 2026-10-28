package dev.fitnessapp.algo.targets

import dev.fitnessapp.algo.common.Groups
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.TreeMap

/** A group's goal (D6): `improve`, `maintain` or `skip`, with an optional focus (improve only). */
data class GroupGoal(val goal: String, val focus: Boolean = false)

/**
 * The goal presets of ALGORITHMS_PLANNING 5.6 (D6), bundled as `fitness/presets/goal-presets-<version>.json`.
 * `custom` is not a preset: it is the user's own per-group choice.
 */
class GoalPresets(private val presets: Map<String, Map<String, GroupGoal>>, val version: String) {
    val ids: List<String> get() = presets.keys.sorted()

    /** The 18 group goals of [preset], in canonical order. */
    fun goals(preset: String): Map<String, GroupGoal> = requireNotNull(presets[preset]) { "unknown goal preset $preset" }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): GoalPresets {
            val doc = json.decodeFromString(PresetFile.serializer(), text)
            val out = TreeMap<String, Map<String, GroupGoal>>()
            doc.presets.forEach { (id, p) ->
                val listed = p.improve + p.maintain + p.skip
                require(listed.toSet() == Groups.CANONICAL.toSet() && listed.size == Groups.CANONICAL.size) { "preset $id must list each group once" }
                require(p.focus.all { it in p.improve }) { "preset $id: focus groups must be improve groups" }
                out[id] = Groups.CANONICAL.associateWithTo(linkedMapOf()) { g ->
                    when (g) {
                        in p.improve -> GroupGoal("improve", g in p.focus)
                        in p.maintain -> GroupGoal("maintain")
                        else -> GroupGoal("skip")
                    }
                }
            }
            return GoalPresets(out, doc.presetsVersion)
        }
    }

    @Serializable
    private data class PresetFile(
        @kotlinx.serialization.SerialName("presets_version") val presetsVersion: String,
        val presets: Map<String, Preset>,
    )

    @Serializable
    private data class Preset(val improve: List<String>, val maintain: List<String>, val skip: List<String>, val focus: List<String> = emptyList())
}
