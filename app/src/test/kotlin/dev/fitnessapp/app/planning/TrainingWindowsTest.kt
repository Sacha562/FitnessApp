package dev.fitnessapp.app.planning

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalTime

/** D75: unset times give the default evenings and weekend days; "Any time" and switched-off days are explicit. */
class TrainingWindowsTest {
    @Test
    fun `unset times use the defaults, not the whole day`() {
        TrainingWindows.isSet("{}") shouldBe false
        TrainingWindows.isSet(null) shouldBe false
        val w = TrainingWindows.effective("{}")
        w.getValue("tue") shouldBe (LocalTime.of(19, 45) to LocalTime.of(21, 15))
        w.getValue("sat") shouldBe (LocalTime.of(9, 0) to LocalTime.of(16, 0))
    }

    @Test
    fun `saved times win and a switched-off day has no window`() {
        val json = TrainingWindows.json(mapOf("tue" to ("18:00" to "21:00"), "sun" to ("10:00" to "12:00")))
        TrainingWindows.isSet(json) shouldBe true
        val w = TrainingWindows.effective(json)
        w.getValue("tue") shouldBe (LocalTime.of(18, 0) to LocalTime.of(21, 0))
        w.containsKey("mon") shouldBe false
        TrainingWindows.summary(json) shouldBe "Tue 18:00-21:00 · Sun 10:00-12:00"
    }

    @Test
    fun `any time is stored explicitly`() {
        val json = TrainingWindows.json(TrainingWindows.ANY_TIME)
        TrainingWindows.isSet(json) shouldBe true
        TrainingWindows.summary(json) shouldBe "Any time the gym is open"
        TrainingWindows.effective(json).getValue("mon") shouldBe (LocalTime.MIDNIGHT to LocalTime.of(23, 59))
    }
}
