package dev.fitnessapp.app.nav

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The saved back stack survives recreation: every key encodes and decodes to itself (ANDROID.md 7.4). */
class KeysTest {
    @Test
    fun `every key round-trips`() {
        val keys = listOf(
            AppKey.Main, AppKey.LiveSession("s1"), AppKey.Summary("s2"), AppKey.DataSync, AppKey.Equipment, AppKey.Reliability,
            AppKey.History, AppKey.SessionDetail("0192f0a0-0000-7000-8000-000000000001"),
            AppKey.ExerciseDetail("0192f0a0-0000-7000-8000-000000000002", "list"), AppKey.DataSyncExport,
        )
        keys.forEach { AppKey.decode(AppKey.encode(it)) shouldBe it }
    }

    @Test
    fun `notification routes`() {
        Routes.parse("fitapp://you/data/export") shouldBe Routes.Target.DataSyncExport
        Routes.parse("fitapp://you/data") shouldBe Routes.Target.DataSync
        Routes.parse("fitapp://session/current") shouldBe Routes.Target.CurrentSession
        Routes.parse("https://example.org") shouldBe Routes.Target.Today
    }
}
