package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.planning.today.Excluded
import dev.fitnessapp.algo.planning.today.Reason
import dev.fitnessapp.algo.planning.today.TodayOption
import dev.fitnessapp.algo.planning.today.TodayOptions
import dev.fitnessapp.algo.planning.today.TodayRecommender
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** The card states of UX 9.3 (ALGORITHMS_PLANNING 19.1). */
enum class CardState { SETUP_NEEDED, IN_PROGRESS, DONE, PLANNED, PLANNED_BUT_REST, OPTIONAL, REST, ENGINE_ERROR }

/** One reason chip: its code, precedence class and text; [muted] when no template knows the code (UX 19.1). */
data class CardChip(val code: String, val cls: Int, val text: String, val muted: Boolean = false)

/**
 * `TodayCard` of 19.1: the state and its variant (`CHALLENGE`, `TEST`, `GENERAL` for PLANNED; `SOFT`, `HARD` for the rest
 * states; `CLOSE`, `CLEAR` for OPTIONAL), the primary option (the engine's, never re-sorted), the alternatives, the
 * chips and the "Not today" lines.
 */
data class TodayCard(
    val state: CardState,
    val variant: String?,
    val primary: TodayOption?,
    val alternatives: List<TodayOption>,
    val alternativesInline: Boolean,
    val chips: List<CardChip>,
    val notToday: List<String>,
    val unknownCodes: List<String>,
)

/**
 * `ui.todaycard` (UI-01, ALGORITHMS_PLANNING 19.1, UX 19.1): maps `today`'s verdict and options to a card. It applies
 * no thresholds of its own except the display-only close-call test (C53: the second option within `close_rel` of the
 * primary's score). Pure; [groupLabel] gives the taxonomy's English group names.
 */
object UiTodayCard {
    fun card(
        today: TodayOptions?,
        activeSession: Boolean,
        finishedToday: Boolean,
        engineError: Boolean,
        setupNeeded: Boolean,
        now: Instant,
        zone: ZoneId,
        params: ParamView,
        groupLabel: (String) -> String,
    ): TodayCard {
        val none = TodayCard(CardState.ENGINE_ERROR, null, null, emptyList(), false, emptyList(), emptyList(), emptyList())
        if (activeSession) return none.copy(state = CardState.IN_PROGRESS)
        if (today == null) return if (setupNeeded) none.copy(state = CardState.SETUP_NEEDED) else none
        val maxChips = params.int("ui.todaycard.max_chips")
        val maxAlts = params.int("ui.todaycard.max_alts")
        val unknown = ArrayList<String>()
        fun chips(rs: List<Reason>): List<CardChip> = rs
            .sortedWith(compareBy<Reason>({ it.cls }, { -kotlin.math.abs(it.weight) }, { it.code }))
            .distinctBy { it.code + "|" + (it.params["group"] ?: it.params["family"] ?: it.params["slot"] ?: "") }
            .take(maxChips)
            .map { r -> text(r.code, r.params, now, zone, groupLabel)?.let { CardChip(r.code, r.cls, it) } ?: CardChip(r.code, r.cls, r.code, muted = true).also { unknown += r.code } }
        val notToday = notToday(today.excluded, params.int("ui.todaycard.not_today_max"), now, zone, groupLabel)
        val primary = today.options.firstOrNull { it.optionId == today.primaryOptionId }
        val others = today.options.filter { it.optionId != today.primaryOptionId }
        val planned = today.planned
        if (planned == null && finishedToday) return TodayCard(CardState.DONE, null, null, emptyList(), false, emptyList(), emptyList(), emptyList())
        val v = today.verdict
        if (planned != null) {
            val variant = when (planned.kind) { "test" -> "TEST"; "challenge" -> "CHALLENGE"; else -> "GENERAL" }
            if (v == TodayRecommender.REST_HARD && variant != "TEST") {
                return TodayCard(CardState.PLANNED_BUT_REST, "HARD", null, emptyList(), false, chips(today.restReasons), notToday, unknown)
            }
            if (v == TodayRecommender.REST_SOFT && variant == "GENERAL") {
                return TodayCard(CardState.PLANNED_BUT_REST, "SOFT", primary, others.take(maxAlts), false, chips(today.restReasons), notToday, unknown)
            }
            return TodayCard(CardState.PLANNED, variant, primary, others.take(maxAlts), false, chips(primary?.reasons.orEmpty()), notToday, unknown)
        }
        if (v != TodayRecommender.TRAIN) {
            val hard = v == TodayRecommender.REST_HARD
            return TodayCard(CardState.REST, if (hard) "HARD" else "SOFT", null, if (hard) emptyList() else today.options, false, chips(today.restReasons), notToday, unknown)
        }
        val second = others.sortedWith(compareByDescending<TodayOption> { it.score }.thenBy { o -> o.sessionType?.let { SessionType.of(it).ordinal } ?: 0 }).firstOrNull()
        val close = primary != null && second != null && primary.score > 0.0 && second.score >= primary.score * (1.0 - params.double("ui.todaycard.close_rel")) - 1e-12
        return TodayCard(
            CardState.OPTIONAL, if (close) "CLOSE" else "CLEAR", primary, others.take(maxAlts), close, chips(primary?.reasons.orEmpty()), notToday, unknown,
        )
    }

