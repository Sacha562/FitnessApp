package dev.fitnessapp.algo.challenge.materialize

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.DefinitionDoc
import dev.fitnessapp.algo.challenge.cds.RuleSpec
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentFold
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.StateJson
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.ValueJson
import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.util.SortedMap
import java.util.TreeMap

/** One item of a previewed session: [before] under the enrollment's version, [after] under the new one (null: not there). */
data class ItemChange(val key: String, val before: PlannedItem?, val after: PlannedItem?) {
    /** `added`, `removed` or `changed`. */
    val kind: String get() = when {
        before == null -> "added"
        after == null -> "removed"
        else -> "changed"
    }
}

/** A planned session of this or next week under both versions, with the items that differ. */
data class SessionDiff(val slot: String, val date: LocalDate, val before: SessionPlan, val after: SessionPlan, val changes: List<ItemChange>)

/** A rule of either version that was added, removed or changed (any field), by id. */
data class RuleChange(val id: String, val kind: String)

/**
 * The definition-update preview of CHALLENGE_ENGINE 4.12 (D44). [varMap] is what the update writes to the vars the new
 * version adds; [hash] binds the preview to the state it was made on and becomes the event's `preview_hash`.
 */
data class MigrationPreview(
    val fromVersion: String,
    val toVersion: String,
    val varMap: SortedMap<String, Value>,
    val sessions: List<SessionDiff>,
    val rules: List<RuleChange>,
    /** Proposals of the new version that the old one does not have; they arrive undecided (2.2). */
    val newProposals: List<String>,
    /** [StateJson.hash] of the state the preview was made on; the update is refused on any other state. */
    val stateHash: String,
    val hash: String,
)

object MigrationPreviews {
    private val json = Json { encodeDefaults = true }

    /**
     * Materializes every planned session of [sessions] (slot and date, from this and next week's plans) under [from] and
     * [to] with the state as the update would leave it, and compares them item by item.
     */
    fun preview(
        from: CompiledDefinition,
        to: CompiledDefinition,
        params: ParamView,
        state: EnrollmentState,
        facts: EngineFacts,
        catalog: CatalogDoc?,
        sessions: List<Pair<String, LocalDate>>,
        today: LocalDate,
        changeoverMin: Int,
        preferences: Map<String, String> = emptyMap(),
    ): MigrationPreview {
        val varMap = EnrollmentFold.migrationVars(from, to, params, state, today)
        val stateHash = StateJson.hash(state)
        val moved = state.copy(
            vars = TreeMap(state.vars.filterKeys { it in to.stateVarByName }).also { it.putAll(varMap) },
            definitionVersion = to.version,
            definitionSha = to.jcsSha256,
        )
        val diffs = sessions.map { (slot, date) ->
            val before = Materializer.materialize(from, params, MaterializeInput(slot, date, state, facts, catalog, preferences, changeoverMin = changeoverMin))
            val after = Materializer.materialize(to, params, MaterializeInput(slot, date, moved, facts, catalog, preferences, changeoverMin = changeoverMin))
            SessionDiff(slot, date, before, after, changes(before, after))
        }
        val rules = ruleChanges(from.doc, to.doc)
        val known = from.doc.proposals.map { it.id }.toSet()
        val proposals = to.doc.proposals.map { it.id }.filter { it !in known }
        val hash = Jcs.sha256Hex(
            JsonObject(
                sortedMapOf(
                    "from" to JsonPrimitive(from.jcsSha256),
                    "to" to JsonPrimitive(to.jcsSha256),
                    "state_hash" to JsonPrimitive(stateHash),
                    "var_map" to JsonObject(varMap.mapValues { ValueJson.encode(it.value) }.toSortedMap()),
                    "sessions" to JsonArray(
                        diffs.map { d ->
                            JsonObject(
                                sortedMapOf(
                                    "slot" to JsonPrimitive(d.slot), "date" to JsonPrimitive(d.date.toString()),
                                    "changes" to JsonArray(d.changes.map { JsonPrimitive("${it.kind}:${it.key}") }),
                                ),
                            )
                        },
                    ),
                    "rules" to JsonArray(rules.map { JsonPrimitive("${it.kind}:${it.id}") }),
                    "proposals" to JsonArray(proposals.map(::JsonPrimitive)),
                ),
            ),
        )
        return MigrationPreview(from.version, to.version, varMap, diffs, rules, proposals, stateHash, hash)
    }

    /** Items in [before]'s order, then the items only [after] has, in its order; equal items are left out. */
    fun changes(before: SessionPlan, after: SessionPlan): List<ItemChange> {
        val next = after.items.associateBy { it.key }
        val prev = before.items.associateBy { it.key }
        return before.items.mapNotNull { b -> next[b.key].let { a -> if (a == b) null else ItemChange(b.key, b, a) } } +
            after.items.filter { it.key !in prev }.map { ItemChange(it.key, null, it) }
    }

    /** Rules by id: those only one version has, and those whose JSON differs. Sorted by id. */
    fun ruleChanges(from: DefinitionDoc, to: DefinitionDoc): List<RuleChange> {
        fun byId(d: DefinitionDoc) = d.rules.associate { it.id to json.encodeToJsonElement(RuleSpec.serializer(), it) }.toSortedMap()
        val a = byId(from)
        val b = byId(to)
        return (a.keys + b.keys).toSortedSet().mapNotNull { id ->
            when {
                id !in a -> RuleChange(id, "added")
                id !in b -> RuleChange(id, "removed")
                a[id] != b[id] -> RuleChange(id, "changed")
                else -> null
            }
        }
    }
}
