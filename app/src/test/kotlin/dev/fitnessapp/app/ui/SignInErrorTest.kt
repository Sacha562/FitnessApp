package dev.fitnessapp.app.ui

import dev.fitnessapp.app.ui.auth.turnstilePage
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun widgetPageCarriesTheKeyAndTheBridgeCallbacks() {
        val page = turnstilePage("0x4AAAAAAATEST")
        assertTrue(page.contains("sitekey: '0x4AAAAAAATEST'"))
        assertTrue(page.contains("appearance: 'interaction-only'"))
        listOf("Bridge.token", "Bridge.error", "Bridge.interactive").forEach { assertTrue(page.contains(it), it) }
    }
}
