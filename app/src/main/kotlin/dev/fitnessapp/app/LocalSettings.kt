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

    private companion object {
        const val KEY_EXPORT_REMINDER = "export_reminder_enabled"
        const val KEY_PLATFORM_HASH = "platform_config_hash"
        const val KEY_EXPORT_REMINDER_DATE = "export_reminder_date"
        const val KEY_SYNC_NOTICE_DATE = "sync_notice_date"
        const val KEY_HABIT_REMINDERS = "habit_reminders_enabled"
        const val KEY_HABIT_SKIPS = "habit_skips"
    }
}
