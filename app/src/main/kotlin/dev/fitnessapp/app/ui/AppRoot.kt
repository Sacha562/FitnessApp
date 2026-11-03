package dev.fitnessapp.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.nav.AppKey
import dev.fitnessapp.app.nav.Routes
import dev.fitnessapp.app.nav.Tab
import dev.fitnessapp.app.ui.auth.GateMessageScreen
import dev.fitnessapp.app.ui.challenges.ChallengeDashboardRoute
import dev.fitnessapp.app.ui.challenges.ChallengeWeek
import dev.fitnessapp.app.ui.challenges.HabitDays
import dev.fitnessapp.app.ui.challenges.ChallengeSetupRoute
import dev.fitnessapp.app.ui.challenges.ChallengesRoute
import dev.fitnessapp.app.ui.auth.LoadingScreen
import dev.fitnessapp.app.ui.auth.SignInScreen
import dev.fitnessapp.app.ui.live.LiveSessionRoute
import dev.fitnessapp.app.ui.testday.TestDayRoute
import dev.fitnessapp.app.ui.onboarding.OnboardingScreen
import dev.fitnessapp.app.ui.summary.SummaryRoute
import dev.fitnessapp.app.ui.today.TodayRoute
import dev.fitnessapp.app.ui.you.DataSyncRoute
import dev.fitnessapp.app.ui.you.EquipmentRoute
import dev.fitnessapp.app.ui.you.ReliabilityRoute
import dev.fitnessapp.app.ui.you.YouRoute
import dev.fitnessapp.app.ui.exercises.ExerciseDetailRoute
import dev.fitnessapp.app.ui.exercises.ExercisesRoute
import dev.fitnessapp.app.ui.history.HistoryRoute
import dev.fitnessapp.app.ui.history.SessionDetailRoute
import dev.fitnessapp.app.notify.Channels
import dev.fitnessapp.app.session.SessionService
import dev.fitnessapp.app.ui.common.FitNavBar
import dev.fitnessapp.app.ui.common.NavItem
import dev.fitnessapp.app.ui.theme.Fit
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun AppRoot(c: AppContainer, route: MutableStateFlow<Routes.Target?>) {
    val vm: AppViewModel = viewModel(factory = viewModelFactory { initializer { AppViewModel(c) } })
    val state by vm.state.collectAsState()
    when (state.gate) {
        Gate.STARTING, Gate.CHECKING -> LoadingScreen(if (state.gate == Gate.CHECKING) "Checking your account…" else "")
        Gate.SIGNED_OUT -> SignInScreen(state.busy, state.error, vm::signIn)
        Gate.NOT_ALLOWED -> GateMessageScreen(
            "This account can't use the app yet",
            "The app is invite-only. Ask the owner to add this email.",
            state.email, "Check again", vm::check, "Sign out", vm::signOut,
        )
        Gate.SCHEMA_NOT_EXPOSED -> GateMessageScreen(
            "Server needs an update", "The app can't use the server yet. Try again later.", state.email, "Check again", vm::check, null, {},
        )
        Gate.OFFLINE -> GateMessageScreen(
            "No connection", state.error ?: "The first sign-in needs internet.", state.email, "Try again", vm::check, null, {},
        )
        Gate.ONBOARDING -> OnboardingScreen(c, vm::onboardingDone)
        Gate.READY -> MainNav(c, vm, route)
    }
}

