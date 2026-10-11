package dev.fitnessapp.app.ui.you

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

/** "Last export" line on Data and sync: calendar days in the app's zone, not elapsed 24 h blocks. */
class LastExportLineTest {
    private fun ms(s: String) = ZonedDateTime.parse(s).toInstant().toEpochMilli()

    @Test
    fun `same day reads today, the day before yesterday even when under 24 h ago`() {
        val export = ms("2026-10-11T19:35:00+02:00[Europe/Amsterdam]")
        lastExportLine(export, ms("2026-10-11T23:59:00+02:00[Europe/Amsterdam]")) shouldBe "Last export: Sun 11 Oct 2026 (today)"
        lastExportLine(export, ms("2026-10-12T07:00:00+02:00[Europe/Amsterdam]")) shouldBe "Last export: Sun 11 Oct 2026 (yesterday)"
        lastExportLine(export, ms("2026-11-10T09:00:00+01:00[Europe/Amsterdam]")) shouldBe "Last export: Sun 11 Oct 2026 (30 days ago)"
    }
}
