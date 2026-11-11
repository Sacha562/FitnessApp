package dev.fitnessapp.app.ui

import dev.fitnessapp.app.ui.auth.turnstilePage
import io.github.jan.supabase.auth.mfa.AuthenticatorAssuranceLevel
import io.github.jan.supabase.auth.mfa.MfaLevel
import io.github.jan.supabase.auth.user.UserMfaFactor
import kotlin.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** UX 8.2 error classes, including the CAPTCHA refusal of D95. */
class SignInErrorTest {
    private fun cls(msg: String) = classifySignInError(RuntimeException(msg)).first

    @Test
    fun captchaRefusalIsNotACredentialsError() {
        assertEquals("captcha", cls("captcha protection: request disallowed (no captcha_token found)"))
        assertEquals("Security check failed. Try again.", classifySignInError(RuntimeException("captcha_failed")).second)
    }

    @Test
    fun existingClassesKeepTheirMapping() {
        assertEquals("credentials", cls("Invalid login credentials"))
        assertEquals("rate_limited", cls("Request rate limit reached"))
        assertEquals("server", cls("Database error querying schema"))
        assertEquals("offline", classifySignInError(java.io.IOException("x")).first)
    }

    private fun factor(id: String, type: String = "totp", status: String = "verified", created: Long = 0) =
        UserMfaFactor(id, Instant.fromEpochSeconds(created), Instant.fromEpochSeconds(created), status, null, type, null)

    private val aal1to2 = MfaLevel(AuthenticatorAssuranceLevel.AAL1, AuthenticatorAssuranceLevel.AAL2, emptyList())

    @Test
    fun codeStepOnlyForAnAal1SessionWithAVerifiedTotpFactor() {
        assertEquals("f1", totpFactorToVerify(aal1to2, listOf(factor("f1"))))
        // No MFA on the account (D116: not every account has it): no step.
        assertNull(totpFactorToVerify(MfaLevel(AuthenticatorAssuranceLevel.AAL1, AuthenticatorAssuranceLevel.AAL1, emptyList()), emptyList()))
        // Already aal2.
        assertNull(totpFactorToVerify(MfaLevel(AuthenticatorAssuranceLevel.AAL2, AuthenticatorAssuranceLevel.AAL2, emptyList()), listOf(factor("f1"))))
        // Only a phone factor or an unverified TOTP: no step.
        assertNull(totpFactorToVerify(aal1to2, listOf(factor("p", type = "phone"), factor("u", status = "unverified"))))
    }

    @Test
    fun oldestTotpFactorWins() {
        assertEquals("old", totpFactorToVerify(aal1to2, listOf(factor("new", created = 20), factor("old", created = 10))))
        assertEquals("a", totpFactorToVerify(aal1to2, listOf(factor("b"), factor("a"))))
    }

    @Test
    fun mfaErrors() {
        assertEquals("mfa_code" to "Code is wrong or expired. Try again.", classifyMfaError(RuntimeException("Invalid TOTP code entered")))
        assertEquals("mfa_code", classifyMfaError(RuntimeException("MFA challenge has expired")).first)
        assertEquals("rate_limited", classifyMfaError(RuntimeException("Request rate limit reached")).first)
        assertEquals("offline", classifyMfaError(java.io.IOException("x")).first)
    }

    @Test
    fun widgetPageCarriesTheKeyAndTheBridgeCallbacks() {
        val page = turnstilePage("0x4AAAAAAATEST")
        assertTrue(page.contains("sitekey: '0x4AAAAAAATEST'"))
        assertTrue(page.contains("appearance: 'interaction-only'"))
        listOf("Bridge.token", "Bridge.error", "Bridge.interactive").forEach { assertTrue(page.contains(it), it) }
    }
}
