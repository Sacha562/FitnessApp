package dev.fitnessapp.app.calendar

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances

/** One row of `CalendarContract.Calendars` (ANDROID 16.3). */
data class ProviderCalendar(
    val id: Long,
    val accountName: String,
    val accountType: String,
    val ownerAccount: String,
    val isPrimary: Boolean?,
    val displayName: String,
    val visible: Boolean,
    val syncEvents: Boolean,
    val accessLevel: Int,
    val syncId: String?,
) {
    /** The stored identity `(ACCOUNT_NAME, ACCOUNT_TYPE, OWNER_ACCOUNT)`; ids change when an account is re-added. */
    val identity: String get() = "$accountName|$accountType|$ownerAccount"

    val isTestCalendar: Boolean get() = accountType == CalendarContract.ACCOUNT_TYPE_LOCAL && accountName == CalendarProviderGateway.TEST_ACCOUNT
}

/** One `CalendarContract.Instances` row with the event's sync id (ANDROID 16.4). */
data class ProviderInstance(
    val eventId: Long,
    val calendarId: Long,
    val begin: Long,
    val end: Long,
    val title: String?,
    val description: String?,
    val location: String?,
    val allDay: Boolean,
    val status: Int?,
    val selfAttendeeStatus: Int?,
    val availability: Int?,
    val eventSyncId: String?,
)

/**
 * The thin Android side of the calendar (ANDROID 16.1): queries calendars and instances and applies one batch of
 * operations. Everything else (filtering, classification, write plans) happens in `:algo`.
 */
class CalendarProviderGateway(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun calendars(): List<ProviderCalendar> {
        val projection = arrayOf(
            Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.OWNER_ACCOUNT, Calendars.IS_PRIMARY,
            Calendars.CALENDAR_DISPLAY_NAME, Calendars.VISIBLE, Calendars.SYNC_EVENTS, Calendars.CALENDAR_ACCESS_LEVEL, Calendars._SYNC_ID,
        )
        val out = ArrayList<ProviderCalendar>()
        resolver.query(Calendars.CONTENT_URI, projection, null, null, "${Calendars.ACCOUNT_NAME} ASC, ${Calendars._ID} ASC")?.use { c ->
            while (c.moveToNext()) {
                out += ProviderCalendar(
                    id = c.getLong(0), accountName = c.getString(1).orEmpty(), accountType = c.getString(2).orEmpty(),
                    ownerAccount = c.getString(3).orEmpty(), isPrimary = if (c.isNull(4)) null else c.getInt(4) == 1,
                    displayName = c.getString(5).orEmpty(), visible = c.getInt(6) == 1, syncEvents = c.getInt(7) == 1,
                    accessLevel = c.getInt(8), syncId = c.getString(9),
                )
            }
        }
        return out
    }

    /** Instances of [calendarIds] in `[fromMs, toMs)`, ordered by begin and event id, with each event's `_SYNC_ID`. */
    fun instances(calendarIds: List<Long>, fromMs: Long, toMs: Long): List<ProviderInstance> {
        if (calendarIds.isEmpty()) return emptyList()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.BEGIN, Instances.END, Instances.TITLE, Instances.DESCRIPTION,
            Instances.EVENT_LOCATION, Instances.ALL_DAY, Instances.STATUS, Instances.SELF_ATTENDEE_STATUS, Instances.AVAILABILITY,
        )
        val selection = "${Instances.CALENDAR_ID} IN (${calendarIds.joinToString { "?" }})"
        val rows = ArrayList<ProviderInstance>()
        resolver.query(uri, projection, selection, calendarIds.map { it.toString() }.toTypedArray(), "${Instances.BEGIN} ASC, ${Instances.EVENT_ID} ASC")?.use { c ->
            while (c.moveToNext()) {
                rows += ProviderInstance(
                    eventId = c.getLong(0), calendarId = c.getLong(1), begin = c.getLong(2), end = c.getLong(3), title = c.getString(4),
                    description = c.getString(5), location = c.getString(6), allDay = c.getInt(7) == 1,
                    status = if (c.isNull(8)) null else c.getInt(8), selfAttendeeStatus = if (c.isNull(9)) null else c.getInt(9),
                    availability = if (c.isNull(10)) null else c.getInt(10), eventSyncId = null,
                )
            }
        }
        val syncIds = eventSyncIds(rows.map { it.eventId }.distinct())
        return rows.map { it.copy(eventSyncId = syncIds[it.eventId]) }
    }

    private fun eventSyncIds(ids: List<Long>): Map<Long, String> {
        val out = HashMap<Long, String>()
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            resolver.query(
                Events.CONTENT_URI, arrayOf(Events._ID, Events._SYNC_ID), "${Events._ID} IN (${chunk.joinToString { "?" }})",
                chunk.map { it.toString() }.toTypedArray(), null,
            )?.use { c ->
                while (c.moveToNext()) c.getString(1)?.let { out[c.getLong(0)] = it }
            }
        }
        return out
    }

    fun applyBatch(ops: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> =
        resolver.applyBatch(CalendarContract.AUTHORITY, ops)

    /**
     * Creates the device-only "FitnessApp TEST" calendar (ANDROID 16.10) in sync-adapter mode for a local account,
     * which the provider allows for `ACCOUNT_TYPE_LOCAL`. Returns its id.
     */
    fun createTestCalendar(timeZone: String): Long {
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, TEST_ACCOUNT)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, TEST_ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, TEST_NAME)
            put(Calendars.CALENDAR_DISPLAY_NAME, TEST_NAME)
            put(Calendars.CALENDAR_COLOR, TEST_COLOR)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, TEST_ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, timeZone)
        }
        return ContentUris.parseId(requireNotNull(resolver.insert(uri, values)) { "test calendar insert returned null" })
    }

    companion object {
        const val TEST_ACCOUNT = "fitnessapp.test"
        const val TEST_NAME = "FitnessApp TEST"
        private const val TEST_COLOR = 0xFF00897B.toInt()
        private const val QUERY_CHUNK = 200

        fun status(code: Int?): String = when (code) {
            Events.STATUS_CANCELED -> "cancelled"
            Events.STATUS_TENTATIVE -> "tentative"
            else -> "confirmed"
        }
    }
}
