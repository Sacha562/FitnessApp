package dev.fitnessapp.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import dev.fitnessapp.app.ui.auth.LoadingScreen
import dev.fitnessapp.app.ui.auth.SignInScreen
import dev.fitnessapp.app.ui.live.LiveSessionRoute
import dev.fitnessapp.app.ui.onboarding.OnboardingScreen
import dev.fitnessapp.app.ui.summary.SummaryRoute
import dev.fitnessapp.app.ui.today.TodayRoute
import dev.fitnessapp.app.ui.you.DataSyncRoute
import dev.fitnessapp.app.ui.you.EquipmentRoute
import dev.fitnessapp.app.ui.you.ReliabilityRoute
import dev.fitnessapp.app.ui.you.YouRoute
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun AppRoot(c: AppContainer, route: MutableStateFlow<Routes.Target?>) {
    val vm: AppViewModel = viewModel(factory = viewModelFactory { initializer { AppViewModel(c) } })
    val state by vm.state.collectAsState()
    when (state.gate) {
        Gate.STARTING, Gate.CHECKING -> LoadingScreen(if (state.gate == Gate.CHECKING) "Checking your account…" else "")
        Gate.SIGNED_OUT -> SignInScreen(state.busy, state.error, vm::signIn)
        Gate.NOT_ALLOWED -> GateMessageScreen(
            "This account isn't enabled for the fitness app yet.",
            "Your sign-in worked, but the app is limited to invited accounts. Ask the owner to add this email to the allowlist.",
            state.email, "Check again", vm::check, "Sign out", vm::signOut,
        )
        Gate.SCHEMA_NOT_EXPOSED -> GateMessageScreen(
            "Server not configured", "The fitness schema is not exposed in the Data API yet.", state.email, "Check again", vm::check, null, {},
        )
        Gate.OFFLINE -> GateMessageScreen(
            "No connection", state.error ?: "The first sign-in needs a connection.", state.email, "Try again", vm::check, null, {},
        )
        Gate.ONBOARDING -> OnboardingScreen(c, vm::onboardingDone)
        Gate.READY -> MainNav(c, vm, route)
    }
}

@Composable
private fun MainNav(c: AppContainer, appVm: AppViewModel, route: MutableStateFlow<Routes.Target?>) {
    val backStack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf<AppKey>(AppKey.Main) }
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    val pending by route.collectAsState()

    fun push(k: AppKey) {
        if (backStack.lastOrNull() != k) backStack.add(k)
    }

    // An open session always comes back to the front after a restart (UX 11.15 "Restored").
    LaunchedEffect(pending) {
        val active = c.sessionRepository.observeActiveOnce()
        when (pending) {
            Routes.Target.DataSync -> push(AppKey.DataSync)
            Routes.Target.CurrentSession, null -> active?.let { a ->
                val key = AppKey.LiveSession(a.id)
                if (pending != null || key !in backStack) push(key)
            }
            Routes.Target.Today -> {}
        }
        route.value = null
    }

    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<AppKey.Main> {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(tab == Tab.TODAY, { tab = Tab.TODAY }, { Icon(Icons.Filled.FitnessCenter, null) }, label = { Text("Today") })
                            NavigationBarItem(tab == Tab.YOU, { tab = Tab.YOU }, { Icon(Icons.Filled.Person, null) }, label = { Text("You") })
                        }
                    },
                ) { pad ->
                    val m = Modifier.padding(pad)
                    when (tab) {
                        Tab.TODAY -> TodayRoute(c, m, onOpenSession = { push(AppKey.LiveSession(it)) }, onOpenSync = { push(AppKey.DataSync) })
                        Tab.YOU -> YouRoute(
                            c, appVm, m, onDataSync = { push(AppKey.DataSync) }, onEquipment = { push(AppKey.Equipment) },
                            onReliability = { push(AppKey.Reliability) },
                        )
                    }
                }
            }
            entry<AppKey.LiveSession> { k ->
                LiveSessionRoute(
                    c, k.sessionId,
                    onMinimise = { backStack.removeAt(backStack.lastIndex) },
                    onFinished = { id ->
                        backStack.removeAt(backStack.lastIndex)
                        push(AppKey.Summary(id))
                    },
                    onAbandoned = { backStack.removeAt(backStack.lastIndex) },
                )
            }
            entry<AppKey.Summary> { k -> SummaryRoute(c, k.sessionId, onDone = { backStack.removeAt(backStack.lastIndex) }) }
            entry<AppKey.DataSync> { DataSyncRoute(c, onBack = { backStack.removeAt(backStack.lastIndex) }) }
            entry<AppKey.Equipment> { EquipmentRoute(c, onBack = { backStack.removeAt(backStack.lastIndex) }) }
            entry<AppKey.Reliability> { ReliabilityRoute(c, onBack = { backStack.removeAt(backStack.lastIndex) }) }
        },
    )
}

private val BackStackSaver = listSaver<SnapshotStateList<AppKey>, String>(
    save = { stack -> stack.map(AppKey::encode) },
    restore = { saved -> mutableStateListOf<AppKey>().apply { addAll(saved.mapNotNull(AppKey::decode).ifEmpty { listOf(AppKey.Main) }) } },
)
