package dev.fitnessapp.app.calendar

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract.Events
import dev.fitnessapp.algo.challenge.calendar.WriteOp
import dev.fitnessapp.algo.core.calendar.Marker
import dev.fitnessapp.algo.core.clock.Week
import java.time.Instant
import java.time.ZoneId

/** Where writes may go and why (ANDROID 16.7 guard 1, 16.10). */
data class WriteTarget(
    val calendarId: Long,
    val accessLevel: Int,
    /** True when the target is the device-only test calendar (sandbox redirect, D39). */
    val testCalendar: Boolean,
    val identity: String,
)

/** What one batch did. */
data class ExecResult(
    val applied: List<WriteOp>,
    /** Slot key -> provider id of each insert. */
    val insertedIds: Map<String, Long>,
    /** Updates and deletes that touched no row because the marker was gone (guard 3). */
    val markerMissing: List<WriteOp>,
    /** Ops a guard refused before the batch, with the guard's code. */
    val refused: List<Pair<WriteOp, String>>,
    /** The batch failed as a whole (`provider_error`, `security_exception`). */
    val failure: String?,
)

/**
 * `CalendarWriteExecutor` (ANDROID 16.7): applies a write plan from `challenge.calendar` in one `applyBatch` and refuses,
 * regardless of the plan, every op that breaks a guard: the target calendar (only the selected target, and only the test
 * calendar while real writes are off), the week guard for automatic writes, the atomic marker guard on update and
 * delete, and the future-only guard on delete. Adopt and user-confirmed edits have their own guarded calls.
 */
class CalendarWriteExecutor(private val gateway: CalendarProviderGateway, private val realWritesAllowed: () -> Boolean) {

    /** Applies the automatic ops of one plan. [eventCalendar] maps provider event ids to their calendar. */
    fun execute(ops: List<WriteOp>, target: WriteTarget, eventCalendar: Map<Long, Long>, now: Instant, zone: ZoneId, location: String?): ExecResult {
        val refused = ArrayList<Pair<WriteOp, String>>()
        val send = ArrayList<WriteOp>()
        val thisWeek = Week.isoWeekStart(Week.localDate(now, zone))
        fun inWeeks(t: Instant): Boolean {
            val ws = Week.isoWeekStart(Week.localDate(t, zone))
            return ws == thisWeek || ws == thisWeek.plusWeeks(1)
        }
        for (op in ops) {
            val code = when {
                !target.testCalendar && !realWritesAllowed() -> "sandbox"
                target.accessLevel < ACCESS_CONTRIBUTOR -> "target_guard"
                op is WriteOp.Insert && !inWeeks(op.event.start) -> "week_guard"
                op is WriteOp.Update && (!inWeeks(op.currentStart) || (op.start != null && !inWeeks(op.start!!))) -> "week_guard"
                op is WriteOp.Update && eventCalendar[op.eventId] != target.calendarId -> "target_guard"
                op is WriteOp.Delete && eventCalendar[op.eventId] != target.calendarId -> "target_guard"
                op is WriteOp.Delete && !op.start.isAfter(now) -> "delete_guard"
                else -> null
            }
            if (code != null) refused += op to code else send += op
        }
        if (send.isEmpty()) return ExecResult(emptyList(), emptyMap(), emptyList(), refused, null)
        val batch = ArrayList<ContentProviderOperation>()
        for (op in send) {
            batch += when (op) {
                is WriteOp.Insert -> ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(
                    ContentValues().apply {
                        put(Events.CALENDAR_ID, target.calendarId)
                        put(Events.DTSTART, op.event.start.toEpochMilli())
                        put(Events.DTEND, op.event.end.toEpochMilli())
                        put(Events.EVENT_TIMEZONE, zone.id)
                        put(Events.EVENT_END_TIMEZONE, zone.id)
                        put(Events.TITLE, op.event.title)
                        put(Events.DESCRIPTION, op.description)
                        (op.event.location ?: location)?.let { put(Events.EVENT_LOCATION, it) }
                        put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
                        put(Events.STATUS, Events.STATUS_CONFIRMED)
                        put(Events.HAS_ALARM, 0)
                    },
                ).build()
                is WriteOp.Update -> guardedUpdate(op.eventId, op.guard)
                    .withValues(
                        ContentValues().apply {
                            op.start?.let { put(Events.DTSTART, it.toEpochMilli()) }
                            op.end?.let { put(Events.DTEND, it.toEpochMilli()) }
                            op.title?.let { put(Events.TITLE, it) }
                            op.description?.let { put(Events.DESCRIPTION, it) }
                        },
                    ).build()
                is WriteOp.Delete -> guardedDelete(op.eventId, op.guard, now)
            }
        }
        val results = try {
            gateway.applyBatch(batch)
        } catch (e: SecurityException) {
            return ExecResult(emptyList(), emptyMap(), emptyList(), refused, "security_exception")
        } catch (e: Exception) {
            return ExecResult(emptyList(), emptyMap(), emptyList(), refused, "provider_error")
        }
        val inserted = sortedMapOf<String, Long>()
        val missing = ArrayList<WriteOp>()
        val applied = ArrayList<WriteOp>()
        send.forEachIndexed { i, op ->
            val r = results.getOrNull(i)
            when (op) {
                is WriteOp.Insert -> r?.uri?.let { inserted[op.marker.slotKey] = ContentUris.parseId(it); applied += op }
                else -> if ((r?.count ?: 0) == 0) missing += op else applied += op
            }
        }
        return ExecResult(applied, inserted, missing, refused, null)
    }

