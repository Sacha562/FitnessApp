package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.LoadProfile
import dev.fitnessapp.algo.challenge.fitexpr.MachineCalibration
import dev.fitnessapp.algo.challenge.fitexpr.Type
import dev.fitnessapp.algo.challenge.fitexpr.UserKFact
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.ValueJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.SortedMap
import java.util.TreeMap
import java.util.UUID

/**
 * The storage format of `fitness.challenge_events.payload` (CHALLENGE_ENGINE 4.2, DATA_MODEL 9.7.1): snake_case
 * keys, logged rows under their `set_logs` column names, coded values as DATA_MODEL codes and the engine-fact
 * snapshot under `engine_facts`. Typed values (params, state vars, metrics, override values) are plain JSON
 * numbers, read back with the definition's declared types; runtime-typed `onboard` values carry their
 * dimension (`{"dim": "MASS", "v": 70.7}`). The envelope (id, type, times, `supersedes_event_id`) lives in
 * the table's own columns. `decode(encode(e))` folds to the same state hash.
 */
object EventCodec {
    private fun obj(m: Map<String, JsonElement?>): JsonObject = JsonObject(m.filterValues { it != null }.mapValues { it.value!! }.toSortedMap())

    private fun str(s: String?): JsonElement? = s?.let(::JsonPrimitive)
    private fun num(v: BigDecimal?): JsonElement? = v?.let(ValueJson::number)
    private fun int(v: Int?): JsonElement? = v?.let { ValueJson.number(BigDecimal(it)) }
    private fun long(v: Long?): JsonElement? = v?.let { ValueJson.number(BigDecimal(it)) }
    private fun strings(l: Collection<String>): JsonArray = JsonArray(l.map(::JsonPrimitive))

    // ------------------------------------------------------------------------------------------- encode

    fun encode(def: CompiledDefinition, e: ChallengeEvent): JsonObject {
        val body: Map<String, JsonElement?> = when (val p = e.payload) {
            EventPayload.Empty -> emptyMap()
            is EventPayload.Created -> mapOf(
                "enrollment_id" to JsonPrimitive(p.enrollmentId.toString()),
                "params" to JsonObject(p.params.mapValues { ValueJson.encode(it.value) }.toSortedMap()),
                "onboard" to JsonObject(p.onboard.mapValues { ValueJson.tagged(it.value) }.toSortedMap()),
                "import" to (p.import?.let(::import) ?: JsonNull),
                "definition_version" to JsonPrimitive(p.definitionVersion),
                "jcs_sha256" to JsonPrimitive(p.jcsSha256),
            )
            is EventPayload.ApprovalChanged -> mapOf("proposal_id" to JsonPrimitive(p.proposalId), "decision" to JsonPrimitive(p.decision))
            is EventPayload.Session -> session(p.session)
            is EventPayload.HabitCompleted -> mapOf("habit_id" to JsonPrimitive(p.habitId), "items" to items(p.items))
            is EventPayload.Readiness -> mapOf("elbow_score" to int(p.elbowScore), "symptom_codes" to strings(p.symptomCodes))
            is EventPayload.Pain -> mapOf(
                "body_region" to JsonPrimitive(p.bodyRegion), "side" to JsonPrimitive(p.side), "symptom_codes" to strings(p.symptomCodes),
                "severity" to JsonPrimitive(p.severity), "pain_score" to int(p.painScore), "retracted" to JsonPrimitive(p.retracted),
            )
            is EventPayload.TestDue -> mapOf("date" to JsonPrimitive(p.date.toString()))
            is EventPayload.TestCompleted -> mapOf(
                "test_session_id" to JsonPrimitive(p.testSessionId.toString()),
                "template_id" to JsonPrimitive(p.templateId),
                "metrics" to JsonObject(p.metrics.mapValues { ValueJson.encode(it.value) }.toSortedMap()),
                "protocol_codes" to JsonObject(p.protocolCodes.mapValues { JsonPrimitive(it.value) }.toSortedMap()),
            )
            is EventPayload.AttemptLogged -> mapOf("metric" to JsonPrimitive(p.metric), "side" to JsonPrimitive(p.side), "success" to JsonPrimitive(p.success))
            is EventPayload.WeekBoundary -> mapOf("iso_week" to JsonPrimitive(p.isoWeek))
            is EventPayload.DayTick -> mapOf("date" to JsonPrimitive(p.date.toString()))
            is EventPayload.Pause -> mapOf("reason_code" to str(p.reasonCode))
            is EventPayload.Resume -> mapOf("reason_code" to str(p.reasonCode), "gap_days" to long(p.gapDays))
            is EventPayload.Abandon -> mapOf("reason_code" to str(p.reasonCode))
            is EventPayload.ManualOverride -> mapOf(
                "target" to JsonPrimitive(p.target), "value" to ValueJson.encode(p.value), "reason_code" to JsonPrimitive(p.reasonCode),
            )
        }
        val facts = e.engineFacts?.let { "engine_facts" to facts(it) }
        return obj(body + listOfNotNull(facts))
    }

