package dev.fitnessapp.app.debug

import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.util.Log
import dev.fitnessapp.algo.challenge.calendar.WritePrompt
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.calendar.CalendarProviderGateway
import dev.fitnessapp.app.ui.challenges.ChallengeWeek
import java.time.LocalDateTime

/**
 * Calendar checks over adb (M2b.5, ANDROID T17 first half). Every edit here touches only the device-only test
 * calendar "FitnessApp TEST"; events of other calendars are refused.
 *   --es action calendar_create_test                      creates the test calendar
 *   --es action calendar_refresh                          one refresh, then the dump
 *   --es action calendar_dump                             snapshot to logcat (tag CalendarDebug)
 *   --es action calendar_seed --es spec "<title>|2026-10-24T11:00|90;..."   unmarked events in the test calendar
 *   --es action calendar_edit --el event_id <id> --es field title|description|shift_min|delete --es value <v>
 *   --es action calendar_adopt --es instance_key <key>    the Adopt prompt of that event
 *   --es action calendar_delete_test                      deletes the test calendar and its events (cutover reset, D40)
 */
object CalendarDebug {
    const val TAG = "CalendarDebug"

    suspend fun run(c: AppContainer, action: String, spec: String?, eventId: Long, field: String?, value: String?, instanceKey: String?) {
        val result = runCatching {
            when (action) {
                "calendar_create_test" -> c.calendar.createTestCalendar()
                "calendar_refresh" -> c.calendar.refresh("debug")
                "calendar_seed" -> seed(c, requireNotNull(spec) { "spec missing" })
                "calendar_edit" -> edit(c, eventId, requireNotNull(field) { "field missing" }, value.orEmpty())
                "calendar_adopt" -> {
                    val p = c.calendar.snapshot.value.prompts.firstOrNull { (it.prompt as? WritePrompt.Adopt)?.instanceKey == instanceKey }
                    Log.i(TAG, "adopt: " + (p?.let { c.calendar.adopt(it) ?: "ok" } ?: "no adopt prompt for $instanceKey"))
                }
                "calendar_delete_test" -> deleteTestCalendar(c)
                "calendar_dump" -> Unit
            }
        }
        result.exceptionOrNull()?.let { Log.w(TAG, "$action failed", it) }
        dump(c)
    }

    /** Only the app's local test account: a user's calendar can never match this selection. */
    private suspend fun deleteTestCalendar(c: AppContainer) {
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, CalendarProviderGateway.TEST_ACCOUNT)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val n = c.appContext.contentResolver.delete(
            uri, "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(CalendarProviderGateway.TEST_ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL),
        )
        Log.i(TAG, "deleted $n test calendar(s)")
        c.calendar.refresh("debug_delete_test")
    }

    private fun testCalendarId(c: AppContainer): Long =
        requireNotNull(c.calendar.snapshot.value.testCalendar?.id) { "no test calendar: run calendar_create_test" }

    private suspend fun seed(c: AppContainer, spec: String) {
        val cal = testCalendarId(c)
        val zone = ChallengeWeek.ZONE
        for (item in spec.split(';').filter { it.isNotBlank() }) {
            val (title, start, minutes) = item.split('|').also { require(it.size == 3) { "bad item $item" } }
            val begin = LocalDateTime.parse(start).atZone(zone).toInstant().toEpochMilli()
            val v = ContentValues().apply {
                put(Events.CALENDAR_ID, cal)
                put(Events.TITLE, title)
                put(Events.DTSTART, begin)
                put(Events.DTEND, begin + minutes.toLong() * 60_000)
                put(Events.EVENT_TIMEZONE, zone.id)
            }
            val uri = c.appContext.contentResolver.insert(Events.CONTENT_URI, v)
            Log.i(TAG, "seeded ${uri?.let(ContentUris::parseId)} $title $start $minutes")
        }
        c.calendar.refresh("debug_seed")
    }

    private suspend fun edit(c: AppContainer, eventId: Long, field: String, value: String) {
        val cal = testCalendarId(c)
        val resolver = c.appContext.contentResolver
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        val inCal = resolver.query(uri, arrayOf(Events.CALENDAR_ID, Events.DTSTART, Events.DTEND), null, null, null)?.use { cur ->
            if (cur.moveToFirst() && cur.getLong(0) == cal) cur.getLong(1) to cur.getLong(2) else null
        } ?: error("event $eventId is not in the test calendar")
        val rows = when (field) {
            "delete" -> resolver.delete(uri, null, null)
            "title" -> resolver.update(uri, ContentValues().apply { put(Events.TITLE, value) }, null, null)
            "description" -> resolver.update(uri, ContentValues().apply { put(Events.DESCRIPTION, value) }, null, null)
            "shift_min" -> {
                val d = value.toLong() * 60_000
                resolver.update(uri, ContentValues().apply { put(Events.DTSTART, inCal.first + d); put(Events.DTEND, inCal.second + d) }, null, null)
            }
            else -> error("unknown field $field")
        }
        Log.i(TAG, "edit $eventId $field rows=$rows")
        c.calendar.refresh("debug_edit")
    }

    private fun dump(c: AppContainer) {
        val s = c.calendar.snapshot.value
        val zone = ChallengeWeek.ZONE
        Log.i(TAG, "status=${s.status} canWrite=${s.canWrite} primary=${s.primary?.displayName} test=${s.testCalendar?.id} target=${s.target} writeOff=${s.writeOff} read=${s.readIds}")
        for (a in s.anchors.sortedBy { it.beginUtc }) {
            val i = s.instances[a.instanceKey]
            Log.i(
                TAG,
                "anchor ${Week.localDate(a.beginUtc, zone)} ${Week.localTime(a.beginUtc, zone)} ev=${a.eventId} cal=${a.calendarId} '${i?.title}' " +
                    "${a.kind.code} slot=${a.slot} score=${a.matchScore ?: a.score} method=${a.method?.code} att=${a.attention} marker=${a.marker?.slotKey} key=${a.instanceKey.take(12)}",
            )
        }
        for (l in s.links) Log.i(TAG, "link ${l.slotKey} ev=${l.eventId} state=${l.state} start=${l.lastStart}")
        for (p in s.prompts) Log.i(TAG, "prompt ${p.prompt}")
        for (op in s.pending) Log.i(TAG, "pending $op")
    }
}
