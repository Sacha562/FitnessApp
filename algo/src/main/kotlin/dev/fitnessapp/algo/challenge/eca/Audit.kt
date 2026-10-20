package dev.fitnessapp.algo.challenge.eca

import java.util.SortedMap
import java.util.UUID

/** DATA_MODEL E29 outcomes of one rule evaluation (5.8). */
enum class Outcome(val code: String) {
    APPLIED("applied"),
    SUPPRESSED_FIRST_WRITER("suppressed_first_writer"),
    SUPPRESSED_LOCK("suppressed_lock"),
    SUPPRESSED_INJURY("suppressed_injury"),
    NO_EFFECT("no_effect"),
    ERROR("error"),
}

/** One proposed write and what became of it. */
data class WriteRecord(
    val target: String,
    val old: String?,
    val new: String,
    val outcome: Outcome,
    val suppressedBy: String? = null,
    val notes: List<String> = emptyList(),
)

/**
 * One `fitness.challenge_rule_events` row (5.8): written for every rule whose condition was true or errored.
 * Values are rendered as text in [bindings] and [writes]; the app stores them under the DATA_MODEL keys.
 */
data class AuditRow(
    val evaluationId: UUID,
    val eventId: UUID,
    val eventType: String,
    val derivedEventType: String?,
    val cascadeDepth: Int,
    val ruleId: String,
    val ruleTag: String,
    val proposalId: String?,
    /** `approved`, `declined` or `not_required` (C48). */
    val ruleDecision: String,
    val stage: String,
    val prio: Int,
    val outcome: Outcome,
    val condValue: Boolean?,
    val evalError: String?,
    val bindings: SortedMap<String, String>,
    val writes: List<WriteRecord>,
    val suppressedBy: String?,
    val explanation: String?,
    val stateHashBefore: String,
    val stateHashAfter: String,
)

/** A queued notification (5.7); [scopeKey] makes `(msgKey, scopeKey)` the dedupe key. */
data class ChallengeNotification(
    val kind: String,
    val msgKey: String,
    val params: SortedMap<String, String>,
    val ruleId: String,
    val scopeKey: String,
)
