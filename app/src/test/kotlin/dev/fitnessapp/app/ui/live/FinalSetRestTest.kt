package dev.fitnessapp.app.ui.live

import dev.fitnessapp.data.db.entity.SessionExerciseEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.repo.CatalogRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** UX 19.8: no rest timer after the set that finishes the session's planned work. */
class FinalSetRestTest {
    private val ex = CatalogRepository.bundled().exercises.first()

    private fun item(id: String, planned: Int, done: Int, status: String = "active") = ExerciseItem(
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
    fun `last planned set of the only exercise starts no rest`() {
        val a = item("a", planned = 3, done = 2)
        LiveSessionViewModel.completesPlannedWork(listOf(a), "a", setNumber = 3, planned = 3, warmup = false, side = "both") shouldBe true
    }

    @Test
    fun `earlier sets and warm-ups still rest`() {
        val a = item("a", planned = 3, done = 1)
        LiveSessionViewModel.completesPlannedWork(listOf(a), "a", setNumber = 2, planned = 3, warmup = false, side = "both") shouldBe false
        LiveSessionViewModel.completesPlannedWork(listOf(a), "a", setNumber = 3, planned = 3, warmup = true, side = "both") shouldBe false
    }

    @Test
    fun `another exercise with sets left still rests`() {
        val a = item("a", planned = 3, done = 2)
        val b = item("b", planned = 3, done = 0, status = "pending")
        LiveSessionViewModel.completesPlannedWork(listOf(a, b), "a", setNumber = 3, planned = 3, warmup = false, side = "both") shouldBe false
        val skipped = b.copy(se = b.se.copy(status = "skipped"))
        LiveSessionViewModel.completesPlannedWork(listOf(a, skipped), "a", setNumber = 3, planned = 3, warmup = false, side = "both") shouldBe true
    }

    @Test
    fun `left side of a unilateral pair never ends the work`() {
        val a = item("a", planned = 1, done = 0)
        LiveSessionViewModel.completesPlannedWork(listOf(a), "a", setNumber = 1, planned = 1, warmup = false, side = "L") shouldBe false
    }
}