    /**
     * Adopt (guard 5, user tap only): appends the marker after a blank line to the existing description, with the
     * selection "no fitapp marker yet". Returns the rows touched (0 when someone else marked it meanwhile).
     */
    fun adopt(eventId: Long, eventCalendarId: Long, target: WriteTarget, description: String?, marker: Marker): Int {
        if (eventCalendarId != target.calendarId) throw GuardException("target_guard")
        if (!target.testCalendar && !realWritesAllowed()) throw GuardException("sandbox")
        val values = ContentValues().apply { put(Events.DESCRIPTION, Marker.withMarker(description, marker)) }
        val op = ContentProviderOperation.newUpdate(Events.CONTENT_URI)
            .withSelection("${Events._ID} = ? AND (${Events.DESCRIPTION} IS NULL OR ${Events.DESCRIPTION} NOT LIKE ?)", arrayOf(eventId.toString(), Marker.NO_MARKER_LIKE))
            .withValues(values).build()
        return gateway.applyBatch(arrayListOf(op)).firstOrNull()?.count ?: 0
    }

    /**
     * A user-confirmed change to an app event (Rename, Remove): user actions may target any future date (guard 2), but
     * the target, sandbox, marker and future-only guards still apply.
     */
    fun userConfirmed(op: WriteOp, target: WriteTarget, eventCalendar: Map<Long, Long>, now: Instant): ExecResult {
        val id = when (op) {
            is WriteOp.Update -> op.eventId
            is WriteOp.Delete -> op.eventId
            is WriteOp.Insert -> null
        }
        if (id == null || eventCalendar[id] != target.calendarId) return ExecResult(emptyList(), emptyMap(), emptyList(), listOf(op to "target_guard"), null)
        return executeAnyWeek(op, target, now)
    }

    private fun executeAnyWeek(op: WriteOp, target: WriteTarget, now: Instant): ExecResult {
        val values = ContentValues()
        val built = when (op) {
            is WriteOp.Update -> {
                op.title?.let { values.put(Events.TITLE, it) }
                op.description?.let { values.put(Events.DESCRIPTION, it) }
                guardedUpdate(op.eventId, op.guard).withValues(values).build()
            }
            is WriteOp.Delete -> guardedDelete(op.eventId, op.guard, now)
            is WriteOp.Insert -> return ExecResult(emptyList(), emptyMap(), emptyList(), listOf(op to "week_guard"), null)
        }
        if (!target.testCalendar && !realWritesAllowed()) return ExecResult(emptyList(), emptyMap(), emptyList(), listOf(op to "sandbox"), null)
        val r = try {
            gateway.applyBatch(arrayListOf(built))
        } catch (e: SecurityException) {
            return ExecResult(emptyList(), emptyMap(), emptyList(), emptyList(), "security_exception")
        } catch (e: Exception) {
            return ExecResult(emptyList(), emptyMap(), emptyList(), emptyList(), "provider_error")
        }
        return if ((r.firstOrNull()?.count ?: 0) == 0) ExecResult(emptyList(), emptyMap(), listOf(op), emptyList(), null)
        else ExecResult(listOf(op), emptyMap(), emptyList(), emptyList(), null)
    }

    /**
     * The atomic marker guard (guard 3). The Calendar provider refuses any selection on an `events/<id>` URI
     * ("Selection not permitted", seen on Android 16), so the id is part of the selection on `events` instead.
     */
    private fun guardedUpdate(eventId: Long, guard: Marker): ContentProviderOperation.Builder =
        ContentProviderOperation.newUpdate(Events.CONTENT_URI)
            .withSelection("${Events._ID} = ? AND $MARKER_SELECTION", arrayOf(eventId.toString(), guard.likePattern()))

    /** Guards 3 and 4: the exact marker, and a start still in the future. */
    private fun guardedDelete(eventId: Long, guard: Marker, now: Instant): ContentProviderOperation =
        ContentProviderOperation.newDelete(Events.CONTENT_URI)
            .withSelection(
                "${Events._ID} = ? AND $MARKER_SELECTION AND ${Events.DTSTART} > ?",
                arrayOf(eventId.toString(), guard.likePattern(), now.toEpochMilli().toString()),
            ).build()

    class GuardException(val code: String) : Exception(code)

    companion object {
        /** `CAL_ACCESS_CONTRIBUTOR` (500): the lowest level that may write events. */
        private const val ACCESS_CONTRIBUTOR = 500
        private const val MARKER_SELECTION = "${Events.DESCRIPTION} LIKE ? ESCAPE '\\'"
    }
}