    private fun import(i: ImportBlock): JsonObject = obj(
        mapOf(
            "phase" to int(i.phase), "block_index" to int(i.blockIndex), "block_week" to int(i.blockWeek), "mode" to JsonPrimitive(i.mode),
            "week_ordinal" to int(i.weekOrdinal), "planned_test_date" to (i.plannedTestDate?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
            "vars" to JsonObject(i.vars.mapValues { ValueJson.encode(it.value) }.toSortedMap()),
            "past_tests" to JsonArray(
                i.pastTests.map { t ->
                    obj(
                        mapOf(
                            "date" to JsonPrimitive(t.date.toString()),
                            "metrics" to JsonObject(t.metrics.mapValues { ValueJson.encode(it.value) }.toSortedMap()),
                            "valid_for_gate" to JsonPrimitive(t.validForGate),
                            "not_comparable" to strings(t.notComparable.sorted()),
                            "protocol_codes" to JsonObject(t.protocolCodes.mapValues { JsonPrimitive(it.value) }.toSortedMap()),
                        ),
                    )
                },
            ),
            "block_item_counts" to JsonObject(i.blockItemCounts.mapValues { ValueJson.number(BigDecimal(it.value)) }.toSortedMap()),
            "snapshot_current_as_block_start" to JsonPrimitive(i.snapshotCurrentAsBlockStart),
        ),
    )

    private fun session(s: SessionLog): Map<String, JsonElement?> = mapOf(
        "session_id" to JsonPrimitive(s.sessionId.toString()), "slot" to JsonPrimitive(s.slot), "template_id" to JsonPrimitive(s.templateId),
        "kind" to JsonPrimitive(s.kind), "local_date" to JsonPrimitive(s.localDate.toString()), "completion_ratio" to num(s.completionRatio),
        "pain_reported" to JsonPrimitive(s.painReported), "duration_min" to num(s.durationMin), "items" to items(s.items),
    )

    private fun items(items: SortedMap<String, ItemLog>): JsonObject = JsonObject(
        items.mapValues { (_, i) ->
            obj(
                mapOf(
                    "status" to JsonPrimitive(i.status), "planned_rows" to int(i.plannedRows), "target_reps" to int(i.targetReps),
                    "target_hold_s" to num(i.targetHoldS), "rows" to JsonArray(i.rows.map(StateJson::row)),
                    "status_reason_code" to str(i.statusReasonCode), "substitution_reason_code" to str(i.substitutionReasonCode),
                    "progression_excluded" to JsonPrimitive(i.progressionExcluded),
                ),
            )
        }.toSortedMap(),
    )

    fun facts(f: EngineFacts): JsonObject = obj(
        mapOf(
            "captured_at" to str(f.capturedAt?.toString()),
            "bw_kg" to num(f.bwKg),
            "user_k" to JsonObject(
                f.userK.mapValues { (_, k) ->
                    obj(
                        mapOf(
                            "catalog_id" to JsonPrimitive(k.catalogId), "k" to num(k.k), "sd_ln_k" to num(k.sdLnk),
                            "fit_run_id" to str(k.fitRunId), "informative_windows" to int(k.informativeWindows),
                        ),
                    )
                }.toSortedMap(),
            ),
            "pain_last_date" to JsonObject(f.painLastDate.mapValues { JsonPrimitive(it.value.toString()) }.toSortedMap()),
            "has" to strings(f.has),
            "machines" to JsonObject(
                f.machines.mapValues { (_, m) ->
                    obj(
                        mapOf(
                            "unit" to JsonPrimitive(m.unit), "step" to num(m.step), "min" to num(m.min), "max" to num(m.max),
                            "offset_kg" to num(m.offsetKg), "ratio" to num(m.ratio), "rev" to int(m.rev),
                        ),
                    )
                }.toSortedMap(),
            ),
            "load_profiles" to JsonObject(
                f.loadProfiles.mapValues { (_, l) -> obj(mapOf("achievable_kg" to JsonArray(l.achievableKg.map(ValueJson::number)), "rev" to int(l.rev))) }.toSortedMap(),
            ),
            "week_plan_slots" to JsonObject(f.weekPlanSlots.entries.associate { (d, s) -> d.toString() to strings(s) }.toSortedMap()),
        ),
    )

    // ------------------------------------------------------------------------------------------- decode

    private fun JsonObject.s(k: String): String = getValue(k).jsonPrimitive.content
    private fun JsonObject.so(k: String): String? = (get(k) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.i(k: String): Int = getValue(k).jsonPrimitive.content.toInt()
    private fun JsonObject.io(k: String): Int? = so(k)?.toInt()
    private fun JsonObject.d(k: String): BigDecimal = Dec.norm(BigDecimal(s(k)))
    private fun JsonObject.dO(k: String): BigDecimal? = so(k)?.let { Dec.norm(BigDecimal(it)) }
    private fun JsonObject.b(k: String): Boolean = getValue(k).jsonPrimitive.booleanOrNull ?: error("$k is not a boolean")
    private fun JsonObject.o(k: String): JsonObject = (get(k) as? JsonObject) ?: JsonObject(emptyMap())
    private fun JsonObject.list(k: String): List<String> = (get(k) as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    fun decode(
        def: CompiledDefinition,
        id: UUID,
        type: String,
        occurredAt: Instant,
        localDate: LocalDate,
        payload: JsonObject,
        supersedesEventId: UUID? = null,
    ): ChallengeEvent {
        val p = payload
        val body: EventPayload = when (type) {
            EventTypes.CREATED -> EventPayload.Created(
                enrollmentId = UUID.fromString(p.s("enrollment_id")),
                params = TreeMap(p.o("params").mapValues { (k, v) -> ValueJson.decode(def.params[k]?.type ?: Type.AnyT, v) }),
                onboard = TreeMap(p.o("onboard").mapValues { ValueJson.untagged(it.value) }),
                import = (p["import"] as? JsonObject)?.let { import(def, it) },
                definitionVersion = p.s("definition_version"),
                jcsSha256 = p.s("jcs_sha256"),
            )
            EventTypes.APPROVAL -> EventPayload.ApprovalChanged(p.s("proposal_id"), p.s("decision"))
            EventTypes.SESSION_COMPLETED, EventTypes.SESSION_AMENDED -> EventPayload.Session(session(p))
            EventTypes.HABIT_COMPLETED -> EventPayload.HabitCompleted(p.s("habit_id"), items(p.o("items")))
            EventTypes.READINESS -> EventPayload.Readiness(p.i("elbow_score"), p.list("symptom_codes"))
            EventTypes.PAIN -> EventPayload.Pain(
                p.s("body_region"), p.s("side"), p.list("symptom_codes"), p.s("severity"), p.i("pain_score"), p.b("retracted"),
            )
            EventTypes.TEST_DUE -> EventPayload.TestDue(LocalDate.parse(p.s("date")))
            EventTypes.TEST_COMPLETED -> EventPayload.TestCompleted(
                UUID.fromString(p.s("test_session_id")), p.s("template_id"),
                TreeMap(p.o("metrics").mapValues { (k, v) -> ValueJson.decode(def.metrics[k]?.type ?: Type.AnyT, v) }),
                TreeMap(p.o("protocol_codes").mapValues { it.value.jsonPrimitive.content }),
            )
            EventTypes.ATTEMPT -> EventPayload.AttemptLogged(p.s("metric"), p.s("side"), p.b("success"))
            EventTypes.WEEK_CLOSED, EventTypes.WEEK_STARTED -> EventPayload.WeekBoundary(p.s("iso_week"))
            EventTypes.DAY_TICK -> EventPayload.DayTick(LocalDate.parse(p.s("date")))
            EventTypes.PAUSE -> EventPayload.Pause(p.so("reason_code"))
            EventTypes.RESUME -> EventPayload.Resume(p.so("reason_code"), p.s("gap_days").toLong())
            EventTypes.ABANDON -> EventPayload.Abandon(p.so("reason_code"))
            EventTypes.OVERRIDE -> EventPayload.ManualOverride(p.s("target"), ValueJson.decode(overrideType(def, p.s("target")), p.getValue("value")), p.s("reason_code"))
            else -> EventPayload.Empty
        }
        val facts = (p["engine_facts"] as? JsonObject)?.let(::facts)
        return ChallengeEvent(id, type, occurredAt, localDate, body, facts, supersedesEventId)
    }

    /** The type a `manual.override` value is read with (4.9). */
    fun overrideType(def: CompiledDefinition, target: String): Type = when (target) {
        "phase", "block.week" -> Type.INT
        "plannedTest.date" -> Type.DateT
        else -> def.stateVarByName[target]?.type ?: Type.AnyT
    }

    private fun import(def: CompiledDefinition, o: JsonObject): ImportBlock = ImportBlock(
        phase = o.i("phase"), blockIndex = o.i("block_index"), blockWeek = o.i("block_week"), mode = o.s("mode"),
        weekOrdinal = o.i("week_ordinal"), plannedTestDate = o.so("planned_test_date")?.let(LocalDate::parse),
        vars = TreeMap(o.o("vars").mapValues { (k, v) -> ValueJson.decode(def.stateVarByName[k]?.type ?: Type.AnyT, v) }),
        pastTests = (o["past_tests"] as? JsonArray).orEmpty().map { it.jsonObject }.map { t ->
            PastTest(
                date = LocalDate.parse(t.s("date")),
                metrics = TreeMap<String, Value>(t.o("metrics").mapValues { (k, v) -> ValueJson.decode(def.metrics[k]?.type ?: Type.AnyT, v) }),
                validForGate = t.b("valid_for_gate"),
                notComparable = t.list("not_comparable").toSet(),
                protocolCodes = TreeMap(t.o("protocol_codes").mapValues { it.value.jsonPrimitive.content }),
            )
        },
        blockItemCounts = TreeMap(o.o("block_item_counts").mapValues { it.value.jsonPrimitive.content.toInt() }),
        snapshotCurrentAsBlockStart = o.b("snapshot_current_as_block_start"),
    )

    private fun session(p: JsonObject): SessionLog = SessionLog(
        sessionId = UUID.fromString(p.s("session_id")), slot = p.s("slot"), templateId = p.s("template_id"), kind = p.s("kind"),
        localDate = LocalDate.parse(p.s("local_date")), completionRatio = p.d("completion_ratio"), painReported = p.b("pain_reported"),
        durationMin = p.dO("duration_min"), items = items(p.o("items")),
    )

    private fun items(o: JsonObject): SortedMap<String, ItemLog> = TreeMap(
        o.mapValues { (_, v) ->
            val i = v.jsonObject
            ItemLog(
                status = i.s("status"), plannedRows = i.i("planned_rows"), targetReps = i.io("target_reps"), targetHoldS = i.dO("target_hold_s"),
                rows = (i["rows"] as? JsonArray).orEmpty().map { row(it.jsonObject) },
                statusReasonCode = i.so("status_reason_code"), substitutionReasonCode = i.so("substitution_reason_code"),
                progressionExcluded = i.b("progression_excluded"),
            )
        },
    )

    fun row(r: JsonObject): RowLog = RowLog(
        side = r.s("side"), setKind = r.s("set_kind"), setIndex = r.i("set_index"), reps = r.io("reps"), rirReported = r.dO("rir_reported"),
        addedKg = r.dO("added_kg"), assistPinNative = r.dO("assist_pin_native"), assistKg = r.dO("assist_kg"), assistOrdinal = r.io("assist_ordinal"),
        durationS = r.dO("duration_s"), holdReserveS = r.dO("hold_reserve_s"), eccSActual = r.dO("ecc_s_actual"), flags = r.list("flags"),
        deviationReasonCode = r.so("deviation_reason_code"),
    )

    fun facts(o: JsonObject): EngineFacts = EngineFacts(
        capturedAt = o.so("captured_at")?.let(Instant::parse),
        bwKg = o.dO("bw_kg"),
        userK = TreeMap(
            o.o("user_k").mapValues { (_, v) ->
                val k = v.jsonObject
                UserKFact(k.s("catalog_id"), k.d("k"), k.d("sd_ln_k"), k.so("fit_run_id"), k.i("informative_windows"))
            },
        ),
        painLastDate = TreeMap(o.o("pain_last_date").mapValues { LocalDate.parse(it.value.jsonPrimitive.content) }),
        has = o.list("has"),
        machines = TreeMap(
            o.o("machines").mapValues { (_, v) ->
                val m = v.jsonObject
                MachineCalibration(m.s("unit"), m.d("step"), m.d("min"), m.dO("max"), m.d("offset_kg"), m.d("ratio"), m.i("rev"))
            },
        ),
        loadProfiles = TreeMap(
            o.o("load_profiles").mapValues { (_, v) ->
                val l = v.jsonObject
                LoadProfile(l["achievable_kg"]!!.jsonArray.map { Dec.norm(BigDecimal(it.jsonPrimitive.content)) }, l.i("rev"))
            },
        ),
        weekPlanSlots = TreeMap(o.o("week_plan_slots").entries.associate { (d, s) -> LocalDate.parse(d) to s.jsonArray.map { it.jsonPrimitive.content } }),
    )
}
