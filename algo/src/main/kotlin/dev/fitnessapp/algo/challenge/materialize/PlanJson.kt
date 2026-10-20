package dev.fitnessapp.algo.challenge.materialize

import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.fitexpr.ValueJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.LocalDate

/**
 * JSON of a materialized [SessionPlan] and its live rows, so the logger can restore a challenge session after
 * process death exactly as it was planned and changed by in-session rules (6.10); `decode(encode(p)) == p`.
 */
object PlanJson {
    private fun obj(m: Map<String, JsonElement?>): JsonObject = JsonObject(m.filterValues { it != null }.mapValues { it.value!! })
    private fun num(v: BigDecimal?): JsonElement? = v?.let(ValueJson::number)
    private fun int(v: Int?): JsonElement? = v?.let { JsonPrimitive(it) }
    private fun str(v: String?): JsonElement? = v?.let(::JsonPrimitive)
    private fun strings(l: List<String>): JsonArray = JsonArray(l.map(::JsonPrimitive))
    private fun i18n(m: Map<String, String>): JsonObject = JsonObject(m.toSortedMap().mapValues { JsonPrimitive(it.value) })

    fun encode(p: SessionPlan): JsonObject = obj(
        mapOf(
            "slot" to JsonPrimitive(p.slot), "template_id" to JsonPrimitive(p.templateId), "phase" to int(p.phase), "mode" to JsonPrimitive(p.mode),
            "date" to JsonPrimitive(p.date.toString()), "kind" to JsonPrimitive(p.kind), "booked_min" to int(p.bookedMin), "budget_min" to int(p.budgetMin),
            "items" to JsonArray(p.items.map(::item)), "est_min" to num(p.estMin), "add_on_capacity_min" to int(p.addOnCapacityMin),
            "add_on_offered" to JsonPrimitive(p.addOnOffered), "warnings" to JsonArray(p.warnings.map(::warning)),
            "snapshot" to obj(
                mapOf(
                    "definition_sha" to JsonPrimitive(p.snapshot.definitionSha), "engine_version" to JsonPrimitive(p.snapshot.engineVersion),
                    "params_hash" to JsonPrimitive(p.snapshot.paramsHash), "catalog_version" to str(p.snapshot.catalogVersion),
                    "state_rev" to int(p.snapshot.stateRev),
                ),
            ),
        ),
    )

    fun item(i: PlannedItem): JsonObject = obj(
        mapOf(
            "key" to JsonPrimitive(i.key), "exercise_key" to str(i.exerciseKey), "catalog_slug" to str(i.catalogSlug), "name" to i18n(i.name),
            "role" to JsonPrimitive(i.role), "status" to JsonPrimitive(i.status), "status_reason" to str(i.statusReason),
            "rows" to rows(i.rows), "rir_target" to int(i.rirTarget), "flags_offered" to strings(i.flagsOffered), "stop_set_on" to strings(i.stopSetOn),
            "notes" to JsonArray(i.notes.map(::i18n)), "progression_mode" to JsonPrimitive(i.progressionMode),
            "in_session_rule_ids" to strings(i.inSessionRuleIds), "calibration_first_set" to JsonPrimitive(i.calibrationFirstSet),
            "metric" to str(i.metric), "record" to str(i.record), "substituted_from" to str(i.substitutedFrom),
            "progression_excluded" to JsonPrimitive(i.progressionExcluded), "warnings" to JsonArray(i.warnings.map(::warning)),
            "note_codes" to strings(i.noteCodes),
        ),
    )

    fun rows(rows: List<PlannedRow>): JsonArray = JsonArray(rows.map(::row))

    fun row(r: PlannedRow): JsonObject = obj(
        mapOf(
            "side" to JsonPrimitive(r.side), "set_kind" to JsonPrimitive(r.setKind), "set_index" to int(r.setIndex), "pair_index" to int(r.pairIndex),
            "target_reps" to int(r.targetReps), "target_reps_max" to int(r.targetRepsMax), "target_hold_s" to num(r.targetHoldS),
            "target_hold_max_s" to num(r.targetHoldMaxS), "target_ecc_s" to num(r.targetEccS), "duration_s" to num(r.durationS),
            "added_kg" to num(r.addedKg), "plate_breakdown" to r.plateBreakdown?.let { l -> JsonArray(l.map(ValueJson::number)) },
            "system_load_kg" to num(r.systemLoadKg), "pin" to num(r.pin), "assist_kg" to num(r.assistKg), "one_arm_load_kg" to num(r.oneArmLoadKg),
            "fingers" to int(r.fingers), "attempts" to int(r.attempts), "rest_s" to int(r.restS), "log_mode" to JsonPrimitive(r.logMode.code),
            "changed_by_rule" to str(r.changedByRule),
        ),
    )

