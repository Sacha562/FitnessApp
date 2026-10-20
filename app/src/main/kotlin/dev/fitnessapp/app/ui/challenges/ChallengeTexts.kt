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
}
