package dev.fitnessapp.algo.challenge.materialize

import dev.fitnessapp.algo.challenge.cds.I18n
import java.math.BigDecimal
import java.time.LocalDate

/** How the logger records a row (6.8, D9). */
enum class LogMode(val code: String) {
    DONE("done"), RIR_CHIPS("rir_chips"), HOLD_CHIPS("hold_chips"), ECC_SECONDS("ecc_seconds"),
    ATTEMPT_CHECKLIST("attempt_checklist"), TEST_RECORD("test_record"),
}

/** One prescribed row (6.1). Masses are kg on the 0.01 grid, pins in the machine's unit, seconds on 0.1. */
data class PlannedRow(
    val side: String,
    val setKind: String,
    val setIndex: Int,
    val pairIndex: Int? = null,
    val targetReps: Int? = null,
    val targetRepsMax: Int? = null,
    val targetHoldS: BigDecimal? = null,
    val targetHoldMaxS: BigDecimal? = null,
    val targetEccS: BigDecimal? = null,
    val durationS: BigDecimal? = null,
    val addedKg: BigDecimal? = null,
    val plateBreakdown: List<BigDecimal>? = null,
    val systemLoadKg: BigDecimal? = null,
    val pin: BigDecimal? = null,
    val assistKg: BigDecimal? = null,
    val oneArmLoadKg: BigDecimal? = null,
    val fingers: Int? = null,
    val attempts: Int? = null,
    val restS: Int? = null,
    val logMode: LogMode,
    /** `planned_source = challenge_rule` once an in-session rule changed this row (6.10). */
    val changedByRule: String? = null,
)

/** A non-blocking warning on an item: WE-S2 (RULE load above the generic cap), `not_loadable`, `could_not_compute`, ... */
data class PlanWarning(val code: String, val severity: String, val detail: String)

data class PlannedItem(
    val key: String,
    val exerciseKey: String?,
    val catalogSlug: String?,
    val name: I18n,
    val role: String,
    /** `planned`, `skipped` or `excluded`. */
    val status: String,
    val statusReason: String? = null,
    val rows: List<PlannedRow>,
    val rirTarget: Int? = null,
    val flagsOffered: List<String> = emptyList(),
    val stopSetOn: List<String> = emptyList(),
    val notes: List<I18n> = emptyList(),
    /** `rule`, `none` or `delegate:rx` (2.4.7). */
    val progressionMode: String,
    val inSessionRuleIds: List<String> = emptyList(),
    val calibrationFirstSet: Boolean = false,
    val metric: String? = null,
    val record: String? = null,
    val substitutedFrom: String? = null,
    val progressionExcluded: Boolean = false,
    val warnings: List<PlanWarning> = emptyList(),
    val noteCodes: List<String> = emptyList(),
)

/** Reproducibility snapshot of a plan (C24). */
data class PlanSnapshot(
    val definitionSha: String,
    val engineVersion: String,
    val paramsHash: String,
    val catalogVersion: String?,
    val stateRev: Int,
)

/** CHALLENGE_ENGINE 6.1 `SessionPlan` (the challenge block; the general add-on block is built by SEL-1 in M4). */
data class SessionPlan(
    val slot: String,
    val templateId: String,
    val phase: Int,
    val mode: String,
    val date: LocalDate,
    val kind: String,
    val bookedMin: Int,
    val budgetMin: Int,
    val items: List<PlannedItem>,
    val estMin: BigDecimal?,
    val addOnCapacityMin: Int?,
    val addOnOffered: Boolean,
    val warnings: List<PlanWarning>,
    val snapshot: PlanSnapshot,
)
