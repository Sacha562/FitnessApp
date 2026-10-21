package dev.fitnessapp.app.ui.challenges

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.eca.ChallengeNotification
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.DateV
import dev.fitnessapp.algo.challenge.fitexpr.NullV
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.StrV
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.app.uidet.UiGateRows
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Plain-language labels of the challenge screens (UX 14), read from the definition where it has text. */
object ChallengeTexts {
    val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun mode(code: String): String = when (code) {
        "normal" -> "Normal"
        "deload" -> "Deload"
        "test_pending" -> "Test pending"
        "extra_deload" -> "Extra deload"
        "injury" -> "Elbow protocol"
        else -> code
    }

    fun status(code: String): String = when (code) {
        EnrollmentState.ACTIVE -> "Active"
        EnrollmentState.PAUSED -> "Paused"
        EnrollmentState.COMPLETED -> "Completed"
        EnrollmentState.ABANDONED -> "Abandoned"
        "onboarding" -> "Setting up"
        else -> code
    }

    fun title(def: CompiledDefinition): String = def.doc.title["en"] ?: def.slug

    /** The first sentence of the definition's summary: the one line a card or setup shows (D35). */
    fun tagline(def: CompiledDefinition): String? = def.doc.summary["en"]?.let { s ->
        val end = s.indexOf(". ")
        if (end < 0) s else s.substring(0, end + 1)
    }

    /** Readable names of user-editable params (setup, UX 14.8); the param name otherwise. */
    fun paramLabel(name: String): String = when (name) {
        "grip" -> "Grip"
        "hangMeasureWeekday" -> "Day of the weekly hang measurement"
        else -> name
    }

    fun paramValue(v: String): String = when (v) {
        "MON" -> "Mon"; "TUE" -> "Tue"; "WED" -> "Wed"; "THU" -> "Thu"; "FRI" -> "Fri"; "SAT" -> "Sat"; "SUN" -> "Sun"
        else -> v.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun phaseName(def: CompiledDefinition, id: Int): String = def.doc.phases.firstOrNull { it.id == id }?.name?.get("en") ?: "Phase $id"

    /** The definition's message for [n] with its `{placeholders}` filled from the notification's parameters. */
    fun message(def: CompiledDefinition, n: ChallengeNotification): String {
        var text = def.doc.messages[n.msgKey]?.get("en") ?: n.msgKey.replace('_', ' ')
        for ((k, v) in n.params) text = text.replace("{$k}", v)
        return text
    }

    fun ruleExplain(def: CompiledDefinition, ruleId: String): String =
        def.doc.rules.firstOrNull { it.id == ruleId }?.explain?.get("en") ?: if (ruleId == "manual.override") "Corrected by you" else ruleId

    fun value(v: Value?, metric: String? = null): String = when (v) {
        null, is NullV -> "–"
        is QV -> UiGateRows.text(v, metric, precise = true)
        is BoolV -> if (v.b) "yes" else "no"
        is StrV -> v.s
        is DateV -> DAY.format(v.d)
        else -> v.toString()
    }

    fun date(d: LocalDate?): String = d?.let(DAY::format) ?: "–"

    /** A clock time as hours and minutes ("19:45"), also for a done session's actual start with seconds. */
    fun time(t: java.time.LocalTime): String = t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

    /** Readable names of the state vars a user may correct (CHALLENGE_ENGINE 4.9); the var name otherwise. */
    fun varLabel(name: String): String = VAR_LABELS[name] ?: name

    private val VAR_LABELS = mapOf(
        "wpuA" to "Weighted pull-up, session A", "wpuB" to "Weighted pull-up, session B", "wpuC" to "Weighted pull-up, session C (anchor)",
        "negB" to "Negatives, session B", "negReset" to "Negatives reset level", "pinA" to "Assisted pin, session A", "pinC" to "Assisted pin, session C",
        "pinHold90" to "Assisted pin, 90-degree hold", "sthS" to "Straight-arm hold", "plankS" to "Plank hold", "plankLoadKg" to "Plank plate",
        "oaHangS" to "One-arm hang target", "kgPerRep" to "Kg per rep", "e1rmAdded" to "Gate e1RM", "bVariant" to "Session B variant",
        "fingerLevel" to "Finger assistance level", "needsCleanSession" to "Test waits for a clean session",
    )

    /** A state var's enum value as a chip or line shows it (D35: no raw codes). */
    fun enumValue(v: String): String = when (v) {
        "6x2" -> "6 × 2"
        "ladder123" -> "Ladder 1-2-3"
        else -> paramValue(v)
    }

    /** Readable test metric names (Tests tab); the code with spaces otherwise. */
    fun metricLabel(code: String): String = METRIC_LABELS[code] ?: code.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private val METRIC_LABELS = mapOf(
        "bw" to "Bodyweight", "two_arm_hang_best_s" to "Two-arm hang, best", "wpu_3rm_added" to "Weighted pull-up 3RM",
        "wpu_1rm_added" to "Weighted pull-up 1RM", "wpu_e1rm_added" to "Weighted pull-up 1RM, estimated",
        "wpu_e1rm_added_m110" to "Weighted pull-up 1RM, ×1.10 estimate", "bw_max_reps" to "Max strict pull-ups",
        "oa_hang_s" to "One-arm dead hang", "oa_top_hold_s" to "One-arm top hold", "oa_least_assist_pin" to "Least assistance for one rep",
        "oa_least_assist_kg" to "Least assistance for one rep", "oa_neg5_unassisted" to "One-arm 5 s negative, no help",
        "oa_one_finger_rep" to "One-arm rep with one finger of help", "oa_rep" to "Strict one-arm pull-up",
    )

    /** Why a rule's change did not go through (rule audit outcome codes, CHALLENGE_ENGINE 5.8). */
    fun outcome(code: String): String = when (code) {
        "suppressed_first_writer" -> "another rule changed it first"
        "suppressed_lock" -> "the value is locked"
        "suppressed_injury" -> "the elbow protocol is on"
        else -> code.replace('_', ' ')
    }

    /** A history entry's title for an event type without its own label. */
    fun eventType(type: String): String = when (type) {
        "session.started" -> "Session started"
        "set.logged" -> "Set logged"
        "habit.completed" -> "Home habit done"
        "readiness.checked" -> "Elbow check"
        "test.due" -> "Test day due"
        "week.started" -> "New week"
        "day.tick" -> "Daily update"
        "definition.migrated" -> "Program updated"
        "phase.changed" -> "New phase"
        "block.started" -> "New block"
        "mode.changed" -> "Mode changed"
        else -> type.replace('.', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
