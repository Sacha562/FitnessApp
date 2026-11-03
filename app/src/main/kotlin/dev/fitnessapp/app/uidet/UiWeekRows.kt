package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.challenge.calendar.CalendarLink
import dev.fitnessapp.algo.challenge.calendar.WritePrompt
import dev.fitnessapp.algo.core.calendar.Anchor
import dev.fitnessapp.algo.core.calendar.AnchorKind
import dev.fitnessapp.algo.core.calendar.CalendarInstance
import dev.fitnessapp.algo.core.calendar.Marker
import dev.fitnessapp.algo.core.calendar.SlotKey
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.planning.weekplan.FixedItem
import dev.fitnessapp.algo.planning.weekplan.PlanItem
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.algo.planning.weekplan.WeeklyPlanner
import dev.fitnessapp.app.calendar.CalendarPrompt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One calendar event under a Week row (UX 10.3 CALENDAR part). */
data class WeekEvent(
    val instanceKey: String,
    val title: String,
    val time: String,
    /** The event carries the app's marker (`[App]`); otherwise it is the user's own (`[E]`). */
    val app: Boolean,
    /** "Moved by you", "Edited by you: ...", "planned (not recommended)", or null. */
    val note: String?,
    val kind: AnchorKind,
    val marker: Marker?,
)

/** The one action a Week row offers on its right (D82 (1)). */
sealed interface WeekAction {
    data class PlanIt(val item: PlanItem) : WeekAction
    data class Adopt(val prompt: CalendarPrompt) : WeekAction
    data class AddToCalendar(val prompt: CalendarPrompt) : WeekAction
}

/**
 * One stacked Week row (UX 10.3, D82 (1)): the planner's line on top ([symbol], [title], [detail]), the day's calendar
 * events below it, and one action. [item] is the day's general session (recommended or planned) for the day sheet.
 */
data class WeekRow(
    val date: LocalDate,
    /** `v` done, `*` challenge or planned, `T` test, `o` recommended general, `x` blocked, `-` rest or past. */
    val symbol: String,
    val title: String,
    val detail: String?,
    val events: List<WeekEvent>,
    val action: WeekAction?,
    val item: PlanItem?,
    /** The plan gives the day's planned session nothing to do (D82 (4) "planned (not recommended)"). */
    val notRecommended: Boolean,
    /** `done`, `challenge`, `test`, `planned`, `recommended`, `blocked`, `rest`, `past` (`week_day_opened.row_kind`). */
    val kind: String,
)

/**
 * `ui.weekrows`: the Week tab's day rows from the week plan, the classified calendar, the app's links and prompts
 * (UX 10.3, D82). Pure: the screen renders the rows as they are.
 */
