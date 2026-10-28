package dev.fitnessapp.algo.core.safety

import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamType
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.core.trace.TraceStep
import dev.fitnessapp.algo.selection.catalog.CatalogDomains
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId

/**
 * One pain report (ALGORITHMS_PLANNING 2.2, C33): a `wellness_logs` row of kind `pain`, or an event derived from an
 * elbow check ([Safety.elbowCheckEvent]). [severity] is 1 mild, 2 moderate, 3 sharp ([Safety.severityOfChip],
 * [Safety.severityOfScore]); [bodyRegion] is a `body_regions` code.
 */
data class PainEvent(
    val id: String,
    val ts: Instant,
    val source: String,
    val bodyRegion: String,
    val side: String,
    val severity: Int,
    val symptomCodes: List<String> = emptyList(),
    val exerciseId: String? = null,
    val sessionId: String? = null,
)

/** The user tapped "pain gone" for a joint key. */
data class PainClear(val id: String, val ts: Instant, val joint: String)

/** A challenge safety mode as L2 computed it (read-only here): its `modeEffects` are merged into the state. */
data class ChallengeModeEffect(
    val enrollmentId: String,
    val mode: String,
    val since: Instant,
    val forbidSessionTags: List<String> = emptyList(),
    val forbidExerciseTags: List<String> = emptyList(),
    val jointFlags: Map<String, Int> = emptyMap(),
)

data class SafetyInput(
    val painEvents: List<PainEvent>,
    val clears: List<PainClear>,
    val challengeModes: List<ChallengeModeEffect>,
    /** `body_regions.joint_keys`: body region code -> joint keys (an empty list gives a notice and no flag). */
    val regionJoints: Map<String, List<String>>,
    val now: Instant,
    val zone: ZoneId,
)

/** A joint flag; [until] is null while a challenge mode holds it (no end). */
data class JointFlag(val joint: String, val severity: Int, val since: Instant, val until: Instant?, val sourceEventIds: List<String>)

/** A safety notice: `sharp_pain` per severity-3 pain flag, `challenge_mode` per merged mode, `pain_no_joint` per region without a joint key. */
data class SafetyNotice(val code: String, val joint: String? = null, val region: String? = null, val mode: String? = null)

data class SafetyState(
    val asOf: Instant,
    val jointFlags: List<JointFlag>,
    val forbidSessionTags: List<String>,
    val forbidExerciseTags: List<String>,
    val challengeModes: List<ChallengeModeEffect>,
    val notices: List<SafetyNotice>,
    val fingerprint: String,
    val trace: List<TraceStep>,
) {
    val disclaimerRequired: Boolean get() = true

    /** joint -> severity, the shape INJ-1 and ELIG-1 read. */
    val flagMap: Map<String, Int> get() = jointFlags.associate { it.joint to it.severity }
}

/**
 * `safety@1.0.0` SafetyState.v1 (ALGORITHMS_PLANNING 2, C33, D10): every pain report, pain clear and challenge
 * safety mode becomes one state that the planner, ELIG-1/INJ-1 and the session screens read. It runs before
 * every other module on each event; a new [SafetyState.fingerprint] is a replan trigger. Safety is always
 * enforced, whatever the evidence mode.
 */
