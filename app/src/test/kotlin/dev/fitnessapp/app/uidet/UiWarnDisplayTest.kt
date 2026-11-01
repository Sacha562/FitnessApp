package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.prescription.warnings.Severity
import dev.fitnessapp.algo.prescription.warnings.Warning
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/** UI-10 (ALGORITHMS_PLANNING 19.5): safetyAlwaysShown, infoNeverBanner, snoozeAndResurface, useMineMarker. */
class UiWarnDisplayTest {
    private val params = ParamView.fromParamsFile(
        requireNotNull(javaClass.getResource("/fitness/params/default-params-1.0.2.json")).readText(Charsets.UTF_8),
    )
    private val now = Instant.parse("2026-10-27T18:00:00Z")

    private fun w(rule: String, sev: Severity, mag: Double, entity: String = "e", enforced: Boolean = false) =
        Warning(rule, sev, entity, mag, "moderate", enforced)

    private fun show(ws: List<Warning>, dismissals: Map<String, Dismissal> = emptyMap(), useMine: Set<String> = emptySet(), ack: Set<String> = emptySet()) =
        UiWarnDisplay.display(ws, dismissals, useMine, ack, now, params)

    @Test
    fun safetyAlwaysShown() {
        val r = show(listOf(w("WE-S1", Severity.SAFETY, 1.0), w("WE-P2", Severity.WARN, 0.5)))
        r.safety.single().ruleId shouldBe "WE-S1"
        r.banner!!.ruleId shouldBe "WE-P2"
        // An enforced SAFETY (the engine clamped its own output) needs no action: list and dot only.
        show(listOf(w("WE-S2", Severity.SAFETY, 0.2, enforced = true))).safety.shouldBeEmpty()
        // Acknowledged notices leave the safety slot.
        show(listOf(w("WE-S1", Severity.SAFETY, 1.0)), ack = setOf("WE-S1|e")).safety.shouldBeEmpty()
    }

    @Test
    fun infoNeverBanner() {
        val r = show(listOf(w("WE-P4", Severity.INFO, 0.9)))
        r.banner.shouldBeNull()
        r.moreNotes shouldBe 1
        r.markers["e"] shouldBe WarnMarker.MUTED_DOT
    }

    @Test
    fun snoozeAndResurface() {
        val d = mapOf("WE-P2|e" to Dismissal(now.minusSeconds(86_400), 0.4))
        show(listOf(w("WE-P2", Severity.WARN, 0.45)), d).banner.shouldBeNull()
        show(listOf(w("WE-P2", Severity.WARN, 0.55)), d).banner!!.magnitude shouldBe 0.55
        // After the snooze the banner returns at the old magnitude.
        val old = mapOf("WE-P2|e" to Dismissal(now.minusSeconds(29L * 86_400), 0.4))
        show(listOf(w("WE-P2", Severity.WARN, 0.4)), old).banner!!.ruleId shouldBe "WE-P2"
    }

    @Test
    fun useMineMarker() {
        val r = show(listOf(w("WE-P2", Severity.WARN, 0.5)), useMine = setOf("WE-P2|e"))
        r.banner.shouldBeNull()
        r.markers["e"] shouldBe WarnMarker.YOUR_CHOICE
    }

    @Test
    fun smallMagnitudesStayInTheList() {
        val r = show(listOf(w("WE-P6", Severity.WARN, 0.1), w("WE-P1", Severity.STRONG, 0.6), w("WE-P2", Severity.WARN, 0.8)))
        r.banner!!.ruleId shouldBe "WE-P1"
        r.moreNotes shouldBe 2
        r.notes.map { it.ruleId } shouldBe listOf("WE-P2", "WE-P6")
    }

    @Test
    fun `an OK given today survives the reopened session screen, yesterday's does not`() {
        val zone = java.time.ZoneId.of("Europe/Amsterdam")
        val now = Instant.parse("2026-11-01T21:24:05Z")
        val k = UiWarnDisplay.key("WE-S4", "session_exercise:a")
        val old = UiWarnDisplay.key("WE-S4", "session_exercise:b")
        val acks = UiWarnDisplay.acknowledgedToday(listOf(k to Instant.parse("2026-11-01T21:05:13Z"), old to Instant.parse("2026-10-31T22:59:00Z")), now, zone)
        acks shouldBe setOf(k)
    }
}
