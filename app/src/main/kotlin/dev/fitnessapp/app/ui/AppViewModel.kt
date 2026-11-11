package dev.fitnessapp.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.auth.mask
import dev.fitnessapp.data.remote.AccessCheck
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.mfa.AuthenticatorAssuranceLevel
import io.github.jan.supabase.auth.mfa.MfaLevel
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserMfaFactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val CAPTCHA_WIDGET_MESSAGE = "Security check could not load. Check your connection and try again."

/**
 * UX 8.2 sign-in errors as (`error_class`, message). The CAPTCHA refusal (D95, GoTrue `captcha_failed`:
 * "captcha protection: request disallowed ...") is checked first, since its text is no credentials error.
 */
internal fun classifySignInError(e: Throwable): Pair<String, String> {
    val m = e.message.orEmpty()
    return when {
        e is java.io.IOException || e::class.simpleName == "HttpRequestException" -> "offline" to "No internet. The first sign-in needs a connection."
        m.contains("captcha", ignoreCase = true) -> "captcha" to "Security check failed. Try again."
        m.contains("Invalid login", ignoreCase = true) -> "credentials" to "Email or password is wrong."
        m.contains("rate", ignoreCase = true) -> "rate_limited" to "Too many attempts. Wait a minute and try again."
        else -> "server" to "Sign-in failed. Check your email and password."
    }
}

/**
 * D116: the TOTP factor to verify after a password sign-in, or null when no second step applies (the session
 * is already `aal2`, or the account has no verified TOTP factor). Picks the oldest factor, ties by id.
 */
internal fun totpFactorToVerify(level: MfaLevel, factors: List<UserMfaFactor>): String? {
    if (level.current != AuthenticatorAssuranceLevel.AAL1 || level.next != AuthenticatorAssuranceLevel.AAL2) return null
    return factors.filter { it.isVerified && it.factorType == "totp" }
        .sortedWith(compareBy<UserMfaFactor> { it.createdAt }.thenBy { it.id })
        .firstOrNull()?.id
}

/** D116 / UX 8.2 errors of the authenticator-code step as (`error_class`, message). */
internal fun classifyMfaError(e: Throwable): Pair<String, String> {
    val m = e.message.orEmpty()
    return when {
        e is java.io.IOException || e::class.simpleName == "HttpRequestException" -> "offline" to "No internet. The first sign-in needs a connection."
        m.contains("rate", ignoreCase = true) && !m.contains("TOTP", ignoreCase = true) -> "rate_limited" to "Too many attempts. Wait a minute and try again."
        else -> "mfa_code" to "Code is wrong or expired. Try again."
    }
}

/** Which top-level flow the app shows (UX 8.1): sign-in, rejection, onboarding or the main app. */
enum class Gate { STARTING, SIGNED_OUT, CHECKING, NOT_ALLOWED, SCHEMA_NOT_EXPOSED, OFFLINE, ONBOARDING, READY }

data class AppState(
    val gate: Gate = Gate.STARTING,
    val busy: Boolean = false,
    val error: String? = null,
    val email: String? = null,
    /** The password was accepted and the authenticator code is being asked (D116). */
    val mfaPending: Boolean = false,
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

    fun signIn(email: String, password: String, captchaToken: String?) = authenticate(email, password, captchaToken) {
        c.supabase.auth.currentUserOrNull()?.let { u -> c.account.remember(u.id, u.email) }
        _state.update { it.copy(email = email.trim()) }
        check()
    }

    /**
     * Sign in again after the session expired (D94): only the remembered account is accepted, so one account's
     * rows are never uploaded under another; then the upload resumes. No allowlist check, no onboarding.
     */
    fun reauth(email: String, password: String, captchaToken: String?, onDone: () -> Unit) = authenticate(
        email, password, captchaToken,
        accept = {
            val u = c.supabase.auth.currentUserOrNull()
            val remembered = c.account.userId()
            val ok = u != null && (remembered == null || u.id == remembered)
            if (!ok) {
                runCatching { c.supabase.auth.signOut(SignOutScope.LOCAL) }
                val who = c.account.email()?.let(::mask) ?: "another account"
                _state.update { it.copy(error = "This phone holds the data of $who. Sign in with that account.") }
            }
            ok
        },
    ) {
        val u = c.supabase.auth.currentUserOrNull() ?: return@authenticate
        c.account.remember(u.id, u.email)
        _state.update { it.copy(email = u.email ?: email.trim()) }
        c.sync.syncNow()
        onDone()
    }

    /** The TOTP factor and the rest of the sign-in while the authenticator code is asked (D116). */
    private var mfaFactorId: String? = null
    private var afterMfa: (suspend () -> Unit)? = null

    /** Verifies the authenticator code (D116); the session becomes `aal2`, then the sign-in continues. */
    fun verifyMfa(code: String) {
        val factorId = mfaFactorId ?: return
        val then = afterMfa ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                c.supabase.auth.mfa.createChallengeAndVerify(factorId = factorId, code = code.trim())
                mfaFactorId = null
                afterMfa = null
                _state.update { it.copy(mfaPending = false) }
                then()
            } catch (e: Exception) {
                val (cls, msg) = classifyMfaError(e)
                _state.update { it.copy(error = msg) }
                runCatching { c.fgsEvent("signin_failed", mapOf("error_class" to cls)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Cancel on the code step (D116): the half-finished `aal1` session is signed out; the password form returns. */
    fun cancelMfa() {
        mfaFactorId = null
        afterMfa = null
        _state.update { it.copy(mfaPending = false, error = null) }
        viewModelScope.launch { runCatching { c.supabase.auth.signOut(SignOutScope.LOCAL) } }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** The Turnstile widget gave no token (D95); [code] is the widget's error code, kept out of telemetry. */
    fun captchaFailed(@Suppress("UNUSED_PARAMETER") code: String) {
        _state.update { it.copy(error = CAPTCHA_WIDGET_MESSAGE) }
        viewModelScope.launch { runCatching { c.fgsEvent("signin_failed", mapOf("error_class" to "captcha_widget")) } }
    }

    /**
     * Password sign-in; [accept] may refuse the signed-in account before anything else happens. With a verified
     * TOTP factor the code step comes next (D116) and [then] runs after it; otherwise [then] runs at once.
     */
    private fun authenticate(
        email: String, password: String, captchaToken: String?,
        accept: suspend () -> Boolean = { true }, then: suspend () -> Unit,
    ) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                c.supabase.auth.signInWith(Email) {
                    this.email = email.trim()
                    this.password = password
                    this.captchaToken = captchaToken
                }
                if (!accept()) return@launch
                val mfa = c.supabase.auth.mfa
                val factorId = totpFactorToVerify(mfa.getAuthenticatorAssuranceLevel(), mfa.verifiedFactors)
                if (factorId != null) {
                    mfaFactorId = factorId
                    afterMfa = then
                    _state.update { it.copy(mfaPending = true) }
                } else {
                    then()
                }
            } catch (e: Exception) {
                val (cls, msg) = classifySignInError(e)
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
