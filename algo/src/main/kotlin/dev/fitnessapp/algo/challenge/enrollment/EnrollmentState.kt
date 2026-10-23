package dev.fitnessapp.algo.challenge.enrollment

import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.Value
import java.math.BigDecimal
import java.time.LocalDate
import java.util.SortedMap
import java.util.SortedSet
import java.util.UUID

data class BlockPos(val index: Int, val week: Int, val startDate: LocalDate)

/** `plannedTest` of 4.4; [basis] is `blockRelative`, `fixedList`, `postponed` or `none`. */
data class PlannedTest(val date: LocalDate?, val basis: String, val postponedFrom: LocalDate? = null) {
    companion object {
        val NONE = PlannedTest(null, "none")
    }
}

data class Decision(val decision: String, val decidedAt: LocalDate)

/** A valid test kept for `pt.*` (the previous valid test) and gate history. */
data class TestRecord(
    val date: LocalDate,
    val sessionId: UUID?,
    val metrics: SortedMap<String, Value>,
    val protocolCodes: SortedMap<String, String>,
    val notComparable: Set<String> = emptySet(),
)

/** One measurement of the comparison baseline (D41): its value, the test date it comes from and its protocol code. */
data class BaselineValue(val value: Value, val date: LocalDate, val protocolCode: String?)

/** A completed session of an open week, reduced to what `week.closed` rules and `sessionsDone` read. */
data class WeekSession(
    val slot: String?,
    val date: LocalDate,
    val completionRatio: BigDecimal,
    val loggedWorkingRows: Int,
    /** Rows of the items that `week.ex.*` reads (CompiledDefinition.weekItemKeys). */
    val items: SortedMap<String, List<RowLog>>,
)

/**
 * The folded enrollment (CHALLENGE_ENGINE 4.4). Besides the fields of 4.4 it keeps the fold's own
 * bookkeeping: valid sessions per item in the current block (`validInBlock`), the import's pre-cutover
 * counts, the sessions of open weeks (the `week.closed` summary is derived from them, so a late session
 * changes it on refold), the observations of the current phase (`m.*`), the last pain date per body region
 * and the last engine facts (used by synthesized ticks, which carry none).
 */
data class EnrollmentState(
    val enrollmentId: UUID,
    val status: String,
    val mode: String,
    val modeSince: LocalDate,
    val phase: Int,
    val phaseSince: LocalDate,
    val block: BlockPos,
    val weekOrdinal: Int,
    val vars: SortedMap<String, Value>,
    val snapshots: SortedMap<Int, SortedMap<String, Value>>,
    val plannedTest: PlannedTest,
    val lastValidTest: TestRecord?,
    val validTestCount: Int,
    val proposalDecisions: SortedMap<String, Decision>,
    val params: SortedMap<String, Value>,
    val pausedAt: LocalDate?,
    val lastTickDate: LocalDate,
    val lastWeekBoundary: LocalDate,
    val notificationsSent: SortedSet<String>,
    val definitionVersion: String,
    val definitionSha: String,
    val blockValid: SortedMap<String, Int>,
    val importBlock: Int?,
    val importBlockCounts: SortedMap<String, Int>,
    val weekLogs: SortedMap<String, List<WeekSession>>,
    val phaseObservations: SortedMap<String, Value>,
    val painLast: SortedMap<String, LocalDate>,
    val lastChallengeSessionDate: LocalDate?,
    val lastFacts: EngineFacts?,
    val stateRev: Int,
    /** D41: per measurement, the newest value of any gate-valid test; `pt` reads it. */
    val testBaseline: SortedMap<String, BaselineValue> = sortedMapOf(),
) {
    fun approved(proposalId: String): Boolean = proposalDecisions[proposalId]?.decision == "approved"

    companion object {
        const val ACTIVE = "active"
        const val PAUSED = "paused"
        const val COMPLETED = "completed"
        const val ABANDONED = "abandoned"
    }
}
