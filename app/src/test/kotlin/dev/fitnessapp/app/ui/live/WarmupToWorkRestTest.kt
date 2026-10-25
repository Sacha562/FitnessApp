package dev.fitnessapp.app.ui.live

import dev.fitnessapp.data.db.entity.SessionExerciseEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.repo.CatalogRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** UX 19.8: the last challenge warm-up set before the first working set rests `warmup.rest_to_work_s`. */
class WarmupToWorkRestTest {
    private val ex = CatalogRepository.bundled().exercises.first()

    private fun item(id: String, planned: Int, done: Int, warmup: Boolean, status: String = "active") = ExerciseItem(
        SessionExerciseEntity(
            id = id, user_id = "u", client_updated_at = 0L, device_id = "d", app_version = "t", session_id = "s",
            block_id = "b", exercise_id = "e", position = 0L, origin = "challenge", status = status, load_mode = "bodyweight",
            progression_mode = "none", rep_scheme = "straight", planned_sets = planned,
        ),
        ex,
        List(done) { i ->
            SetLogEntity(
                id = "$id-$i", user_id = "u", client_updated_at = 0L, device_id = "d", app_version = "t", session_id = "s",
                session_exercise_id = id, exercise_id = "e", set_index = i + 1, load_mode = "bodyweight", completed_at = 0L,
            )
        },
        warmupItem = warmup,
    )

    @Test
    fun `last set of the last warm-up item leads to work`() {
        val face = item("face", planned = 1, done = 1, warmup = true, status = "done")
        val pull = item("pull", planned = 2, done = 1, warmup = true)
        val wpu = item("wpu", planned = 4, done = 0, warmup = false, status = "pending")
        LiveSessionViewModel.warmupLeadsToWork(listOf(face, pull, wpu), "pull", setNumber = 2, planned = 2, warmupItem = true) shouldBe true
    }

    @Test
    fun `earlier warm-up sets and items keep the short rest`() {
        val face = item("face", planned = 1, done = 0, warmup = true)
        val pull = item("pull", planned = 2, done = 0, warmup = true, status = "pending")
        val wpu = item("wpu", planned = 4, done = 0, warmup = false, status = "pending")
        val items = listOf(face, pull, wpu)
        LiveSessionViewModel.warmupLeadsToWork(items, "face", setNumber = 1, planned = 1, warmupItem = true) shouldBe false
        LiveSessionViewModel.warmupLeadsToWork(items, "pull", setNumber = 1, planned = 2, warmupItem = true) shouldBe false
    }

    @Test
    fun `no working item left or a manual warm-up set keeps the short rest`() {
        val pull = item("pull", planned = 2, done = 1, warmup = true)
        LiveSessionViewModel.warmupLeadsToWork(listOf(pull), "pull", setNumber = 2, planned = 2, warmupItem = true) shouldBe false
        val wpu = item("wpu", planned = 4, done = 0, warmup = false)
        LiveSessionViewModel.warmupLeadsToWork(listOf(wpu), "wpu", setNumber = 1, planned = 4, warmupItem = false) shouldBe false
    }
}
