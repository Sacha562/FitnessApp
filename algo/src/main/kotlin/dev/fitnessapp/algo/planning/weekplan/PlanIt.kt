package dev.fitnessapp.algo.planning.weekplan

import dev.fitnessapp.algo.challenge.calendar.CalendarLink
import dev.fitnessapp.algo.challenge.calendar.DesiredEvent
import dev.fitnessapp.algo.challenge.calendar.LinkChange
import dev.fitnessapp.algo.challenge.calendar.WriteOp
import dev.fitnessapp.algo.challenge.calendar.WritePlan
import dev.fitnessapp.algo.challenge.calendar.WritePlanner
import dev.fitnessapp.algo.core.calendar.CalendarInstance
import dev.fitnessapp.algo.core.calendar.Marker
import dev.fitnessapp.algo.core.calendar.SlotKey
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Sha256Hex
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

/**
 * "Plan it" (ALGORITHMS_PLANNING 8.9, UX 10.4, D82): the calendar event of an accepted recommended general session, and
 * the updates that keep an unedited one in step with the plan. The event is training time (C49): its length is the
 * estimate plus the changeover, rounded up to the 5-minute calendar grid, so its budget (length minus changeover) covers
 * the plan. The marker is C37's `[fitapp:v1 <recommendationId> gen/<isoYear>-W<ww>/<isoDow>]`. After the write the app
 * never moves the event, never deletes it on its own and never changes its session type; while the user has not edited
 * it, its title and exercise lines follow the plan for that day (D82 (4)). Pure: the app's calendar writer applies the
 * result through the guarded executor (ANDROID 16.7).
 */
object PlanIt {
    /** `calendar_link.kind` of a general event. */
    const val KIND = "general"

    /** The 5-minute calendar grid of 8.9: a display grid for event lengths, not a tunable. */
    private const val GRID_MIN = 5

    private const val TITLE_PREFIX = "Gym — "
    private const val SLOT = "GEN"

    /** One event as Plan it writes it. */
    data class Event(val marker: Marker, val title: String, val start: Instant, val end: Instant, val body: String, val description: String) {
        val isoWeek: String get() = (marker.parsedKey() as SlotKey.General).isoWeek

        /** The insert op for the executor (the location is the gym profile's, filled in by the executor). */
        fun insert(): WriteOp.Insert = WriteOp.Insert(DesiredEvent(isoWeek, SLOT, title, body, start, end, null), marker, description)
    }

    /** What an unedited planned event should show: the label of the plan's session that day and its exercise lines. */
    data class Desired(val title: String, val body: String)

    data class UpdateInput(
        val now: Instant,
        /** The read window: a link whose last start lies outside it is not judged (its event was not read). */
        val windowStart: Instant,
        val windowEnd: Instant,
        /** The general links (kind [KIND]); others are ignored. */
        val links: List<CalendarLink>,
        val instances: List<CalendarInstance>,
        /** Marker line -> what the plan wants the event to show; a marker without an entry is left as it is. */
        val desired: Map<String, Desired>,
        val humanLine: String,
    )

    fun slotKey(date: LocalDate): String = SlotKey.General(Week.isoWeek(date), date.dayOfWeek.value).render()

    /** The date a general slot key names (`gen/2026-W41/5` -> Fri 9 Oct 2026), or null for another key. */
    fun dateOf(slotKey: String): LocalDate? {
        val k = SlotKey.parse(slotKey) as? SlotKey.General ?: return null
        val year = k.isoWeek.substringBefore("-W").toInt()
        val week = k.isoWeek.substringAfter("-W").toInt()
        val monday = LocalDate.of(year, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong()).with(DayOfWeek.MONDAY)
        return monday.plusDays((k.isoDow - 1).toLong())
    }

    fun title(label: String): String = TITLE_PREFIX + label

    /**
     * The session type's name as a general event's title carries it (D84): "Full body", "Legs + push". The classifier
     * reads each of these back as the same type, so the title keeps the planned type; null for an unknown code.
     */
    fun typeName(code: String?): String? = when (code) {
        "LEGS" -> "Legs"
        "PUSH" -> "Push"
        "PULL" -> "Pull"
        "PUSH+LEGS" -> "Legs + push"
        "PULL+LEGS" -> "Pull + legs"
        "UPPER" -> "Upper"
        "FULL" -> "Full body"
        "CORE" -> "Core"
        else -> null
    }

    /** The event length in minutes: estimate plus changeover, rounded up to the 5-minute grid. */
    fun minutes(estMinutes: Double, changeoverMin: Double): Int {
        val raw = Math.ceil((estMinutes + changeoverMin) / GRID_MIN - EPS).toInt()
        return maxOf(1, raw) * GRID_MIN
    }

    /** One exercise line of the description: "Leg press · 3 sets". */
    fun line(name: String, sets: Int): String = "$name · $sets ${if (sets == 1) "set" else "sets"}"

    /**
     * The event of a general [item] (a recommended one, or one the user placed in the Week grid) under [markerUuid]: the
     * recommendation id of an accepted "Plan it", or a new device uuid (D84). Titled by its session type; [lines] are
     * its exercise lines.
     */
    fun event(markerUuid: String, item: PlanItem, zone: ZoneId, lines: List<String>, changeoverMin: Double, humanLine: String): Event {
        val date = Week.localDate(item.start, zone)
        val marker = Marker(markerUuid, slotKey(date))
        val end = item.start.plusSeconds(minutes(item.estMinutes, changeoverMin) * SECONDS_PER_MIN)
        val body = lines.joinToString("\n")
        return Event(marker, title(typeName(item.type) ?: item.label), item.start, end, body, WritePlanner.description(body, humanLine, marker))
    }