object UiWeekRows {
    /**
     * The seven rows of the ISO week starting [monday]. [plan] is null for a past week (only [done] sessions then);
     * [canPlan] is false while the app may not write to a calendar (Plan it is hidden).
     */
    fun rows(
        plan: WeekPlan?,
        monday: LocalDate,
        anchors: List<Anchor>,
        instances: Map<String, CalendarInstance>,
        links: List<CalendarLink>,
        prompts: List<CalendarPrompt>,
        done: List<Pair<LocalDate, String>>,
        today: LocalDate,
        now: Instant,
        zone: ZoneId,
        canPlan: Boolean,
        groupLabel: (String) -> String,
    ): List<WeekRow> = (0L..6L).map { i ->
        val date = monday.plusDays(i)
        val day = plan?.days?.firstOrNull { it.date == date }
        val items = day?.items.orEmpty()
        val dayAnchors = anchors.filter { it.localDate == date && it.kind != AnchorKind.IGNORED }.sortedWith(compareBy({ it.beginUtc }, { it.instanceKey }))
        val runs = dayAnchors.filter { it.kind == AnchorKind.RUN }
        val runText = runs.joinToString(", ") { "run ${it.runMinutes ?: 0} min" }.ifEmpty { null }

        val doneItems = items.filter { it.kind == FixedItem.KIND_DONE || it.kind == FixedItem.KIND_DONE_GENERAL }
        val challenge = items.firstOrNull { it.kind == FixedItem.KIND_CHALLENGE || it.kind == FixedItem.KIND_TEST }
        val addOn = items.firstOrNull { it.kind == WeeklyPlanner.KIND_ADDON }
        val planned = items.firstOrNull { it.kind == FixedItem.KIND_PLANNED_GYM }
        val recommended = items.firstOrNull { it.kind == WeeklyPlanner.KIND_RECOMMENDED }
        val notRecommended = planned != null && planned.direct.values.none { it > 0.0 }
        val reason = day?.restReason?.let { r -> UiTodayCard.text(r.code, r.params, now, zone, groupLabel) ?: if (r.code == "PAST_DAY") null else r.code.lowercase().replace('_', ' ') }

        val kind: String
        val symbol: String
        val title: String
        var detail: String? = null
        when {
            challenge != null -> {
                kind = if (challenge.kind == FixedItem.KIND_TEST) "test" else "challenge"
                symbol = if (challenge.kind == FixedItem.KIND_TEST) "T" else "*"
                title = challenge.label
                detail = addOn?.let { a -> "+ ${typeLabel(a.type)} add-on ~${minutes(a.estMinutes)} min" }
            }
            doneItems.isNotEmpty() || (plan == null && done.any { it.first == date }) -> {
                kind = "done"
                symbol = "v"
                val labels = doneItems.map { it.label } + if (plan == null) done.filter { it.first == date }.map { it.second } else emptyList()
                title = labels.distinct().joinToString(" + ") + " (done)"
            }
            planned != null && notRecommended -> {
                kind = "planned"
                symbol = "-"
                title = "Planned, not recommended"
                detail = "The plan has nothing left for it that day"
            }
            planned != null -> {
                kind = "planned"
                symbol = "o"
                title = planned.label + " ~${minutes(planned.estMinutes)} min"
            }
            recommended != null -> {
                kind = "recommended"
                symbol = "o"
                title = recommended.label + " ~${minutes(recommended.estMinutes)} min"
            }
            day?.status == "blocked" -> {
                kind = "blocked"
                symbol = "x"
                title = "Rest"
                detail = reason
            }
            plan == null || day?.status == "past" -> {
                kind = "past"
                symbol = "-"
                title = if (date.isBefore(today)) "No session" else "-"
            }
            else -> {
                kind = "rest"
                symbol = "-"
                title = "Rest"
                val free = day?.allowedFamilies?.let { fams ->
                    "free for " + fams.joinToString(" / ") { it.lowercase() } + if (day.lightPullOnly) "; light pull only" else ""
                }
                detail = listOfNotNull(reason, free).joinToString("; ").ifEmpty { null }
            }
        }
        if (runText != null) detail = listOfNotNull(detail, runText).joinToString(" · ")

        val linkByMarker = links.associateBy { it.marker.render() }
        val events = dayAnchors.map { a ->
            val link = a.marker?.let { linkByMarker[it.render()] }
            val note = when {
                a.marker != null && a.marker!!.parsedKey() is SlotKey.General && planned?.key == a.instanceKey && notRecommended -> "planned (not recommended)"
                link?.state == CalendarLink.USER_MOVED -> "Moved by you"
                link?.state == CalendarLink.USER_EDITED -> "Edited by you: the app no longer updates this event"
                else -> null
            }
            val inst = instances[a.instanceKey]
            WeekEvent(a.instanceKey, inst?.title?.ifBlank { null } ?: "(no title)", hhmm(Week.localTime(a.beginUtc, zone)), a.marker != null, note, a.kind, a.marker)
        }

        val keys = dayAnchors.map { it.instanceKey }.toSet()
        val adopt = prompts.firstOrNull { (it.prompt as? WritePrompt.Adopt)?.instanceKey in keys }
        val addTo = challenge?.slot?.let { slot ->
            prompts.firstOrNull { p -> (p.prompt as? WritePrompt.AddToCalendar)?.let { it.slot == slot && it.isoWeek == Week.isoWeek(date) } == true }
        }
        val action = when {
            adopt != null -> WeekAction.Adopt(adopt)
            addTo != null -> WeekAction.AddToCalendar(addTo)
            recommended != null && canPlan && !date.isBefore(today) && recommended.start.isAfter(now) -> WeekAction.PlanIt(recommended)
            else -> null
        }
        WeekRow(date, symbol, title, detail, events, action, planned ?: recommended, notRecommended, kind)
    }

    fun typeLabel(code: String?): String = runCatching { SessionType.of(code ?: "") }.getOrNull()?.let {
        when (it) {
            SessionType.PUSH_LEGS -> "legs + push"
            SessionType.PULL_LEGS -> "pull + legs"
            SessionType.FULL -> "full body"
            else -> it.code.lowercase()
        }
    } ?: code.orEmpty().lowercase()

    private fun minutes(m: Double): Long = Math.round(m)

    private fun hhmm(t: java.time.LocalTime): String = "%02d:%02d".format(java.util.Locale.ROOT, t.hour, t.minute)
}
