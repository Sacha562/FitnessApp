package dev.fitnessapp.sim.challenge

import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.enrollment.StateJson
import dev.fitnessapp.algo.challenge.fitexpr.ValueJson
import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.core.params.ParamView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The committed snapshot of one persona run (CHALLENGE_ENGINE 2.6 stage 3): per week the mode, phase,
 * block/week, planned test, every state var, the sessions done, the rule firings by outcome and the
 * notifications. CI fails on any diff that is not accompanied by an updated snapshot and a review note.
 */
object Trajectory {
    private val pretty = Json { prettyPrint = true }

    fun encode(def: CompiledDefinition, params: ParamView, r: SimResult): JsonObject {
        fun obj(vararg p: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*p))
        val weeks = r.weeks.map { w ->
            val s = w.state
            obj(
                "iso_week" to JsonPrimitive(w.iso),
                "status" to JsonPrimitive(s.status),
                "mode" to JsonPrimitive(s.mode),
                "phase" to JsonPrimitive(s.phase),
                "block" to JsonPrimitive("${s.block.index}/${s.block.week} from ${s.block.startDate}"),
                "week_ordinal" to JsonPrimitive(s.weekOrdinal),
                "planned_test" to JsonPrimitive("${s.plannedTest.basis} ${s.plannedTest.date ?: "-"}"),
                "sessions" to JsonArray(w.sessions.map { JsonPrimitive(it) }),
                "vars" to JsonObject(s.vars.mapValues { ValueJson.encode(it.value) }.toSortedMap()),
                "fired" to JsonObject(w.fired.mapValues { (_, m) -> JsonObject(m.mapValues { JsonPrimitive(it.value) }.toSortedMap()) }.toSortedMap()),
                "notifications" to JsonArray(w.notifications.map { JsonPrimitive(it) }),
            )
        }
        return obj(
            "persona" to JsonPrimitive(r.persona),
            "proposals" to JsonPrimitive(if (r.approved) "approved" else "declined"),
            "definition" to JsonPrimitive("${def.slug}@${def.version}"),
            "definition_jcs_sha256" to JsonPrimitive(def.jcsSha256),
            "engine_version" to JsonPrimitive(EngineVersion.ENGINE_VERSION),
            "params_hash" to JsonPrimitive(params.hash),
            "events" to JsonPrimitive(r.events.size),
            "audit_rows" to JsonPrimitive(r.audits.size),
            "final_state_hash" to JsonPrimitive(StateJson.hash(r.finalState)),
            "violations" to JsonArray(r.violations.map { JsonPrimitive(it) }),
            "weeks" to JsonArray(weeks),
        )
    }

    /** Pretty JSON with a trailing newline, stable across runs (keys are written in a fixed order). */
    fun text(def: CompiledDefinition, params: ParamView, r: SimResult): String = pretty.encodeToString(JsonElement.serializer(), encode(def, params, r)) + "\n"

    fun hash(def: CompiledDefinition, params: ParamView, r: SimResult): String = Jcs.sha256Hex(encode(def, params, r))
}
