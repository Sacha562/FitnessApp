package dev.fitnessapp.app.nav

/** Navigation3 keys (ANDROID.md 7.4); the back stack is app-owned state. */
sealed interface AppKey {
    data object Main : AppKey
    data class LiveSession(val sessionId: String) : AppKey
    data class Summary(val sessionId: String) : AppKey
    data object DataSync : AppKey
    data object Equipment : AppKey
    data object Reliability : AppKey
    data object History : AppKey
    data class SessionDetail(val sessionId: String) : AppKey
    data class ExerciseDetail(val exerciseId: String, val from: String) : AppKey

    /** Data and sync opened from the N-EXPORT reminder: starts the monthly export right away (UX 18). */
    data object DataSyncExport : AppKey

    companion object {
        /** Saved-state form of a key, so the back stack survives recreation and process death. */
        fun encode(k: AppKey): String = when (k) {
            Main -> "main"
            is LiveSession -> "live:" + k.sessionId
            is Summary -> "summary:" + k.sessionId
            DataSync -> "data_sync"
            Equipment -> "equipment"
            Reliability -> "reliability"
            History -> "history"
            is SessionDetail -> "session_detail:" + k.sessionId
            is ExerciseDetail -> "exercise:" + k.from + ":" + k.exerciseId
            DataSyncExport -> "data_sync_export"
        }

        fun decode(s: String): AppKey? = when {
            s == "main" -> Main
            s.startsWith("live:") -> LiveSession(s.removePrefix("live:"))
            s.startsWith("summary:") -> Summary(s.removePrefix("summary:"))
            s == "data_sync" -> DataSync
            s == "equipment" -> Equipment
            s == "reliability" -> Reliability
            s == "history" -> History
            s.startsWith("session_detail:") -> SessionDetail(s.removePrefix("session_detail:"))
            s.startsWith("exercise:") -> s.removePrefix("exercise:").split(':', limit = 2).takeIf { it.size == 2 }?.let { ExerciseDetail(it[1], it[0]) }
            s == "data_sync_export" -> DataSyncExport
            else -> null
        }
    }
}

/** Top-level tabs; Week and Challenges arrive with their milestones. */
enum class Tab { TODAY, EXERCISES, YOU }

/** Internal route names of notification taps (ANDROID.md 7.4, C54): never deep links. */
object Routes {
    sealed interface Target {
        data object CurrentSession : Target
        data object Today : Target
        data object DataSync : Target
        data object DataSyncExport : Target
    }

    fun parse(route: String?): Target = when (route) {
        "fitapp://session/current", "fitapp://session/current/rest" -> Target.CurrentSession
        "fitapp://you/data" -> Target.DataSync
        "fitapp://you/data/export" -> Target.DataSyncExport
        else -> Target.Today
    }
}
