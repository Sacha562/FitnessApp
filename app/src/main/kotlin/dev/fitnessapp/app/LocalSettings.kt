package dev.fitnessapp.app

import android.content.Context

/**
 * Device-local settings and bookkeeping that are not synced profile columns: the monthly export reminder switch
 * (UX 15.11, default on), the last logged `PlatformConfig` hash (ANDROID.md 7.7 step 8) and the days on which the
 * maintenance reminders were last posted (at most one per day).
 */
class LocalSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("local_settings", Context.MODE_PRIVATE)

    var exportReminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXPORT_REMINDER, true)
        set(v) = prefs.edit().putBoolean(KEY_EXPORT_REMINDER, v).apply()

    var lastPlatformConfigHash: String?
        get() = prefs.getString(KEY_PLATFORM_HASH, null)
        set(v) = prefs.edit().putString(KEY_PLATFORM_HASH, v).apply()

    var lastExportReminderDate: String?
        get() = prefs.getString(KEY_EXPORT_REMINDER_DATE, null)
        set(v) = prefs.edit().putString(KEY_EXPORT_REMINDER_DATE, v).apply()

    var lastSyncNoticeDate: String?
        get() = prefs.getString(KEY_SYNC_NOTICE_DATE, null)
        set(v) = prefs.edit().putString(KEY_SYNC_NOTICE_DATE, v).apply()

    /**
     * Home-habit reminders (ANDROID.md 17 `habitRemindersEnabled`): off until the D4 cutover is confirmed, so Todoist
     * and the app never remind twice.
     */
    var habitRemindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_HABIT_REMINDERS, false)
        set(v) = prefs.edit().putBoolean(KEY_HABIT_REMINDERS, v).apply()

    /**
     * Mesocycles (D100): off until the user turns them on from the "This mesocycle" card; while off no mesocycle is
     * started or used, so builds take no main lifts, consistency set or rotated-out exercises.
     */
    var mesocyclesEnabled: Boolean
        get() = prefs.getBoolean(KEY_MESOCYCLES, false)
        set(v) = prefs.edit().putBoolean(KEY_MESOCYCLES, v).apply()

    /** "Skip today" on N-HABIT: no further reminder of that habit on that date (kept for a week). */
    fun habitSkipped(habitId: String, date: java.time.LocalDate): Boolean = "$habitId|$date" in prefs.getStringSet(KEY_HABIT_SKIPS, emptySet()).orEmpty()

    fun markHabitSkipped(habitId: String, date: java.time.LocalDate) {
        val keep = prefs.getStringSet(KEY_HABIT_SKIPS, emptySet()).orEmpty()
            .filter { runCatching { !java.time.LocalDate.parse(it.substringAfter('|')).isBefore(date.minusDays(7)) }.getOrDefault(false) }
        prefs.edit().putStringSet(KEY_HABIT_SKIPS, (keep + "$habitId|$date").toSet()).apply()
    }

    /** The write target and default read calendar by identity `(ACCOUNT_NAME|ACCOUNT_TYPE|OWNER_ACCOUNT)` (ANDROID 16.3). */
    var calendarPrimaryIdentity: String?
        get() = prefs.getString(KEY_CAL_PRIMARY, null)
        set(v) = prefs.edit().putString(KEY_CAL_PRIMARY, v).apply()

    /** Further calendars the user added to the read set (never written). */
    var calendarExtraIdentities: Set<String>
        get() = prefs.getStringSet(KEY_CAL_EXTRA, emptySet()).orEmpty().toSortedSet()
        set(v) = prefs.edit().putStringSet(KEY_CAL_EXTRA, v).apply()

    /**
     * Writes to the user's own calendar (D39): off until the user allows them after the test-calendar checks (T17);
     * until then every write goes to the device-only test calendar. The sandbox build never writes there (F27).
     */
    var calendarRealWrites: Boolean
        get() = prefs.getBoolean(KEY_CAL_REAL_WRITES, false)
        set(v) = prefs.edit().putBoolean(KEY_CAL_REAL_WRITES, v).apply()

    /**
     * Suggestions the user dragged in the Week grid (D84 (2)) for one ISO week: plan item key -> the new start. Display
     * only: nothing is written to the calendar until the user plans the session.
     */
    fun movedSuggestions(isoWeek: String): Map<String, java.time.Instant> =
        prefs.getString(KEY_MOVED_SUGGESTIONS + isoWeek, null).orEmpty().split(';').mapNotNull { e ->
            val i = e.lastIndexOf('=')
            val ms = if (i > 0) e.substring(i + 1).toLongOrNull() else null
            if (ms == null) null else e.substring(0, i) to java.time.Instant.ofEpochMilli(ms)
        }.toMap()

    /** Moves suggestion [key] of [isoWeek] to [start]; null puts it back where the planner placed it. */
    fun setMovedSuggestion(isoWeek: String, key: String, start: java.time.Instant?) {
        val cur = movedSuggestions(isoWeek).toMutableMap()
        if (start == null) cur.remove(key) else cur[key] = start
        val text = cur.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value.toEpochMilli()}" }
        prefs.edit().putString(KEY_MOVED_SUGGESTIONS + isoWeek, text).apply()
    }

    /** Series keys whose override applies to the whole series (D39 scope "the whole series"). */
    var calendarSeriesOverrides: Set<String>
        get() = prefs.getStringSet(KEY_CAL_SERIES, emptySet()).orEmpty().toSortedSet()
        set(v) = prefs.edit().putStringSet(KEY_CAL_SERIES, v).apply()

    /** The degraded-mode reason last logged (`calendar_unavailable` is logged when it changes). */
    var calendarUnavailableReason: String?
        get() = prefs.getString(KEY_CAL_UNAVAILABLE, null)
        set(v) = prefs.edit().putString(KEY_CAL_UNAVAILABLE, v).apply()

    /** The day keys of the last week plan of [isoWeek] (ALGORITHMS_PLANNING 8.6 stability term), as JSON; only the newest week is kept. */
    fun weekPlanKeys(isoWeek: String): String? = prefs.getString(KEY_WEEK_PLAN_KEYS, null)?.takeIf { it.startsWith("$isoWeek|") }?.substringAfter('|')

    fun setWeekPlanKeys(isoWeek: String, json: String) = prefs.edit().putString(KEY_WEEK_PLAN_KEYS, "$isoWeek|$json").apply()

    /**
     * True the first time [key] is asked, false afterwards: once-per-day telemetry such as `weekplan_shown` (UX 19.5).
     * Only the newest key per prefix (the part before the first ':') is kept.
     */
    fun once(key: String): Boolean {
        val prefix = key.substringBefore(':')
        val last = prefs.getString(KEY_ONCE + prefix, null)
        if (last == key) return false
        prefs.edit().putString(KEY_ONCE + prefix, key).apply()
        return true
    }

    /** Wall-clock ms of the last update check (D98 (2)); 0 before the first. */
    var lastUpdateCheckMs: Long
        get() = prefs.getLong(KEY_UPDATE_CHECK, 0L)
        set(v) = prefs.edit().putLong(KEY_UPDATE_CHECK, v).apply()

    /** The highest release version code already notified (N-UPDATE) or shown in the app (D98 (2)). */
    var lastNotifiedUpdateCode: Int
        get() = prefs.getInt(KEY_UPDATE_NOTIFIED, 0)
        set(v) = prefs.edit().putInt(KEY_UPDATE_NOTIFIED, v).apply()

    /** The Plan grid's hour height in dp, as last pinched (D114). */
    var planHourDp: Float
        get() = prefs.getFloat(KEY_PLAN_HOUR, dev.fitnessapp.app.ui.week.DEFAULT_HOUR_DP)
        set(v) = prefs.edit().putFloat(KEY_PLAN_HOUR, v).apply()

    private companion object {
        const val KEY_PLAN_HOUR = "plan_hour_dp"
        const val KEY_UPDATE_CHECK = "update_check_ms"
        const val KEY_UPDATE_NOTIFIED = "update_notified_code"
        const val KEY_ONCE = "once."
        const val KEY_CAL_PRIMARY = "calendar_primary_identity"
        const val KEY_CAL_EXTRA = "calendar_extra_identities"
        const val KEY_CAL_REAL_WRITES = "calendar_real_writes"
        const val KEY_MOVED_SUGGESTIONS = "week_moved_suggestions:"
        const val KEY_CAL_SERIES = "calendar_series_overrides"
        const val KEY_CAL_UNAVAILABLE = "calendar_unavailable_reason"
        const val KEY_EXPORT_REMINDER = "export_reminder_enabled"
        const val KEY_PLATFORM_HASH = "platform_config_hash"
        const val KEY_EXPORT_REMINDER_DATE = "export_reminder_date"
        const val KEY_SYNC_NOTICE_DATE = "sync_notice_date"
        const val KEY_WEEK_PLAN_KEYS = "week_plan_keys"
        const val KEY_HABIT_REMINDERS = "habit_reminders_enabled"
        const val KEY_MESOCYCLES = "mesocycles_enabled"
        const val KEY_HABIT_SKIPS = "habit_skips"
    }
}
