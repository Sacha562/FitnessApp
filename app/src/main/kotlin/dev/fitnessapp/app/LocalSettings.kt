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

    /** Series keys whose override applies to the whole series (D39 scope "the whole series"). */
    var calendarSeriesOverrides: Set<String>
        get() = prefs.getStringSet(KEY_CAL_SERIES, emptySet()).orEmpty().toSortedSet()
        set(v) = prefs.edit().putStringSet(KEY_CAL_SERIES, v).apply()

    /** The degraded-mode reason last logged (`calendar_unavailable` is logged when it changes). */
    var calendarUnavailableReason: String?
        get() = prefs.getString(KEY_CAL_UNAVAILABLE, null)
        set(v) = prefs.edit().putString(KEY_CAL_UNAVAILABLE, v).apply()

    /** The add-on picked on the Today card for [slot] on [date] (D70 (4)): a family code or `none`; one pick is kept. */
    fun addOnPick(date: String, slot: String): String? = prefs.getString(KEY_ADD_ON_PICK, null)?.takeIf { it.startsWith("$date|$slot|") }?.substringAfterLast('|')

    fun setAddOnPick(date: String, slot: String, family: String) = prefs.edit().putString(KEY_ADD_ON_PICK, "$date|$slot|$family").apply()

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

    private companion object {
        const val KEY_ONCE = "once."
        const val KEY_CAL_PRIMARY = "calendar_primary_identity"
        const val KEY_CAL_EXTRA = "calendar_extra_identities"
        const val KEY_CAL_REAL_WRITES = "calendar_real_writes"
        const val KEY_CAL_SERIES = "calendar_series_overrides"
        const val KEY_CAL_UNAVAILABLE = "calendar_unavailable_reason"
        const val KEY_EXPORT_REMINDER = "export_reminder_enabled"
        const val KEY_PLATFORM_HASH = "platform_config_hash"
        const val KEY_EXPORT_REMINDER_DATE = "export_reminder_date"
        const val KEY_SYNC_NOTICE_DATE = "sync_notice_date"
        const val KEY_WEEK_PLAN_KEYS = "week_plan_keys"
        const val KEY_ADD_ON_PICK = "add_on_pick"
        const val KEY_HABIT_REMINDERS = "habit_reminders_enabled"
        const val KEY_HABIT_SKIPS = "habit_skips"
    }
}