    /** The link Plan it stores after the insert ([eventId] is the provider id). */
    fun link(e: Event, eventId: Long?): CalendarLink =
        CalendarLink(e.marker.uuid, e.marker.slotKey, KIND, eventId, CalendarLink.ACTIVE, e.title, Sha256Hex.ofUtf8(e.description), e.start, e.end)

    /**
     * Reconciles the general links with the calendar (ANDROID 16.7 edit policy) and returns the title and description
     * updates of the unedited future events whose plan changed. Never an insert, a delete or a time change. Idempotent:
     * once the updates are applied, the same inputs give no operations.
     */
    fun updates(input: UpdateInput): WritePlan {
        val ours = input.instances.mapNotNull { inst ->
            val m = Marker.ofDescription(inst.description) ?: return@mapNotNull null
            if (m.parsedKey() !is SlotKey.General) null else inst to m
        }.sortedWith(compareBy({ it.first.beginUtc }, { it.first.instanceKey }))
        val byMarker = ours.groupBy { it.second.render() }.mapValues { it.value.first() }
        val instByEvent = input.instances.groupBy { it.eventId }

        val changes = ArrayList<LinkChange>()
        val links = java.util.TreeMap<String, CalendarLink>()
        for (l in input.links.filter { it.kind == KIND }.sortedWith(compareBy({ it.markerUuid }, { it.slotKey }))) {
            var s = l.state
            var eventId = l.eventId
            val live = byMarker[l.marker.render()]
            if (s == CalendarLink.ACTIVE || s == CalendarLink.USER_MOVED || s == CalendarLink.USER_EDITED) {
                if (live != null) {
                    val inst = live.first
                    eventId = inst.eventId
                    val edited = inst.title.orEmpty() != l.lastTitle || Sha256Hex.ofUtf8(inst.description.orEmpty()) != l.lastDescSha256
                    val moved = inst.beginUtc != l.lastStart || inst.endUtc != l.lastEnd
                    if (edited) s = CalendarLink.USER_EDITED else if (moved && s == CalendarLink.ACTIVE) s = CalendarLink.USER_MOVED
                } else if (!l.lastStart.isBefore(input.windowStart) && l.lastStart.isBefore(input.windowEnd)) {
                    val same = l.eventId?.let { instByEvent[it] }.orEmpty()
                    s = if (same.isNotEmpty() && same.none { Marker.ofDescription(it.description) == l.marker }) CalendarLink.RELEASED else CalendarLink.USER_DELETED
                }
            } else if ((s == CalendarLink.DELETED_BY_APP || s == CalendarLink.USER_DELETED) && live != null) {
                // The marked event is in the calendar again (restored, or a delete the calendar's sync undid): the
                // app's own again, judged against what the app last wrote (ANDROID 16.6).
                val inst = live.first
                eventId = inst.eventId
                val edited = inst.title.orEmpty() != l.lastTitle || Sha256Hex.ofUtf8(inst.description.orEmpty()) != l.lastDescSha256
                val moved = inst.beginUtc != l.lastStart || inst.endUtc != l.lastEnd
                s = if (edited) CalendarLink.USER_EDITED else if (moved) CalendarLink.USER_MOVED else CalendarLink.ACTIVE
            }
            val now = l.copy(state = s, eventId = eventId)
            if (now != l) changes += LinkChange(now)
            links[l.marker.render()] = now
        }
        // A marked general event without a link (after a reinstall): rebuilt as unchanged (ANDROID 16.6).
        for ((key, pair) in byMarker) {
            if (key in links) continue
            val (inst, m) = pair
            val rebuilt = CalendarLink(
                m.uuid, m.slotKey, KIND, inst.eventId, CalendarLink.ACTIVE, inst.title.orEmpty(), Sha256Hex.ofUtf8(inst.description.orEmpty()), inst.beginUtc, inst.endUtc,
            )
            links[key] = rebuilt
            changes += LinkChange(rebuilt)
        }

        val ops = ArrayList<WriteOp>()
        for ((key, l) in links) {
            if (l.state != CalendarLink.ACTIVE && l.state != CalendarLink.USER_MOVED) continue
            val d = input.desired[key] ?: continue
            val inst = byMarker[key]?.first ?: continue
            if (!inst.beginUtc.isAfter(input.now)) continue
            val description = WritePlanner.description(d.body, input.humanLine, l.marker)
            val title = d.title.takeIf { it != inst.title.orEmpty() }
            val desc = description.takeIf { it != inst.description.orEmpty() }
            if (title != null || desc != null) ops += WriteOp.Update(inst.eventId, inst.instanceKey, l.marker, l.marker, null, null, title, desc, inst.beginUtc)
        }
        return WritePlan(ops, emptyList(), changes)
    }

    /** The links after the executor applied [plan]'s updates: the reconciled rows with the written title and description. */
    fun linksAfter(links: List<CalendarLink>, plan: WritePlan): List<CalendarLink> {
        val out = java.util.TreeMap<String, CalendarLink>()
        for (l in links) out[l.marker.render()] = l
        for (c in plan.linkChanges) out[c.link.marker.render()] = c.link
        for (op in plan.ops) {
            if (op !is WriteOp.Update) continue
            val key = op.guard.render()
            val old = out[key] ?: continue
            out[key] = old.copy(
                eventId = op.eventId, lastTitle = op.title ?: old.lastTitle,
                lastDescSha256 = op.description?.let { Sha256Hex.ofUtf8(it) } ?: old.lastDescSha256,
            )
        }
        return out.values.toList()
    }

    private const val SECONDS_PER_MIN = 60L
    /** Tolerance for the grid's ceiling: an estimate on the grid up to float noise stays on it. */
    private const val EPS = 1e-9
}