    private fun warning(w: PlanWarning): JsonObject =
        obj(mapOf("code" to JsonPrimitive(w.code), "severity" to JsonPrimitive(w.severity), "detail" to JsonPrimitive(w.detail)))

    // ------------------------------------------------------------------------------------------- decode

    private fun JsonObject.s(k: String): String = getValue(k).jsonPrimitive.content
    private fun JsonObject.so(k: String): String? = (get(k) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.i(k: String): Int = s(k).toInt()
    private fun JsonObject.io(k: String): Int? = so(k)?.toInt()
    private fun JsonObject.d(k: String): BigDecimal? = so(k)?.let { Dec.norm(BigDecimal(it)) }
    private fun JsonObject.b(k: String): Boolean = get(k)?.jsonPrimitive?.booleanOrNull == true
    private fun JsonObject.list(k: String): List<String> = (get(k) as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
    private fun JsonObject.i18n(k: String): Map<String, String> = (get(k) as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

    fun decode(o: JsonObject): SessionPlan {
        val snap = o.getValue("snapshot").jsonObject
        return SessionPlan(
            slot = o.s("slot"), templateId = o.s("template_id"), phase = o.i("phase"), mode = o.s("mode"), date = LocalDate.parse(o.s("date")),
            kind = o.s("kind"), bookedMin = o.i("booked_min"), budgetMin = o.i("budget_min"),
            items = o.getValue("items").jsonArray.map { decodeItem(it.jsonObject) }, estMin = o.d("est_min"),
            addOnCapacityMin = o.io("add_on_capacity_min"), addOnOffered = o.b("add_on_offered"),
            warnings = o.getValue("warnings").jsonArray.map { decodeWarning(it.jsonObject) },
            snapshot = PlanSnapshot(snap.s("definition_sha"), snap.s("engine_version"), snap.s("params_hash"), snap.so("catalog_version"), snap.i("state_rev")),
        )
    }

    fun decodeItem(o: JsonObject): PlannedItem = PlannedItem(
        key = o.s("key"), exerciseKey = o.so("exercise_key"), catalogSlug = o.so("catalog_slug"), name = o.i18n("name"), role = o.s("role"),
        status = o.s("status"), statusReason = o.so("status_reason"), rows = decodeRows(o.getValue("rows").jsonArray), rirTarget = o.io("rir_target"),
        flagsOffered = o.list("flags_offered"), stopSetOn = o.list("stop_set_on"),
        notes = (o["notes"] as? JsonArray).orEmpty().map { n -> n.jsonObject.mapValues { it.value.jsonPrimitive.content } },
        progressionMode = o.s("progression_mode"), inSessionRuleIds = o.list("in_session_rule_ids"), calibrationFirstSet = o.b("calibration_first_set"),
        metric = o.so("metric"), record = o.so("record"), substitutedFrom = o.so("substituted_from"), progressionExcluded = o.b("progression_excluded"),
        warnings = (o["warnings"] as? JsonArray).orEmpty().map { decodeWarning(it.jsonObject) }, noteCodes = o.list("note_codes"),
    )

    fun decodeRows(a: JsonArray): List<PlannedRow> = a.map { decodeRow(it.jsonObject) }

    fun decodeRow(o: JsonObject): PlannedRow = PlannedRow(
        side = o.s("side"), setKind = o.s("set_kind"), setIndex = o.i("set_index"), pairIndex = o.io("pair_index"), targetReps = o.io("target_reps"),
        targetRepsMax = o.io("target_reps_max"), targetHoldS = o.d("target_hold_s"), targetHoldMaxS = o.d("target_hold_max_s"),
        targetEccS = o.d("target_ecc_s"), durationS = o.d("duration_s"), addedKg = o.d("added_kg"),
        plateBreakdown = (o["plate_breakdown"] as? JsonArray)?.map { Dec.norm(BigDecimal(it.jsonPrimitive.content)) },
        systemLoadKg = o.d("system_load_kg"), pin = o.d("pin"), assistKg = o.d("assist_kg"), oneArmLoadKg = o.d("one_arm_load_kg"),
        fingers = o.io("fingers"), attempts = o.io("attempts"), restS = o.io("rest_s"),
        logMode = LogMode.entries.first { it.code == o.s("log_mode") }, changedByRule = o.so("changed_by_rule"),
    )

    private fun decodeWarning(o: JsonObject) = PlanWarning(o.s("code"), o.s("severity"), o.s("detail"))
}
