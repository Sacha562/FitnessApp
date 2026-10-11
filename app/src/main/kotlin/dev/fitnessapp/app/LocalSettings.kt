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

    private companion object {
        const val KEY_EXPORT_REMINDER = "export_reminder_enabled"
        const val KEY_PLATFORM_HASH = "platform_config_hash"
        const val KEY_EXPORT_REMINDER_DATE = "export_reminder_date"
        const val KEY_SYNC_NOTICE_DATE = "sync_notice_date"
    }
}
