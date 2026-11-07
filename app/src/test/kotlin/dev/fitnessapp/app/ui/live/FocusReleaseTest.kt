package dev.fitnessapp.app.ui.live

import dev.fitnessapp.data.db.entity.SessionExerciseEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.repo.CatalogRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A list-tap focus holds on an item, but ending or skipping that item moves the focus on (2026-11-06 top hold). */
class FocusReleaseTest {
    private val ex = CatalogRepository.bundled().exercises.first()

    private fun item(id: String, planned: Int, done: Int, status: String) = ExerciseItem(
        SessionExerciseEntity(
            id = id, user_id = "u", client_updated_at = 0L, device_id = "d", app_version = "t", session_id = "s",
            block_id = "b", exercise_id = "e", position = 0L, origin = "user", status = status, load_mode = "external",
            progression_mode = "double", rep_scheme = "straight", planned_sets = planned,
        ),
        ex,
        List(done) { i ->
            SetLogEntity(
                id = "$id-$i", user_id = "u", client_updated_at = 0L, device_id = "d", app_version = "t", session_id = "s",
                session_exercise_id = id, exercise_id = "e", set_index = i + 1, load_mode = "external", completed_at = 0L,
            )
        },
    )

    @Test
    fun `a list tap focuses an open item`() {
        val items = listOf(item("a", 3, 0, "pending"), item("b", 3, 1, "active"))
        LiveSessionViewModel.focusOf(items, "b") shouldBe "b"
    }

    @Test
    fun `a finished item with planned sets left can be reopened from the list`() {
        val items = listOf(item("a", 3, 2, "done"), item("b", 3, 0, "pending"))
        LiveSessionViewModel.focusOf(items, "a") shouldBe "a"
    }

    @Test
    fun `ending the tapped item early releases the focus to the next open item`() {
        val ended = listOf(item("hold", 3, 2, "done"), item("next", 3, 0, "pending"))
        val override = LiveSessionViewModel.releaseFocus("hold", "hold")
        override shouldBe null
        LiveSessionViewModel.focusOf(ended, override) shouldBe "next"
    }

    @Test
    fun `skipping the tapped item releases the focus`() {
        val skipped = listOf(item("a", 3, 0, "skipped"), item("b", 3, 0, "pending"))
        LiveSessionViewModel.focusOf(skipped, LiveSessionViewModel.releaseFocus("a", "a")) shouldBe "b"
    }

    @Test
    fun `ending another item keeps the tapped focus`() {
        LiveSessionViewModel.releaseFocus("a", "b") shouldBe "a"
        LiveSessionViewModel.releaseFocus(null, "b") shouldBe null
    }
}
