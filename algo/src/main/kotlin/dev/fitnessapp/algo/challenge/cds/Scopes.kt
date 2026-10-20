package dev.fitnessapp.algo.challenge.cds

import dev.fitnessapp.algo.challenge.fitexpr.Type
import dev.fitnessapp.algo.challenge.fitexpr.TypeScope

/**
 * The identifier scopes of CHALLENGE_ENGINE 3.7, one per expression field. Field names and types here are
 * the single list that both the compiler (static types) and the engine (runtime records) use.
 */
object Scopes {
    /** DATA_MODEL E6 set flags (C38), exposed as boolean row fields under their snake_case codes. */
    val SET_FLAGS: List<String> = listOf(
        "grip_slip_before_last", "jerky", "hand_shift", "torso_rotation", "hip_rotation", "technique_breakdown",
        "failed_rep", "used_straps",
    )

    val SIDES = Type.EnumT(listOf("L", "R", "both"))
    val EFFORT = Type.EnumT(listOf("max", "hard", "ok", "easy"))
    val SET_KINDS = Type.EnumT(listOf("warmup", "working", "test", "attempt", "calibration", "backoff", "drop"))
    val MODE = Type.EnumT(Modes.ALL)

    /** Per-row fields of `ex.<key>.<field>` (lists in logging order). */
    val ROW_FIELDS: Map<String, Type> = linkedMapOf<String, Type>(
        "reps" to Type.INT, "rir" to Type.NUM, "added" to Type.MASS, "pin" to Type.PIN, "assist" to Type.MASS,
        "fingers" to Type.INT, "hold_s" to Type.DURATION, "hold_reserve_s" to Type.DURATION, "ecc_s" to Type.DURATION,
        "effort" to EFFORT, "side" to SIDES, "kind" to SET_KINDS,
    ).also { m -> SET_FLAGS.forEach { m[it] = Type.BoolT } }

    /** Scalars of `ex.<key>` besides the row lists. */
    val ITEM_SCALARS: Map<String, Type> = linkedMapOf("plannedRows" to Type.INT, "targetReps" to Type.INT, "targetHold_s" to Type.DURATION)

    fun itemRows(name: String, withDays: Boolean = false): Type.RecordT {
        val lists = ROW_FIELDS.mapValues { Type.ListT(it.value) }
        val side = Type.RecordT("$name.side", lists)
        val fields = LinkedHashMap<String, Type>(lists)
        fields.putAll(ITEM_SCALARS)
        fields["L"] = side
        fields["R"] = side
        if (withDays) fields["days"] = Type.INT
        return Type.RecordT(name, fields)
    }

    /** `set.*` of an in-session `set.logged` rule. */
    val SET_FIELDS: Map<String, Type> = linkedMapOf<String, Type>(
        "reps" to Type.INT, "rir" to Type.NUM, "added" to Type.MASS, "pin" to Type.PIN, "hold_s" to Type.DURATION,
        "ecc_s" to Type.DURATION, "side" to SIDES, "index" to Type.INT, "targetReps" to Type.INT,
        "targetHold_s" to Type.DURATION,
    ).also { m -> SET_FLAGS.forEach { m[it] = Type.BoolT } }

    /** `pair.*` of an in-session `pair.logged` rule: the L and R rows as two-element lists, plus scalars. */
    val PAIR_FIELDS: Map<String, Type> = linkedMapOf<String, Type>().also { m ->
        for ((k, t) in ROW_FIELDS) m[k] = Type.ListT(t)
        m["index"] = Type.INT
        m["targetReps"] = Type.INT
        m["targetHold_s"] = Type.DURATION
    }

    /** `event.*` payload fields per input event type (section 3.7, second table). */
    val EVENT_FIELDS: Map<String, Map<String, Type>> = mapOf(
        "pain.reported" to linkedMapOf(
            "painKinds" to Type.ListT(Type.STR), "location" to Type.STR,
            "side" to Type.EnumT(listOf("L", "R", "both", "none")),
            "severity" to Type.EnumT(listOf("mild", "moderate", "sharp")), "painScore" to Type.INT,
        ),
        "readiness.checked" to linkedMapOf("elbowDiscomfort" to Type.INT, "painKinds" to Type.ListT(Type.STR)),
        "resume" to linkedMapOf("gapDays" to Type.DAYS, "reason" to Type.STR),
        "pause" to linkedMapOf("reason" to Type.STR),
        "abandon" to linkedMapOf("reason" to Type.STR),
        "attempt.logged" to linkedMapOf("metric" to Type.STR, "side" to SIDES, "success" to Type.BoolT),
    )

    val INPUT_EVENTS: List<String> = listOf(
        "enrollment.created", "rules.approval_changed", "session.started", "session.completed", "session.amended",
        "set.logged", "habit.completed", "readiness.checked", "pain.reported", "test.due", "test.completed",
        "attempt.logged", "week.closed", "week.started", "day.tick", "pause", "resume", "abandon", "manual.override",
        "definition.migrated",
    )
    val DERIVED_EVENTS: List<String> = listOf("phase.changed", "block.started", "mode.changed")