@Composable
private fun MainNav(c: AppContainer, appVm: AppViewModel, route: MutableStateFlow<Routes.Target?>) {
    val backStack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf<AppKey>(AppKey.Main) }
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    // A day tapped in Today's week strip: the Week tab opens at that week with the day marked (UX 9.7, D82).
    var weekFocus by remember { mutableStateOf<java.time.LocalDate?>(null) }
    val pending by route.collectAsState()
    val scope = rememberCoroutineScope()

    fun push(k: AppKey) {
        if (backStack.lastOrNull() != k) backStack.add(k)
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    // An open session always comes back to the front after a restart (UX 11.15 "Restored").
    LaunchedEffect(pending) {
        val active = c.sessionRepository.observeActiveOnce()
        when (pending) {
            Routes.Target.DataSync -> push(AppKey.DataSync)
            Routes.Target.DataSyncExport -> {
                push(AppKey.DataSyncExport)
                c.fgsEvent("notification_action", mapOf("notification_id" to "N-EXPORT", "channel" to Channels.APP_STATUS, "action" to "export"))
            }
            Routes.Target.CurrentSession, null -> active?.let { a ->
                val key = sessionKey(a)
                if (key !in backStack) {
                    push(key)
                    if (pending == null) restored(c, a.id) else c.sessionEvent(a.id, "session_resumed", mapOf("via" to "notification"))
                } else if (pending != null) {
                    push(key)
                }
            }
            // N-HABIT Start (ANDROID.md 17): the habit's home mini-session; an open session wins.
            is Routes.Target.Habit -> {
                val p = pending as Routes.Target.Habit // a delegated property does not smart-cast
                c.reminders.action("start")
                val id = active?.id ?: runCatching {
                    val now = java.time.Instant.now()
                    val day = HabitDays.of(c, dev.fitnessapp.algo.core.clock.Week.localDate(now, ChallengeWeek.ZONE), now) ?: error("no running challenge")
                    c.challengeSessions.startHabit(day, p.habitId, "notification").also { dev.fitnessapp.app.session.SessionService.start(c.appContext) }
                }.getOrNull()
                id?.let { push(AppKey.LiveSession(it)) }
            }
            Routes.Target.Today -> {}
        }
        route.value = null
    }

    // `screen_viewed` on navigation, at most once per screen and `SCREEN_VIEW_MIN_GAP_MS` (UX 20.9).
    val top = backStack.lastOrNull()
    val screen = screenName(top, tab)
    val lastViews = remember { mutableMapOf<String, Long>() }
    LaunchedEffect(screen) {
        val now = System.currentTimeMillis()
        if (now - (lastViews[screen] ?: 0L) >= SCREEN_VIEW_MIN_GAP_MS) {
            lastViews[screen] = now
            c.screenViewed(screen, emptyMap())
        }
    }

    fun openSession(id: String, via: String) {
        scope.launch {
            // A test session (UX 14.9) resumes in its wizard, any other in the live session.
            push(c.sessionRepository.session(id)?.let(::sessionKey) ?: AppKey.LiveSession(id))
            if (via != "start") c.sessionEvent(id, "session_resumed", mapOf("via" to via))
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { pop() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<AppKey.Main> {
                Scaffold(
                    containerColor = Fit.Ground,
                    bottomBar = {
                        FitNavBar(NAV_ITEMS, Tab.entries.indexOf(tab), onSelect = { i ->
                            val t = Tab.entries[i]
                            if (tab == t) scope.launch { c.screenViewed(screenName(AppKey.Main, t), mapOf("tab_reselected" to true)) }
                            tab = t
                        })
                    },
                ) { pad ->
                    val m = Modifier.padding(pad)
                    when (tab) {
                        Tab.TODAY -> TodayRoute(
                            c, m, onOpenSession = { id, resumed -> openSession(id, if (resumed) "today_card" else "start") }, onOpenSync = { push(AppKey.DataSync) },
                            onOpenTest = { push(AppKey.TestDay(it, null)) },
                            onOpenPast = { push(AppKey.SessionDetail(it)) }, onHistory = { push(AppKey.History) },
                            onOpenReliability = { push(AppKey.Reliability) }, onOpenEquipment = { push(AppKey.Equipment) },
                            onSetGoals = { push(AppKey.Preferences) },
                            onOpenWeek = { d -> weekFocus = d; tab = Tab.WEEK },
                        )
                        Tab.WEEK -> dev.fitnessapp.app.ui.week.WeekRoute(
                            c, m, onSetGoals = { push(AppKey.Preferences) }, onOpenCalendars = { push(AppKey.Calendars) },
                            onOpenToday = { tab = Tab.TODAY }, focus = weekFocus, onFocusUsed = { weekFocus = null },
                        )
                        Tab.EXERCISES -> ExercisesRoute(c, m, onOpen = { push(AppKey.ExerciseDetail(it, "list")) }, onCreate = { push(AppKey.CustomExercise(it)) })
                        Tab.CHALLENGES -> ChallengesRoute(c, m, onOpen = { push(AppKey.ChallengeDashboard(it)) }, onSetup = { push(AppKey.ChallengeSetup(it)) })
                        Tab.YOU -> YouRoute(
                            c, appVm, m, onDataSync = { push(AppKey.DataSync) }, onEquipment = { push(AppKey.Equipment) },
                            onReliability = { push(AppKey.Reliability) }, onHistory = { push(AppKey.History) }, onCalendars = { push(AppKey.Calendars) },
                            onPreferences = { push(AppKey.Preferences) }, onPrompts = { push(AppKey.Prompts) },
                        )
                    }
                }
            }
            entry<AppKey.LiveSession> { k ->
                LiveSessionRoute(
                    c, k.sessionId,
                    onMinimise = { via ->
                        pop()
                        scope.launch { c.sessionEvent(k.sessionId, "session_minimised", mapOf("via" to via)) }
                    },
                    onFinished = { id ->
                        pop()
                        push(AppKey.Summary(id))
                    },
                    onAbandoned = { pop() },
                )
            }
            entry<AppKey.Summary> { k -> SummaryRoute(c, k.sessionId, onDone = { pop() }) }
            entry<AppKey.DataSync> { DataSyncRoute(c, onBack = { pop() }) }
            entry<AppKey.DataSyncExport> { DataSyncRoute(c, onBack = { pop() }, autoExport = true) }
            entry<AppKey.Equipment> { EquipmentRoute(c, onBack = { pop() }) }
            entry<AppKey.Reliability> { ReliabilityRoute(c, onBack = { pop() }) }
            entry<AppKey.Calendars> { dev.fitnessapp.app.ui.calendar.CalendarsRoute(c, onBack = { pop() }) }
            entry<AppKey.Preferences> { dev.fitnessapp.app.ui.you.PreferencesRoute(c, onBack = { pop() }) }
            entry<AppKey.Prompts> { dev.fitnessapp.app.ui.you.PromptsRoute(c, onBack = { pop() }) }
            entry<AppKey.History> { HistoryRoute(c, onBack = { pop() }, onOpen = { push(AppKey.SessionDetail(it)) }) }
            entry<AppKey.SessionDetail> { k ->
                SessionDetailRoute(
                    c, k.sessionId, onBack = { pop() },
                    onOpenExercise = { push(AppKey.ExerciseDetail(it, "history")) },
                    onReopened = { id ->
                        pop()
                        SessionService.start(c.appContext)
                        push(AppKey.LiveSession(id))
                    },
                )
            }
            entry<AppKey.CustomExercise> { k ->
                dev.fitnessapp.app.ui.exercises.CustomExerciseRoute(c, k.name, onBack = { pop() }, onSaved = { id -> pop(); push(AppKey.ExerciseDetail(id, "list")) })
            }
            entry<AppKey.ExerciseDetail> { k -> ExerciseDetailRoute(c, k.exerciseId, k.from, onBack = { pop() }, onOpen = { push(AppKey.ExerciseDetail(it, "alternatives")) }) }
            entry<AppKey.ChallengeDashboard> { k -> ChallengeDashboardRoute(c, k.enrollmentId, onBack = { pop() }, onTestDay = { push(AppKey.TestDay(k.enrollmentId, null)) }) }
            entry<AppKey.TestDay> { k ->
                TestDayRoute(
                    c, k.enrollmentId, k.sessionId,
                    onStarted = { id ->
                        pop()
                        push(AppKey.TestDay(k.enrollmentId, id))
                    },
                    onMinimise = { pop() }, onDone = { pop() },
                )
            }
            entry<AppKey.ChallengeSetup> { k ->
                ChallengeSetupRoute(c, k.definitionId, onBack = { pop() }, onStarted = { id ->
                    pop()
                    push(AppKey.ChallengeDashboard(id))
                })
            }
        },
    )
}

/** UX 20.2 `session_restored`: the app came back with a session in progress. */
private suspend fun restored(c: AppContainer, sessionId: String) {
    val rest = c.db.restTimers().latest(sessionId)?.state?.takeIf { it == "running" || it == "overtime" } ?: "none"
    val last = c.db.events().lastActivity(sessionId, emptyList())
    c.sessionEvent(sessionId, "session_restored", mapOf("rest_state" to rest, "ms_since_last_event" to (last?.let { System.currentTimeMillis() - it } ?: 0L)))
}

/** The UX screen id of the visible destination (UX 3.1), the `screen` envelope column of `screen_viewed`. */
private fun screenName(k: AppKey?, tab: Tab): String = when (k) {
    AppKey.Main, null -> when (tab) {
        Tab.TODAY -> "today"
        Tab.WEEK -> "week"
        Tab.EXERCISES -> "exercises"
        Tab.CHALLENGES -> "challenges"
        Tab.YOU -> "you"
    }
    is AppKey.LiveSession -> "session.live"
    is AppKey.Summary -> "session.summary"
    AppKey.DataSync, AppKey.DataSyncExport -> "you.data"
    AppKey.Equipment -> "you.equipment"
    AppKey.Reliability -> "you.rest"
    AppKey.Calendars -> "you.calendars"
    AppKey.Preferences -> "you.preferences"
    AppKey.Prompts -> "you.prompts"
    AppKey.History -> "you.history"
    is AppKey.SessionDetail -> "you.session_detail"
    is AppKey.ExerciseDetail -> "exercises.detail"
    is AppKey.CustomExercise -> "exercises.custom"
    is AppKey.ChallengeDashboard -> "challenges.dashboard"
    is AppKey.ChallengeSetup -> "challenge.setup"
    is AppKey.TestDay -> "test.wizard"
}

/** The screen of an open session: the test-day wizard for a `kind = test` challenge session (UX 14.9), else the live session. */
private fun sessionKey(s: dev.fitnessapp.data.db.entity.WorkoutSessionEntity): AppKey {
    val enrollment = s.enrollment_id
    return if (s.kind == "test" && enrollment != null) AppKey.TestDay(enrollment, s.id) else AppKey.LiveSession(s.id)
}

private const val SCREEN_VIEW_MIN_GAP_MS = 1_000L

/** The floating navigation tile's destinations, in [Tab] order (UX 3.1, 4.3). */
private val NAV_ITEMS = listOf(
    NavItem("Today", Icons.Rounded.FitnessCenter),
    NavItem("Week", Icons.Rounded.CalendarMonth),
    NavItem("Exercises", Icons.AutoMirrored.Rounded.FormatListBulleted),
    NavItem("Challenges", Icons.Rounded.EmojiEvents),
    NavItem("You", Icons.Rounded.Person),
)

private val BackStackSaver = listSaver<SnapshotStateList<AppKey>, String>(
    save = { stack -> stack.map(AppKey::encode) },
    restore = { saved -> mutableStateListOf<AppKey>().apply { addAll(saved.mapNotNull(AppKey::decode).ifEmpty { listOf(AppKey.Main) }) } },
)