object Safety : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("safety")

    private const val TAG_UNIT = "enum list (exercise/session tags)"

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.table("safety.elbow_check_severity", "severity (by elbow score 1-3)", 1.0, 3.0),
        ParamSpec.integer("safety.escalate_count", "events", 2.0, 5.0),
        ParamSpec.integer("safety.escalate_window_days", "days", 7.0, 28.0),
        ParamSpec.table("safety.hold_days", "days (by severity 1-3)", 3.0, 28.0),
        ParamSpec.integer("safety.lookback_days", "days", 14.0, 56.0),
        ParamSpec.integer("safety.pain_free_min_days", "days", 1.0, 7.0),
        ParamSpec.table("safety.severity_map", "severity (by pain chip)", 1.0, 3.0),
    ) + CatalogDomains.JOINTS.flatMap { j -> (1..3).map { ParamSpec("safety.joint_forbid_tags.$j.$it", ParamType.ENUM_ARRAY, TAG_UNIT) } }

    /** Pain sheet chip (mild, moderate, sharp) -> severity 1-3 (`safety.severity_map`). */
    fun severityOfChip(chip: String, params: ParamView): Int =
        requireNotNull(params.table("safety.severity_map")[chip]) { "unknown pain chip $chip" }.toInt()

    /** A 0-10 pain score -> severity (1-3 -> 1, 4-6 -> 2, 7-10 -> 3; 2.2); 0 is no pain. */
    fun severityOfScore(score: Int): Int? = when {
        score <= 0 -> null
        score <= 3 -> 1
        score <= 6 -> 2
        else -> 3
    }

    /** The readiness elbow check (0-3, F15) as a pain event on `elbow_anterior`; null for score 0. */
    fun elbowCheckEvent(id: String, ts: Instant, score: Int, params: ParamView): PainEvent? {
        if (score <= 0) return null
        val sev = requireNotNull(params.table("safety.elbow_check_severity")[score.toString()]) { "elbow score $score" }.toInt()
        return PainEvent(id, ts, "readiness_elbow_check", "elbow_anterior", "both", sev)
    }

    fun evaluate(input: SafetyInput, params: ParamView): SafetyState {
        val zone = input.zone
        val now = input.now
        val lookback = params.int("safety.lookback_days").toLong()
        val escCount = params.int("safety.escalate_count")
        val escWindow = params.int("safety.escalate_window_days").toLong()
        val holdDays = params.table("safety.hold_days")
        val painFree = params.int("safety.pain_free_min_days").toLong()
        val trace = ArrayList<TraceStep>()
        val today = Week.localDate(now, zone)
        val events = input.painEvents.sortedWith(compareBy({ it.ts }, { it.id }))
            .filter { it.ts <= now && Week.calendarDays(Week.localDate(it.ts, zone), today) < lookback }

        val flags = sortedMapOf<String, JointFlag>()
        for (joint in CatalogDomains.JOINTS) {
            val e = events.filter { joint in input.regionJoints[it.bodyRegion].orEmpty() }
            if (e.isEmpty()) continue
            var sev = e.maxOf { it.severity }
            if (e.count { Week.calendarDays(Week.localDate(it.ts, zone), today) < escWindow } >= escCount && sev < 3) {
                sev += 1
                trace += TraceStep("safety.escalate", joint)
            }
            val last = e.last().ts
            val lastDate = Week.localDate(last, zone)
            var until = endOfLocalDay(lastDate.plusDays(requireNotNull(holdDays[sev.toString()]).toLong()), zone)
            val clear = input.clears.filter { it.joint == joint && it.ts > last && it.ts <= now }.maxByOrNull { it.ts }
            if (clear != null && (sev == 1 || Week.calendarDays(lastDate, Week.localDate(clear.ts, zone)) >= painFree)) {
                until = clear.ts
                trace += TraceStep("safety.cleared", joint)
            }
            if (now < until) flags[joint] = JointFlag(joint, sev, e.first().ts, until, e.map { it.id })
        }
        val modes = input.challengeModes.sortedWith(compareBy({ it.enrollmentId }, { it.mode }))
        for (m in modes) {
            for ((joint, sev) in m.jointFlags.toSortedMap()) {
                val prev = flags[joint]
                // A mode flag lasts while the mode does; on equal severity it wins because it has no end.
                if (prev == null || sev >= prev.severity) flags[joint] = JointFlag(joint, sev, m.since, null, emptyList())
            }
        }
        val sessTags = sortedSetOf<String>()
        val exTags = sortedSetOf<String>()
        for (fl in flags.values) sessTags += params.strings("safety.joint_forbid_tags.${fl.joint}.${fl.severity}")
        for (m in modes) {
            sessTags += m.forbidSessionTags
            exTags += m.forbidExerciseTags
        }
        val notices = ArrayList<SafetyNotice>()
        flags.values.filter { it.severity == 3 && it.sourceEventIds.isNotEmpty() }.forEach { notices += SafetyNotice("sharp_pain", joint = it.joint) }
        events.filter { input.regionJoints[it.bodyRegion].isNullOrEmpty() }.map { it.bodyRegion }.toSortedSet()
            .forEach { notices += SafetyNotice("pain_no_joint", region = it) }
        modes.forEach { notices += SafetyNotice("challenge_mode", mode = it.mode) }

        val list = flags.values.toList()
        return SafetyState(now, list, sessTags.toList(), exTags.toList(), modes, notices, fingerprint(list, sessTags, exTags, modes), trace)
    }

    /** The first instant of the local day after [d] (the "end of" [d]); DST-safe through the zone rules. */
    private fun endOfLocalDay(d: java.time.LocalDate, zone: ZoneId): Instant = d.plusDays(1).atStartOfDay(zone).toInstant()

    /** sha256 of (flags, tags, modes) in JCS form: what changes it is a replan trigger (2.3). */
    private fun fingerprint(flags: List<JointFlag>, sess: Set<String>, ex: Set<String>, modes: List<ChallengeModeEffect>): String =
        Jcs.sha256Hex(
            buildJsonObject {
                put("flags", buildJsonArray { flags.forEach { add(buildJsonObject { put("joint", it.joint); put("severity", it.severity) }) } })
                put("session_tags", buildJsonArray { sess.forEach { add(JsonPrimitive(it)) } })
                put("exercise_tags", buildJsonArray { ex.forEach { add(JsonPrimitive(it)) } })
                put("modes", buildJsonArray { modes.forEach { add(buildJsonObject { put("enrollment", it.enrollmentId); put("mode", it.mode) }) } })
            },
        )
}