    /** "Not today" lines (UX 19.1): the excluded families with their rule, by precedence class then family order. */
    fun notToday(excluded: List<Excluded>, max: Int, now: Instant, zone: ZoneId, groupLabel: (String) -> String): List<String> =
        excluded.sortedWith(compareBy<Excluded>({ it.cls }, { familyRank(it.subject) }))
            .mapNotNull { x ->
                val p = x.params
                when (x.reasonCode) {
                    "PROTECT_REST" -> "${family(x.subject)}: ${list(p["groups"].orEmpty().split(',').filter { it.isNotEmpty() }.map(groupLabel))} " +
                        "${if ((p["groups"] ?: "").contains(',')) "are" else "is"} protected; session ${p["slot"]} ${whenText(p["when"], now, zone)}"
                    "GAP_DAY" -> "${groupLabel(x.subject)}: light pull only next to ${slotText(p["slot"])}"
                    "HEAVY_LOWER_GAP" -> "${typeLabel(x.subject)}: heavy legs too recently"
                    else -> null
                }
            }
            .distinct()
            .take(max)

    /** The English chip template of [code] (UX 19.1.1), or null when there is none. */
    fun text(code: String, p: Map<String, String>, now: Instant, zone: ZoneId, groupLabel: (String) -> String): String? = when (code) {
        "SAFETY_PROTOCOL" -> listOfNotNull(p["protocol"], p["day"]?.let { "day $it of ${p["days"]}" }).joinToString(" · ").ifEmpty { "Safety protocol" }
        "SAFETY_JOINT" -> "${p["joint"]?.replaceFirstChar(Char::uppercase) ?: "Joint"} rests today"
        "REST_BEFORE" -> "Rest before ${slotText(p["slot"])} ${whenText(p["when"], now, zone)}"
        "PROTECT_REST" -> "${family(p["family"] ?: "PULL")} rests for ${p["slot"]} ${whenText(p["when"], now, zone)}"
        "GAP_DAY" -> "Gap day next to ${p["slot"]}"
        "NEXT_FIXED" -> p["at"]?.let { at -> val t = Instant.parse(at).atZone(zone); "${slotShort(p["slot"])} ${t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${hhmm(t.hour, t.minute)}" }
        "DELOAD_WEEK" -> "Deload week"
        "PLANNED_CAL" -> p["at"]?.let { at -> val t = Instant.parse(at).atZone(zone); "Planned ${hhmm(t.hour, t.minute)}" }
        "MAX_VISITS" -> "${p["used"]} of ${p["max"]} gym visits used"
        "BUDGET_FIT" -> "Fits ${p["minutes"]} min"
        "NO_SLOT" -> "No free slot"
        "VOLUME_DEFICIT" -> "${groupLabel(p["group"] ?: "")} ${num(p["done"])}/${num(p["target"])} wk"
        "RECOVERING" -> "Still recovering"
        "REENTRY" -> "First ${p["family"] ?: "session"} in a while"
        "BETTER_OTHER_DAY" -> "${p["type"]} fits better on ${p["day"]?.lowercase()?.replaceFirstChar(Char::uppercase)}"
        "LOW_SYSTEMIC" -> "Whole-body readiness ${p["pct"]} %"
        "HEAVY_LOWER_GAP" -> "Heavy legs too recently"
        "RUN_NEAR" -> "Run nearby"
        "ADDON" -> "${family(p["family"] ?: "")} add-on after ${p["slot"]} (~${p["minutes"]?.let { num(it) }} min)"
        "NOT_NEEDED" -> "Targets on track"
        else -> null
    }

    private fun family(code: String): String = when (code) {
        "PUSH+LEGS" -> "Legs + push"
        else -> code.lowercase().replaceFirstChar(Char::uppercase)
    }

    private fun typeLabel(code: String): String = runCatching { SessionType.of(code) }.getOrNull()?.let {
        when (it) {
            SessionType.PUSH_LEGS -> "Legs + push"
            SessionType.PULL_LEGS -> "Pull + legs"
            SessionType.FULL -> "Full body"
            else -> it.code.lowercase().replaceFirstChar(Char::uppercase)
        }
    } ?: family(code)

    private fun familyRank(subject: String): Int = listOf("PUSH", "PULL", "LEGS", "CORE").indexOf(subject).let { if (it >= 0) it else 10 }

    private fun slotText(slot: String?): String = if (slot == "TEST") "test" else slot ?: ""

    private fun slotShort(slot: String?): String = if (slot == "TEST") "Test" else slot ?: ""

    /** `{when}` (UX 19.1.1): today, tomorrow, the weekday within the next 6 days, else "Wed 14 Oct". */
    fun whenText(at: String?, now: Instant, zone: ZoneId): String {
        val t = at?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
        val d = t.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, d)
        return when {
            days == 0L -> "today"
            days == 1L -> "tomorrow"
            days in 2L..6L -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            else -> dateText(d)
        }
    }

    private fun dateText(d: LocalDate): String =
        "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

    private fun hhmm(h: Int, m: Int): String = (if (h < 10) "0$h" else "$h") + ":" + (if (m < 10) "0$m" else "$m")

    /** A grid number as the chips show it: up to one decimal, decimal point (D12). */
    private fun num(s: String?): String {
        val x = s?.toDoubleOrNull() ?: return s ?: ""
        return dev.fitnessapp.algo.core.det.Canon.esNumber(x, dev.fitnessapp.algo.core.det.GridKind.S)
    }

    private fun list(xs: List<String>): String = when (xs.size) {
        0 -> ""
        1 -> xs[0]
        else -> xs.dropLast(1).joinToString(", ") + " and " + xs.last()
    }
}