    private val SESSION_EVENTS = setOf("session.completed", "session.amended")

    /** What a definition contributes to the scopes. */
    class Inputs(
        val params: Map<String, Type>,
        val vars: Map<String, Type>,
        val metrics: Map<String, Type>,
        val derived: Map<String, Pair<Type, List<String>>>,
        val itemKeys: List<String>,
        val slots: List<String>,
    )

    private fun base(i: Inputs, vars: Map<String, Type> = i.vars): LinkedHashMap<String, Type> = linkedMapOf(
        "p" to Type.RecordT("p", i.params),
        "s" to Type.RecordT("s", vars),
        "block" to Type.RecordT("block", linkedMapOf("index" to Type.INT, "week" to Type.INT, "startDate" to Type.DateT)),
        "phase" to Type.INT,
        "mode" to MODE,
        "bw" to Type.MASS,
        "today" to Type.DateT,
    )

    private fun ex(i: Inputs, name: String, withDays: Boolean): Type.RecordT =
        Type.RecordT(name, i.itemKeys.associateWith { itemRows("$name.$it", withDays) })

    private fun metricRecord(name: String, i: Inputs) = Type.RecordT(name, i.metrics)

    /** Roots available for a rule (or derived fact) listening to [events]: the intersection over the events. */
    fun forEvents(i: Inputs, events: List<String>, lets: Map<String, Type> = emptyMap()): TypeScope {
        val roots = base(i)
        val all = events.toSet()
        if (all.isNotEmpty() && all.all { it in SESSION_EVENTS }) {
            roots["sess"] = Type.RecordT(
                "sess",
                linkedMapOf(
                    "slot" to Type.EnumT(i.slots), "templateId" to Type.STR, "kind" to Type.EnumT(listOf("gym", "test", "home")),
                    "date" to Type.DateT, "completionRatio" to Type.NUM, "painReported" to Type.BoolT, "durationMin" to Type.NUM,
                ),
            )
            roots["ex"] = ex(i, "ex", withDays = false)
        }
        if (all.isNotEmpty() && all.all { it == "week.closed" || it == "week.started" }) {
            val fields = linkedMapOf<String, Type>("iso" to Type.STR, "start" to Type.DateT, "ordinal" to Type.INT)
            if (all == setOf("week.closed")) fields["ex"] = ex(i, "week.ex", withDays = true)
            roots["week"] = Type.RecordT("week", fields)
        }
        if (all == setOf("test.completed")) {
            roots["t"] = metricRecord("t", i)
            roots["pt"] = metricRecord("pt", i)
            roots["test"] = Type.RecordT("test", mapOf("valid" to Type.BoolT))
        }
        if (all == setOf("attempt.logged")) roots["m"] = metricRecord("m", i)
        val eventFields = all.map { EVENT_FIELDS[it].orEmpty() }
        if (eventFields.isNotEmpty()) {
            val common = eventFields.first().filterKeys { k -> eventFields.all { it[k] != null && it[k] == eventFields.first()[k] } }
            roots["event"] = Type.RecordT("event", common)
        }
        val d = i.derived.filterValues { (_, evs) -> all.isNotEmpty() && evs.containsAll(all) }.mapValues { it.value.first }
        roots["d"] = Type.RecordT("d", d)
        roots.putAll(lets)
        return TypeScope.of(roots)
    }

    /** `state.vars[].init`: `onboard.*` (runtime-typed), params, and the vars declared before. */
    fun forInit(i: Inputs, earlier: Map<String, Type>): TypeScope {
        val roots = base(i, earlier)
        roots["onboard"] = Type.AnyT
        return TypeScope.of(roots)
    }

    /** Materialization fields of an item (6.3), habit conditions and other state-only expressions. */
    fun forState(i: Inputs): TypeScope = TypeScope.of(base(i))

    /** Mode transforms: `sets` (shorthand of `item.sets`) and `item.*`. */
    fun forModeTransform(i: Inputs): TypeScope {
        val roots = base(i)
        roots["sets"] = Type.INT
        roots["item"] = Type.RecordT("item", linkedMapOf("sets" to Type.INT, "reps" to Type.INT, "rounds" to Type.INT))
        return TypeScope.of(roots)
    }

    /** Exit gates and template validity: the test (`t`, `pt`) or the phase's observations (`m`). */
    fun forTest(i: Inputs, observations: Boolean = false): TypeScope {
        val roots = base(i)
        if (observations) {
            roots["m"] = metricRecord("m", i)
        } else {
            roots["t"] = metricRecord("t", i)
            roots["pt"] = metricRecord("pt", i)
        }
        return TypeScope.of(roots)
    }

    fun forInSession(i: Inputs, on: String): TypeScope {
        val roots = base(i)
        if (on == "set.logged") roots["set"] = Type.RecordT("set", SET_FIELDS)
        if (on == "pair.logged") roots["pair"] = Type.RecordT("pair", PAIR_FIELDS)
        return TypeScope.of(roots)
    }

    /** `structure.advance.weekCounts`: evaluated on `week.closed`. */
    fun forWeekClose(i: Inputs): TypeScope = forEvents(i, listOf("week.closed"))
}
