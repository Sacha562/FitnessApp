package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.Value
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.SortedMap
import java.util.UUID

/**
 * One input event of an enrollment (CHALLENGE_ENGINE 4.2). [type] is the DATA_MODEL 9.7.1 `event_type`;
 * [localDate] is the event's local date in the profile zone. Events are folded in `(occurredAt, id)` order
 * (4.3). [supersedesEventId] marks an amendment (`session.amended`) or a retracted pain report.
 */
data class ChallengeEvent(
    val id: UUID,
    val type: String,
    val occurredAt: Instant,
    val localDate: LocalDate,
    val payload: EventPayload,
    val engineFacts: EngineFacts? = null,
    val supersedesEventId: UUID? = null,
) {
    companion object {
        val ORDER: Comparator<ChallengeEvent> = compareBy<ChallengeEvent> { it.occurredAt }.thenBy { it.id.toString() }
    }
}

/** Typed payloads; the app maps them to the snake_case stored keys (4.2) in M2b. */
sealed class EventPayload {
    data object Empty : EventPayload()

    data class Created(
        /** Device-generated UUIDv7 of the enrollment (DATA_MODEL 6.4); the marker uuid of its calendar events. */
        val enrollmentId: UUID,
        /** User choices of `userEditable` params (`challenge_enrollments.params`), e.g. `grip`. */
        val params: SortedMap<String, Value>,
        /** Values entered or imported in the wizard (`onboard.*`). */
        val onboard: SortedMap<String, Value>,
        val import: ImportBlock?,
        val definitionVersion: String,
        val jcsSha256: String,
    ) : EventPayload()

    data class ApprovalChanged(val proposalId: String, val decision: String) : EventPayload()

    data class Session(val session: SessionLog) : EventPayload()

    data class HabitCompleted(val habitId: String, val items: SortedMap<String, ItemLog>) : EventPayload()

    data class Readiness(val elbowScore: Int, val symptomCodes: List<String>) : EventPayload()

    data class Pain(
        val bodyRegion: String,
        val side: String,
        val symptomCodes: List<String>,
        val severity: String,
        val painScore: Int,
        val retracted: Boolean = false,
    ) : EventPayload()

    data class TestDue(val date: LocalDate) : EventPayload()

    data class TestCompleted(
        val testSessionId: UUID,
        val templateId: String,
        /** Raw metric values by `metric_defs` code; LR metrics as [dev.fitnessapp.algo.challenge.fitexpr.SideV]. */
        val metrics: SortedMap<String, Value>,
        /** Protocol code per metric, stored on every observation (2.4.3). */
        val protocolCodes: SortedMap<String, String> = sortedMapOf(),
    ) : EventPayload()

    data class AttemptLogged(val metric: String, val side: String, val success: Boolean) : EventPayload()

    data class WeekBoundary(val isoWeek: String) : EventPayload()

    data class DayTick(val date: LocalDate) : EventPayload()

    data class Pause(val reasonCode: String?) : EventPayload()

    data class Resume(val reasonCode: String?, val gapDays: Long) : EventPayload()

    data class Abandon(val reasonCode: String?) : EventPayload()

    data class ManualOverride(val target: String, val value: Value, val reasonCode: String) : EventPayload()
}

/** The cutover import of D4 / CHALLENGE_ENGINE 2.4.15. */
data class ImportBlock(
    val phase: Int = 1,
    val blockIndex: Int = 1,
    val blockWeek: Int = 1,
    val mode: String = "normal",
    val weekOrdinal: Int = 1,
    val plannedTestDate: LocalDate? = null,
    val vars: SortedMap<String, Value> = sortedMapOf(),
    val pastTests: List<PastTest> = emptyList(),
    val blockItemCounts: SortedMap<String, Int> = sortedMapOf(),
    val snapshotCurrentAsBlockStart: Boolean = true,
)

/** A test done before cutover, imported as observations (C59: no set rows). */
data class PastTest(
    val date: LocalDate,
    val metrics: SortedMap<String, Value>,
    val validForGate: Boolean,
    /** Metrics not comparable with later tests (another protocol); they never appear in `pt`. */
    val notComparable: Set<String> = emptySet(),
    val protocolCodes: SortedMap<String, String> = sortedMapOf(),
)

/** The `session.completed` payload of section 6.12. */
data class SessionLog(
    val sessionId: UUID,
    val slot: String,
    val templateId: String,
    val kind: String,
    val localDate: LocalDate,
    val completionRatio: BigDecimal,
    val painReported: Boolean,
    val durationMin: BigDecimal? = null,
    val items: SortedMap<String, ItemLog>,
)

/** One item of a logged session: its rows and what happened to it. */
data class ItemLog(
    /** `done`, `partial`, `skipped`, `excluded` or `substituted`. */
    val status: String,
    val plannedRows: Int,
    val targetReps: Int? = null,
    val targetHoldS: BigDecimal? = null,
    val rows: List<RowLog>,
    val statusReasonCode: String? = null,
    val substitutionReasonCode: String? = null,
    /** True when a definition substitution with `progressionData: excluded` was used, or the user swapped. */
    val progressionExcluded: Boolean = false,
)

/** One logged row under its `set_logs` meaning (6.12, C38). */
data class RowLog(
    val side: String,
    val setKind: String,
    val setIndex: Int,
    val reps: Int? = null,
    val rirReported: BigDecimal? = null,
    val addedKg: BigDecimal? = null,
    val assistPinNative: BigDecimal? = null,
    val assistKg: BigDecimal? = null,
    val assistOrdinal: Int? = null,
    val durationS: BigDecimal? = null,
    val holdReserveS: BigDecimal? = null,
    val eccSActual: BigDecimal? = null,
    val flags: List<String> = emptyList(),
    val deviationReasonCode: String? = null,
)

/** Event type names (DATA_MODEL 9.7.1). */
object EventTypes {
    const val CREATED = "enrollment.created"
    const val APPROVAL = "rules.approval_changed"
    const val SESSION_STARTED = "session.started"
    const val SESSION_COMPLETED = "session.completed"
    const val SESSION_AMENDED = "session.amended"
    const val SET_LOGGED = "set.logged"
    const val HABIT_COMPLETED = "habit.completed"
    const val READINESS = "readiness.checked"
    const val PAIN = "pain.reported"
    const val TEST_DUE = "test.due"
    const val TEST_COMPLETED = "test.completed"
    const val ATTEMPT = "attempt.logged"
    const val WEEK_CLOSED = "week.closed"
    const val WEEK_STARTED = "week.started"
    const val DAY_TICK = "day.tick"
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val ABANDON = "abandon"
    const val OVERRIDE = "manual.override"
    const val MIGRATED = "definition.migrated"
    const val PHASE_CHANGED = "phase.changed"
    const val BLOCK_STARTED = "block.started"
    const val MODE_CHANGED = "mode.changed"
}
