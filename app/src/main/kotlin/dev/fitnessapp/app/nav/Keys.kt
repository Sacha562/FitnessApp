package dev.fitnessapp.app.nav

/** Navigation3 keys (ANDROID.md 7.4); the back stack is app-owned state. */
sealed interface AppKey {
    data object Main : AppKey
    data class LiveSession(val sessionId: String) : AppKey
    data class Summary(val sessionId: String) : AppKey
    data object DataSync : AppKey
    data object Equipment : AppKey
    data object Reliability : AppKey
}

/** Top-level tabs of M1a; Week, Exercises and Challenges arrive with their milestones. */
enum class Tab { TODAY, YOU }

/** Internal route names of notification taps (ANDROID.md 7.4, C54): never deep links. */
object Routes {
    sealed interface Target {
        data object CurrentSession : Target
        data object Today : Target
        data object DataSync : Target
    }

    fun parse(route: String?): Target = when (route) {
        "fitapp://session/current", "fitapp://session/current/rest" -> Target.CurrentSession
        "fitapp://you/data" -> Target.DataSync
        else -> Target.Today
    }
}
