package dev.fitnessapp.algo.core.calendar

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** GF-CE-27 and the marker cases of ALGORITHMS_PLANNING 1.7. */
class MarkerTest {
    private val id = "0199c3a0-5d2e-7b41-8f3a-1c2d3e4f5a6b"

    @Test
    fun `GF-CE-27 render and parse round trip`() {
        val m = Marker(id, "oap/C/2026-W42")
        m.render() shouldBe "[fitapp:v1 0199c3a0-5d2e-7b41-8f3a-1c2d3e4f5a6b oap/C/2026-W42]"
        Marker.parseLine(m.render()) shouldBe m
        m.parsedKey() shouldBe SlotKey.Challenge("oap", "C", "2026-W42")
        Marker.parseLine("[fitapp:v1 $id oap/TEST/2026-W46]")!!.parsedKey() shouldBe SlotKey.Challenge("oap", "TEST", "2026-W46")
        Marker.parseLine("[fitapp:v1 $id gen/2026-W42/5]")!!.parsedKey() shouldBe SlotKey.General("2026-W42", 5)
    }

    @Test
    fun `only the last non-empty line counts`() {
        val line = "[fitapp:v1 $id oap/A/2026-W41]"
        Marker.ofDescription("Weighted pull-ups 4 x 4\n\nPlanned by FitnessApp.\n$line\n  \n") shouldBe Marker(id, "oap/A/2026-W41")
        Marker.ofDescription("$line\nmy own note") shouldBe null
        Marker.ofDescription("[fitapp:v1 0199C3A0-5d2e-7b41-8f3a-1c2d3e4f5a6b oap/A/2026-W41]") shouldBe null
        Marker.ofDescription(null) shouldBe null
    }

    @Test
    fun `foreign slot keys parse but are foreign`() {
        Marker.parseLine("[fitapp:v1 $id something]")!!.parsedKey() shouldBe SlotKey.Foreign("something")
        Marker.parseLine("[fitapp:v1 $id gen/2026-W42/8]")!!.parsedKey() shouldBe SlotKey.Foreign("gen/2026-W42/8")
    }

    @Test
    fun `like guard escapes the wildcards`() {
        Marker(id, "oap/C_x/2026-W42").likePattern() shouldBe "%[fitapp:v1 $id oap/C\\_x/2026-W42]%"
        Marker.likeEscape("a\\b%c") shouldBe "a\\\\b\\%c"
    }

    @Test
    fun `withMarker appends after the user's text or replaces the marker line`() {
        val a = Marker(id, "oap/TEST/2026-W41")
        val b = Marker(id, "oap/B/2026-W41")
        Marker.withMarker("Bring chalk", a) shouldBe "Bring chalk\n\n${a.render()}"
        Marker.withMarker(null, a) shouldBe a.render()
        Marker.withMarker("Lines\n\nPlanned by FitnessApp.\n${a.render()}", b) shouldBe "Lines\n\nPlanned by FitnessApp.\n${b.render()}"
    }

    @Test
    fun `normalises Dutch titles`() {
        CalendarText.normalize("Gym — sessie A (één arm zwaar)") shouldBe "gym - sessie a (een arm zwaar)"
        CalendarText.normalize("  Basic Fit\t Ierse  Pond ") shouldBe "basic fit ierse pond"
        CalendarText.normalize(null) shouldBe ""
    }
}
