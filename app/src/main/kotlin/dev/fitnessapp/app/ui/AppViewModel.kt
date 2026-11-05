package dev.fitnessapp.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.auth.mask
import dev.fitnessapp.data.remote.AccessCheck
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which top-level flow the app shows (UX 8.1): sign-in, rejection, onboarding or the main app. */
enum class Gate { STARTING, SIGNED_OUT, CHECKING, NOT_ALLOWED, SCHEMA_NOT_EXPOSED, OFFLINE, ONBOARDING, READY }

data class AppState(
    val gate: Gate = Gate.STARTING,
    val busy: Boolean = false,
    val error: String? = null,
    val email: String? = null,
)

/**
 * The gate (UX 8.2-8.3): after sign-in the allowlist probe runs, then one sync pulls an existing profile (a
 * reinstall keeps its onboarding), and only then is a local profile created. Nothing is written locally
 * before the account is allowlisted. Once onboarded, the app opens offline without any check.
 */
class AppViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state

    init {
        viewModelScope.launch {
            runCatching { c.supabase.auth.awaitInitialization() }
            // Offline with an expired token the auth client reports no user while its refresh fails; the
            // remembered account keeps the app usable (offline-first) until an explicit sign-out.
            val user = c.supabase.auth.currentUserOrNull()
            if (user != null) c.account.remember(user.id, user.email)
            if (c.account.userId() == null) {
                _state.update { it.copy(gate = Gate.SIGNED_OUT) }
                return@launch
            }
            _state.update { it.copy(email = user?.email ?: c.account.email()) }
            val profile = c.profiles.get()
            if (profile?.onboarding_completed_at != null) _state.update { it.copy(gate = Gate.READY) } else check()
        }
    }

    fun signIn(email: String, password: String) = authenticate(email, password) {
        c.supabase.auth.currentUserOrNull()?.let { u -> c.account.remember(u.id, u.email) }
        _state.update { it.copy(email = email.trim()) }
        check()
    }

    /**
     * Sign in again after the session expired (D94): only the remembered account is accepted, so one account's
     * rows are never uploaded under another; then the upload resumes. No allowlist check, no onboarding.
     */
    fun reauth(email: String, password: String, onDone: () -> Unit) = authenticate(email, password) {
        val u = c.supabase.auth.currentUserOrNull()
        val remembered = c.account.userId()
        if (u == null || (remembered != null && u.id != remembered)) {
            runCatching { c.supabase.auth.signOut() }
            val who = c.account.email()?.let(::mask) ?: "another account"
            _state.update { it.copy(error = "This phone holds the data of $who. Sign in with that account.") }
            return@authenticate
        }
        c.account.remember(u.id, u.email)
        _state.update { it.copy(email = u.email ?: email.trim()) }
        c.sync.syncNow()
        onDone()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun authenticate(email: String, password: String, then: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                c.supabase.auth.signInWith(Email) {
                    this.email = email.trim()
                    this.password = password
                }
                then()
            } catch (e: Exception) {
                val (cls, msg) = when {
                    e is java.io.IOException || e::class.simpleName == "HttpRequestException" -> "offline" to "No internet. The first sign-in needs a connection."
                    e.message?.contains("Invalid login", ignoreCase = true) == true -> "credentials" to "Email or password is wrong."
                    e.message?.contains("rate", ignoreCase = true) == true -> "rate_limited" to "Too many attempts. Wait a minute and try again."
                    else -> "server" to "Sign-in failed. Check your email and password."
                }
                _state.update { it.copy(error = msg) }
                // Written only when a user id is known (a previous sign-in on this device); never the email (UX 20.9).
                runCatching { c.fgsEvent("signin_failed", mapOf("error_class" to cls)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Re-runs the access probe (UX 8.3 "Check again") and the first pull. */
    fun check() {
        viewModelScope.launch {
            _state.update { it.copy(gate = Gate.CHECKING, error = null) }
            when (val r = c.accessCheck.run()) {
                AccessCheck.Result.Allowed -> {
                    runCatching { c.syncEngine.run("signin") }
                    val p = c.profiles.ensure()
                    _state.update { it.copy(gate = if (p.onboarding_completed_at != null) Gate.READY else Gate.ONBOARDING) }
                }
                AccessCheck.Result.NotAllowed -> {
                    _state.update { it.copy(gate = Gate.NOT_ALLOWED) }
                    // The row cannot be pushed (RLS rejects a non-allowlisted user); it stays local as a record.
                    runCatching { c.fgsEvent("allowlist_rejected", emptyMap()) }
                }
                AccessCheck.Result.SchemaNotExposed -> _state.update { it.copy(gate = Gate.SCHEMA_NOT_EXPOSED) }
                is AccessCheck.Result.Failed -> {
                    val p = c.profiles.get()
                    _state.update {
                        it.copy(
                            gate = when {
                                p?.onboarding_completed_at != null -> Gate.READY
                                p != null -> Gate.ONBOARDING
                                else -> Gate.OFFLINE
                            },
                            error = "Server unreachable (${r.errorClass}). Try again when you have a connection.",
                        )
                    }
                }
            }
        }
    }

    fun onboardingDone() = _state.update { it.copy(gate = Gate.READY) }

    /** Sign-out is blocked while unsynced or dead-lettered rows exist (DATA_MODEL 11.10). */
    suspend fun canSignOut(): Boolean = c.db.outbox().depth() == 0 && c.sync.deadLetters().isEmpty()

    fun signOut() {
        viewModelScope.launch {
            if (!canSignOut()) return@launch
            runCatching { c.supabase.auth.signOut() }
            c.account.forget()
            _state.value = AppState(gate = Gate.SIGNED_OUT)
        }
    }
}
