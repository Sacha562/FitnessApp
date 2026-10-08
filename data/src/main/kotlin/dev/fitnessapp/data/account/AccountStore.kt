package dev.fitnessapp.data.account

import android.content.Context

/**
 * The last signed-in account (DATA_MODEL 11.2). Writes stay possible offline after the access token
 * expired: the auth client reports no user while a refresh fails, but local rows still belong to this id.
 * Cleared only on an explicit sign-out (which waits for an empty outbox, 11.10).
 */
class AccountStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("account", Context.MODE_PRIVATE)

    fun userId(): String? = prefs.getString(KEY_USER_ID, null)

    fun email(): String? = prefs.getString(KEY_EMAIL, null)

    @Synchronized
    fun remember(userId: String, email: String?) {
        if (userId == userId() && email == email()) return
        prefs.edit().putString(KEY_USER_ID, userId).putString(KEY_EMAIL, email).commit()
    }

    @Synchronized
    fun forget() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_EMAIL = "email"
    }
}
