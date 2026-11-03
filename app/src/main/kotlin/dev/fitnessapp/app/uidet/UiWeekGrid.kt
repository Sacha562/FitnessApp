package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.core.calendar.Anchor
import dev.fitnessapp.algo.core.calendar.AnchorKind
import dev.fitnessapp.algo.core.calendar.CalendarInstance
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.planning.weekplan.FixedItem
import dev.fitnessapp.algo.planning.weekplan.PlanItem
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.algo.planning.weekplan.WeeklyPlanner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The three looks of D83 (2): a gym session, a suggested session, and everything else. */
enum class BlockLook { GYM, SUGGESTED, OTHER }

/** A session logged in a week without a plan (a past week), with its times when it has them. */
data class LoggedSession(val date: LocalDate, val label: String, val start: Instant?, val end: Instant?)

/** One block of the Week grid, in minutes after local midnight, with its lane among the blocks it overlaps. */
data class GridBlock(
    val key: String,
    val date: LocalDate,
    val startMin: Int,
    val endMin: Int,
    val look: BlockLook,
    val title: String,
    /** The label for a narrow column: "C", "Test", "L+P". */
    val short: String,
    /** "19:45". */
    val time: String,
    val done: Boolean,
    /** A second line: the add-on of a challenge session, or "not recommended". */
    val sub: String?,
    /** The recommended item a suggested block can plan in one tap (D83 (4)); null when it cannot be planned. */
    val planIt: PlanItem?,
    val lane: Int = 0,
    val lanes: Int = 1,
)

data class GridDay(val date: LocalDate, val today: Boolean, val blocked: Boolean, val blocks: List<GridBlock>)

/** The week as a time grid (D83): seven days and the hours to show, `[startHour, endHour)`. */
data class WeekGrid(val days: List<GridDay>, val startHour: Int, val endHour: Int)

/**
 * `ui.weekgrid`: the Week tab's calendar grid (D83) from the week plan, the classified calendar and, for a past week,
 * the logged sessions. Gym sessions come from the plan (with the calendar's times); a gym event the plan does not cover
 * is still shown as gym; every other event (busy time, runs) is an [BlockLook.OTHER] block. Pure.
 */
object UiWeekGrid {
    private const val MIN_START_HOUR = 8
    private const val MIN_END_HOUR = 21
    private const val MIN_BLOCK_MIN = 20
    private const val DAY_MIN = 24 * 60

    fun grid(
        plan: WeekPlan?,
        monday: LocalDate,
        anchors: List<Anchor>,
        instances: Map<String, CalendarInstance>,
        logged: List<LoggedSession>,
        today: LocalDate,
        now: Instant,
        zone: ZoneId,
        canPlan: Boolean,
    ): WeekGrid {
        val days = (0L..6L).map { i ->
            val date = monday.plusDays(i)
            val day = plan?.days?.firstOrNull { it.date == date }
            val items = day?.items.orEmpty()
            val blocks = ArrayList<GridBlock>()
            fun add(key: String, start: Instant, end: Instant, look: BlockLook, title: String, short: String, done: Boolean, sub: String? = null, planIt: PlanItem? = null) {
                val s = minuteOf(start, date, zone)
                val e = minOf(DAY_MIN, maxOf(minuteOf(end, date, zone), s + MIN_BLOCK_MIN))
                if (s >= DAY_MIN) return
                blocks += GridBlock(key, date, maxOf(0, s), e, look, title, short, hhmm(start, zone), done, sub, planIt)
            }
            val addOn = items.firstOrNull { it.kind == WeeklyPlanner.KIND_ADDON }
            for (it in items) when (it.kind) {
                FixedItem.KIND_CHALLENGE, FixedItem.KIND_TEST -> add(
                    it.key, it.start, it.end, BlockLook.GYM, it.label, slotShort(it.slot), false,
                    sub = addOn?.let { a -> "+ ${UiWeekRows.typeLabel(a.type)} ${Math.round(a.estMinutes)} min" },
                )
                FixedItem.KIND_DONE -> add(it.key, it.start, it.end, BlockLook.GYM, it.label, slotShort(it.slot), true)
                FixedItem.KIND_DONE_GENERAL -> add(it.key, it.start, it.end, BlockLook.GYM, it.label, typeShort(it.type) ?: "Gym", true)
                FixedItem.KIND_PLANNED_GYM -> {
                    val none = it.direct.values.none { v -> v > 0.0 }
                    add(it.key, it.start, it.end, BlockLook.GYM, if (none) "Planned gym" else it.label, typeShort(it.type) ?: "Gym", false, sub = if (none) "not recommended" else null)
                }
                WeeklyPlanner.KIND_RECOMMENDED -> add(
                    it.key, it.start, it.start.plusSeconds(Math.round(it.estMinutes * 60.0)), BlockLook.SUGGESTED, it.label, typeShort(it.type) ?: "Gym", false,
                    planIt = it.takeIf { canPlan && it.start.isAfter(now) },
                )
            }
            if (plan == null) {
                logged.filter { it.date == date }.forEachIndexed { k, l ->
                    val start = l.start ?: Week.atLocal(date, java.time.LocalTime.of(MIN_START_HOUR, 0), zone)
                    add("logged:$date:$k", start, l.end ?: start.plusSeconds(3600), BlockLook.GYM, l.label, shortOf(l.label), true)
                }
            }
            for (a in anchors.filter { it.localDate == date }.sortedWith(compareBy({ it.beginUtc }, { it.instanceKey }))) {
                val title = instances[a.instanceKey]?.title?.ifBlank { null }
                when (a.kind) {
                    AnchorKind.BUSY -> add(a.instanceKey, a.beginUtc, a.endUtc, BlockLook.OTHER, title ?: "Busy", title ?: "Busy", false)
                    AnchorKind.RUN -> add(a.instanceKey, a.beginUtc, a.endUtc, BlockLook.OTHER, title ?: "Run ${a.runMinutes ?: 0} min", "Run", false)
                    AnchorKind.CHALLENGE_SESSION, AnchorKind.PLANNED_GYM -> {
                        // A gym event the plan does not show (no plan, or a duplicate): still a gym block.
                        val s = minuteOf(a.beginUtc, date, zone)
                        val e = minuteOf(a.endUtc, date, zone)
                        if (blocks.none { it.look == BlockLook.GYM && it.startMin < e && s < it.endMin }) {
                            add(a.instanceKey, a.beginUtc, a.endUtc, BlockLook.GYM, title ?: "Gym", a.slot ?: "Gym", false)
                        }
                    }
                    AnchorKind.IGNORED -> Unit
                }
            }
            GridDay(date, date == today, day?.status == "blocked", lanes(blocks))
        }
        val all = days.flatMap { it.blocks }
        val start = minOf(MIN_START_HOUR, all.minOfOrNull { it.startMin / 60 } ?: MIN_START_HOUR)
        val end = maxOf(MIN_END_HOUR, all.maxOfOrNull { (it.endMin + 59) / 60 } ?: MIN_END_HOUR).coerceAtMost(24)
        return WeekGrid(days, start, end)
    }

