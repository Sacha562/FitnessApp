package dev.fitnessapp.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.data.remote.AccessCheck
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** M0 skeleton: sign in with the existing account, run the allowlist probe and the profile round trip. */
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    init {
        viewModelScope.launch {
            runCatching { c.supabase.auth.awaitInitialization() }
            val user = c.supabase.auth.currentUserOrNull()
            _state.update { it.copy(phase = if (user != null) Phase.SIGNED_IN else Phase.SIGNED_OUT) }
            if (user != null) checkConnection()
        }
    }

    fun signIn(email: String, password: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                c.supabase.auth.signInWith(Email) {
                    this.email = email.trim()
                    this.password = password
                }
                _state.update { it.copy(phase = Phase.SIGNED_IN) }
                runChecks()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Sign-in failed (${e::class.simpleName})") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun checkConnection() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                runChecks()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { c.supabase.auth.signOut() }
            _state.value = HomeState(phase = Phase.SIGNED_OUT)
        }
    }

    private suspend fun runChecks() {
        val access = c.accessCheck.run()
        _state.update { it.copy(access = access, roundTrip = null) }
        if (access != AccessCheck.Result.Allowed) return
        val result = try {
            val rt = c.profileSync.upsertAndReadBack(c.clock.now())
            if (rt.ok) RoundTripState.Ok(rt.readBack.serverUpdatedAt.orEmpty()) else RoundTripState.Mismatch
        } catch (e: Exception) {
            RoundTripState.Failed(e::class.simpleName ?: "unknown")
        }
        _state.update { it.copy(roundTrip = result) }
    }
}

enum class Phase { STARTING, SIGNED_OUT, SIGNED_IN }

sealed interface RoundTripState {
    data class Ok(val serverUpdatedAt: String) : RoundTripState
    data object Mismatch : RoundTripState
    data class Failed(val errorClass: String) : RoundTripState
}

data class HomeState(
    val phase: Phase = Phase.STARTING,
    val busy: Boolean = false,
    val error: String? = null,
    val access: AccessCheck.Result? = null,
    val roundTrip: RoundTripState? = null,
)
