package dev.fitnessapp.app.ui.live

import android.content.Context

/**
 * D112: the exercise the user is on in the live session, kept on the phone so the screen reopens on it after a
 * minimise, a notification or Today-card resume, or process death. One session at a time; another session's id
 * reads as nothing.
 */
class LiveFocusStore(context: Context) {
    private val prefs = context.getSharedPreferences("live_focus", Context.MODE_PRIVATE)

    fun load(sessionId: String): String? =
        if (prefs.getString(K_SESSION, null) == sessionId) prefs.getString(K_EXERCISE, null) else null

    fun save(sessionId: String, sessionExerciseId: String?) {
        prefs.edit().putString(K_SESSION, sessionId).putString(K_EXERCISE, sessionExerciseId).apply()
    }

    private companion object {
        const val K_SESSION = "session_id"
        const val K_EXERCISE = "session_exercise_id"
    }
}