    /**
     * Lanes for overlapping blocks (as a calendar app lays them out): blocks are taken by start (longer first), each goes
     * to the first free lane, and every block of an overlapping cluster gets the cluster's lane count.
     */
    fun lanes(blocks: List<GridBlock>): List<GridBlock> {
        val sorted = blocks.sortedWith(compareBy<GridBlock>({ it.startMin }, { -it.endMin }, { it.look.ordinal }, { it.key }))
        val out = ArrayList<GridBlock>()
        var cluster = ArrayList<GridBlock>()
        var clusterEnd = -1
        val laneEnds = ArrayList<Int>()
        fun flush() {
            val n = laneEnds.size.coerceAtLeast(1)
            out += cluster.map { it.copy(lanes = n) }
            cluster = ArrayList()
            laneEnds.clear()
        }
        for (b in sorted) {
            if (cluster.isNotEmpty() && b.startMin >= clusterEnd) flush()
            val lane = laneEnds.indexOfFirst { it <= b.startMin }.let { if (it >= 0) it else laneEnds.size }
            if (lane == laneEnds.size) laneEnds += b.endMin else laneEnds[lane] = b.endMin
            cluster += b.copy(lane = lane)
            clusterEnd = maxOf(if (cluster.size == 1) b.endMin else clusterEnd, b.endMin)
        }
        if (cluster.isNotEmpty()) flush()
        return out
    }

    private fun minuteOf(t: Instant, date: LocalDate, zone: ZoneId): Int {
        val local = t.atZone(zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(date, local.toLocalDate()).toInt()
        return days * DAY_MIN + local.hour * 60 + local.minute
    }

    private fun slotShort(slot: String?): String = if (slot == "TEST") "Test" else slot ?: "Gym"

    /** Narrow labels of the session types: "Legs", "Push", "L+P", "Full". */
    fun typeShort(code: String?): String? = runCatching { SessionType.of(code ?: return null) }.getOrNull()?.let {
        when (it) {
            SessionType.LEGS -> "Legs"
            SessionType.PUSH -> "Push"
            SessionType.PULL -> "Pull"
            SessionType.PUSH_LEGS -> "L+P"
            SessionType.PULL_LEGS -> "P+L"
            SessionType.UPPER -> "Upper"
            SessionType.FULL -> "Full"
            SessionType.CORE -> "Core"
        }
    }

    private fun shortOf(label: String): String = when {
        label.startsWith("Session ") -> label.removePrefix("Session ")
        label == "Test day" -> "Test"
        else -> label.split(" + ").joinToString("+") { it.take(1).uppercase() }.takeIf { label.contains(" + ") } ?: label
    }

    private fun hhmm(t: Instant, zone: ZoneId): String = Week.localTime(t, zone).let { "%02d:%02d".format(java.util.Locale.ROOT, it.hour, it.minute) }
}
