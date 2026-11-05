package dev.fitnessapp.app.nav

/** Navigation3 keys (ANDROID.md 7.4); the back stack is app-owned state. */
sealed interface AppKey {
    data object Main : AppKey
    data class LiveSession(val sessionId: String) : AppKey
    data class Summary(val sessionId: String) : AppKey
    data object DataSync : AppKey
    data object Equipment : AppKey
    data object Reliability : AppKey

    /** You → Calendars (UX 15.6, D39). */
    data object Calendars : AppKey

    /** You → Preferences (UX 15.4, M3.5). */
    data object Preferences : AppKey

    /** You → Prompts (UX 15.8, M4.2). */
    data object Prompts : AppKey

    /** You → Progress (UX 15.2, M4.7). */
    data object Progress : AppKey
    data object History : AppKey
    data class SessionDetail(val sessionId: String) : AppKey
    data class ExerciseDetail(val exerciseId: String, val from: String) : AppKey

    /** Create a custom exercise (UX 13.5, M3.6); [name] prefills step 4 from an empty search. */
    data class CustomExercise(val name: String) : AppKey

    /** Data and sync opened from the N-EXPORT reminder: starts the monthly export right away (UX 18). */
    data object DataSyncExport : AppKey

    /** The active challenge dashboard (UX 14.2). */
    data class ChallengeDashboard(val enrollmentId: String) : AppKey

    /** The challenge setup wizard for one definition (UX 14.8). */
    data class ChallengeSetup(val definitionId: String) : AppKey

    /** The test-day wizard (UX 14.9): its overview before Start ([sessionId] null), then the test session. */
    data class TestDay(val enrollmentId: String, val sessionId: String?) : AppKey

    /** Sign in again after the session expired (D94): the sign-in form over the app, the remembered account only. */
    data object SignIn : AppKey

    companion object {
        /** Saved-state form of a key, so the back stack survives recreation and process death. */
        fun encode(k: AppKey): String = when (k) {
            Main -> "main"
            is LiveSession -> "live:" + k.sessionId
            is Summary -> "summary:" + k.sessionId
            DataSync -> "data_sync"
            Equipment -> "equipment"
            Reliability -> "reliability"
            Calendars -> "calendars"
            Preferences -> "preferences"
            Prompts -> "prompts"
            Progress -> "progress"
            History -> "history"
            is SessionDetail -> "session_detail:" + k.sessionId
            is ExerciseDetail -> "exercise:" + k.from + ":" + k.exerciseId
            is CustomExercise -> "custom_exercise:" + k.name
            DataSyncExport -> "data_sync_export"
            is ChallengeDashboard -> "challenge:" + k.enrollmentId
            is ChallengeSetup -> "challenge_setup:" + k.definitionId
            is TestDay -> "test_day:" + k.enrollmentId + ":" + (k.sessionId ?: "")
            SignIn -> "sign_in"
        }

        fun decode(s: String): AppKey? = when {
            s == "main" -> Main
            s.startsWith("live:") -> LiveSession(s.removePrefix("live:"))
            s.startsWith("summary:") -> Summary(s.removePrefix("summary:"))
            s == "data_sync" -> DataSync
            s == "equipment" -> Equipment
            s == "reliability" -> Reliability
            s == "calendars" -> Calendars
            s == "preferences" -> Preferences
            s == "prompts" -> Prompts
            s == "progress" -> Progress
            s == "history" -> History
            s.startsWith("session_detail:") -> SessionDetail(s.removePrefix("session_detail:"))
            s.startsWith("custom_exercise:") -> CustomExercise(s.removePrefix("custom_exercise:"))
            s.startsWith("exercise:") -> s.removePrefix("exercise:").split(':', limit = 2).takeIf { it.size == 2 }?.let { ExerciseDetail(it[1], it[0]) }
            s == "data_sync_export" -> DataSyncExport
            s == "sign_in" -> SignIn
            s.startsWith("challenge:") -> ChallengeDashboard(s.removePrefix("challenge:"))
            s.startsWith("challenge_setup:") -> ChallengeSetup(s.removePrefix("challenge_setup:"))
            s.startsWith("test_day:") -> s.removePrefix("test_day:").split(':', limit = 2).takeIf { it.size == 2 }?.let { TestDay(it[0], it[1].ifEmpty { null }) }
            else -> null
        }
    }
}

/** Top-level tabs (UX 3.1); Week since M4.2. */
enum class Tab { TODAY, WEEK, EXERCISES, CHALLENGES, YOU }

/** Internal route names of notification taps (ANDROID.md 7.4, C54): never deep links. */
object Routes {
    sealed interface Target {
        data object CurrentSession : Target
        data object Today : Target
        data object DataSync : Target
        data object DataSyncExport : Target

        /** N-HABIT Start (ANDROID.md 17): the habit's home mini-session. */
        data class Habit(val habitId: String) : Target
    }

    private val HABIT_ID = Regex("^[a-z][a-z0-9_]{0,63}$")

    fun parse(route: String?): Target = when (route) {
        "fitapp://session/current", "fitapp://session/current/rest" -> Target.CurrentSession
        "fitapp://you/data" -> Target.DataSync
        "fitapp://you/data/export" -> Target.DataSyncExport
        else -> route?.removePrefix("fitapp://habit/")?.takeIf { it != route && HABIT_ID.matches(it) }?.let(Target::Habit) ?: Target.Today
    }
}
