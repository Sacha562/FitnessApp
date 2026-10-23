package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.fitexpr.ValueJson
import dev.fitnessapp.algo.core.det.Jcs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * Canonical JSON of [EnrollmentState] with snake_case keys (the cached `challenge_enrollments.state`, 4.4).
 * `state_hash` is its JCS SHA-256 (C24). Engine facts are excluded: they are inputs, not state.
 */
object StateJson {
    private fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(sortedMapOf(*pairs))

    private fun str(s: String?): JsonElement = s?.let { JsonPrimitive(it) } ?: JsonNull

    private fun int(n: Int): JsonElement = ValueJson.number(BigDecimal(n))

    fun encode(s: EnrollmentState): JsonObject = obj(
        "enrollment_id" to JsonPrimitive(s.enrollmentId.toString()),
        "status" to JsonPrimitive(s.status),
        "mode" to JsonPrimitive(s.mode),
        "mode_since" to JsonPrimitive(s.modeSince.toString()),
        "phase" to int(s.phase),
        "phase_since" to JsonPrimitive(s.phaseSince.toString()),
        "block" to obj("index" to int(s.block.index), "week" to int(s.block.week), "start_date" to JsonPrimitive(s.block.startDate.toString())),
        "week_ordinal" to int(s.weekOrdinal),
        "vars" to JsonObject(s.vars.mapValues { ValueJson.encode(it.value) }.toSortedMap()),
        "snapshots" to JsonObject(
            s.snapshots.entries.associate { (k, v) -> k.toString() to JsonObject(v.mapValues { ValueJson.encode(it.value) }.toSortedMap()) }.toSortedMap(),
        ),
        "planned_test" to obj(
            "date" to str(s.plannedTest.date?.toString()), "basis" to JsonPrimitive(s.plannedTest.basis),
            "postponed_from" to str(s.plannedTest.postponedFrom?.toString()),
        ),
        "last_valid_test" to (s.lastValidTest?.let(::test) ?: JsonNull),
        "valid_test_count" to int(s.validTestCount),
        "test_baseline" to JsonObject(
            s.testBaseline.mapValues { (_, b) ->
                obj("value" to ValueJson.encode(b.value), "date" to JsonPrimitive(b.date.toString()), "protocol_code" to str(b.protocolCode))
            }.toSortedMap(),
        ),
        "proposal_decisions" to JsonObject(
            s.proposalDecisions.mapValues { obj("decision" to JsonPrimitive(it.value.decision), "decided_at" to JsonPrimitive(it.value.decidedAt.toString())) }.toSortedMap(),
        ),
        "params" to JsonObject(s.params.mapValues { ValueJson.encode(it.value) }.toSortedMap()),
        "paused_at" to str(s.pausedAt?.toString()),
        "last_tick_date" to JsonPrimitive(s.lastTickDate.toString()),
        "last_week_boundary" to JsonPrimitive(s.lastWeekBoundary.toString()),
        "notifications_sent" to JsonArray(s.notificationsSent.map { JsonPrimitive(it) }),
        "definition_version" to JsonPrimitive(s.definitionVersion),
        "definition_sha" to JsonPrimitive(s.definitionSha),
        "block_valid" to JsonObject(s.blockValid.mapValues { int(it.value) }.toSortedMap()),
        "import_block" to (s.importBlock?.let(::int) ?: JsonNull),
        "import_block_counts" to JsonObject(s.importBlockCounts.mapValues { int(it.value) }.toSortedMap()),
        "week_logs" to JsonObject(s.weekLogs.mapValues { (_, l) -> JsonArray(l.map(::weekSession)) }.toSortedMap()),
        "phase_observations" to JsonObject(s.phaseObservations.mapValues { ValueJson.encode(it.value) }.toSortedMap()),
        "pain_last" to JsonObject(s.painLast.mapValues { JsonPrimitive(it.value.toString()) }.toSortedMap()),
        "last_challenge_session_date" to str(s.lastChallengeSessionDate?.toString()),
        "state_rev" to int(s.stateRev),
    )

    private fun test(t: TestRecord): JsonObject = obj(
        "date" to JsonPrimitive(t.date.toString()),
        "session_id" to str(t.sessionId?.toString()),
        "metrics" to JsonObject(t.metrics.mapValues { ValueJson.encode(it.value) }.toSortedMap()),
        "protocol_codes" to JsonObject(t.protocolCodes.mapValues { JsonPrimitive(it.value) }.toSortedMap()),
        "not_comparable" to JsonArray(t.notComparable.sorted().map { JsonPrimitive(it) }),
    )

    private fun weekSession(w: WeekSession): JsonObject = obj(
        "slot" to str(w.slot),
        "date" to JsonPrimitive(w.date.toString()),
        "completion_ratio" to ValueJson.number(w.completionRatio),
        "logged_working_rows" to int(w.loggedWorkingRows),
        "items" to JsonObject(w.items.mapValues { (_, rows) -> JsonArray(rows.map(::row)) }.toSortedMap()),
    )

    fun row(r: RowLog): JsonObject = JsonObject(
        sortedMapOf<String, JsonElement>(
            "side" to JsonPrimitive(r.side), "set_kind" to JsonPrimitive(r.setKind), "set_index" to int(r.setIndex),
        ).also { m ->
            r.reps?.let { m["reps"] = int(it) }
            r.rirReported?.let { m["rir_reported"] = ValueJson.number(it) }
            r.addedKg?.let { m["added_kg"] = ValueJson.number(it) }
            r.assistPinNative?.let { m["assist_pin_native"] = ValueJson.number(it) }
            r.assistKg?.let { m["assist_kg"] = ValueJson.number(it) }
            r.assistOrdinal?.let { m["assist_ordinal"] = int(it) }
            r.durationS?.let { m["duration_s"] = ValueJson.number(it) }
            r.holdReserveS?.let { m["hold_reserve_s"] = ValueJson.number(it) }
            r.eccSActual?.let { m["ecc_s_actual"] = ValueJson.number(it) }
            if (r.flags.isNotEmpty()) m["flags"] = JsonArray(r.flags.sorted().map { JsonPrimitive(it) })
            r.deviationReasonCode?.let { m["deviation_reason_code"] = JsonPrimitive(it) }
        },
    )

    fun hash(s: EnrollmentState): String = Jcs.sha256Hex(encode(s))
}
