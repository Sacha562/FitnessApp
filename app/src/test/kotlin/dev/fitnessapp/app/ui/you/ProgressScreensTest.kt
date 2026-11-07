package dev.fitnessapp.app.ui.you

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.app.ui.summary.NewBestTile
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.ui.theme.FitnessTheme
import dev.fitnessapp.app.uidet.GateRow
import dev.fitnessapp.app.uidet.GateRows
import dev.fitnessapp.app.uidet.GateSide
import dev.fitnessapp.app.uidet.ProgressAxis
import dev.fitnessapp.app.uidet.ProgressExercise
import dev.fitnessapp.app.uidet.ProgressLevel
import dev.fitnessapp.app.uidet.ProgressMetric
import dev.fitnessapp.app.uidet.ProgressSet
import dev.fitnessapp.app.uidet.UiProgress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

/** You → Progress (UX 15.2, D86 (5)) and the Summary's new bests (12.3), on data shaped like the user's weeks 41-45. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1500dp-xxhdpi")
class ProgressScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val params = ParamView.fromParamsFile(
        requireNotNull(javaClass.getResource("/fitness/params/default-params-1.0.8.json")).readText(Charsets.UTF_8),
    )
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val first = LocalDate.parse("2026-08-17")

    private fun ms(d: LocalDate) = d.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()

    /** Weighted pull-ups, two sessions a week for twelve weeks; a test day every five weeks. */
    private val pullup: ExerciseProgress = run {
        val sets = ArrayList<ProgressSet>()
        val levels = ArrayList<ProgressLevel>()
        for (i in 0 until 24) {
            val d = first.plusDays(i * 3L + i / 2)
            val added = 12.0 + i * 1.15 + (if (i % 3 == 0) 1.2 else if (i % 3 == 1) -0.8 else 0.0)
            val test = i == 9 || i == 19
            sets += ProgressSet("p$i", "s$i", ms(d), d, "both", test, 70.7 + added, added, "normal", 70.7 + added - 6, added - 6, 5, bodyweightKg = 70.7)
            val m = 70.7 + 12.0 + i * 1.12
            levels += ProgressLevel("s$i", ms(d), "both", m, m - 3.6 + i * 0.05, m + 3.6 - i * 0.05, 70.7)
        }
        val ex = ProgressExercise(1.0, 0.0)
        ExerciseProgress(
            "wpu", "weighted_pullup", "Weighted pull-up", sets, levels, emptyList(), ex, 24, sets.last().atMs, UiProgress.bests(sets, params),
            UiProgress.Gate("Weighted pull-up e1RM", 37.4, LocalDate.parse("2026-10-11"), 40.7, 43.96, 70.7, 43.63, 70.2, null),
        )
    }

    private val bench: ExerciseProgress = run {
        val sets = (0 until 8).map { i ->
            val d = first.plusWeeks(4).plusDays(i * 5L)
            ProgressSet("b$i", "t$i", ms(d), d, "both", false, 80.0 + i * 1.6, 60.0 + i * 1.6, "normal", 60.0 + i * 1.25, 60.0 + i * 1.25, 6 + i % 3, bodyweightKg = 70.7)
        }
        ExerciseProgress("bench", "bench_press_barbell", "Bench press", sets, emptyList(), emptyList(), ProgressExercise(0.0, 20.0), 8, sets.last().atMs, UiProgress.bests(sets, params), null)
    }

    private val meso = MesoCard(
        "Mesocycle 1 · block 2 · week 1 of 5",
        listOf(
            MesoFamily("LEGS", "Legs", listOf(MesoLift("LEGS", "leg_press_45", "lp", "Leg press", "sel1"), MesoLift("LEGS", "rdl_barbell", "rdl", "Romanian deadlift", "sel1")), null),
            MesoFamily("PUSH", "Push", listOf(MesoLift("PUSH", "bench_press_barbell", "bench", "Bench press", "sel1"), MesoLift("PUSH", "machine_shoulder_press", "msp", "Machine shoulder press", "sel1")), null),
            MesoFamily("PULL", "Pull", emptyList(), "covered by One-arm pull-up"),
            MesoFamily("CORE", "Core", emptyList(), "no compound lifts, accessories only"),
        ),
        listOf("Rotated: lying leg curl → glute-ham raise (no new best in 3 sessions)"),
    )

    private val gates = GateRows(
        "Goals for phase 2 · 1 of 3 met", 1, 3,
        listOf(
            GateRow(0, "Weighted pull-up e1RM ≥ 65 % BW − 2 kg", false, "Target ≥ +43.96 kg at 70.7 kg bodyweight", listOf(GateSide("none", "+37.4 kg of +43.96 kg", 0.8508)), "Tested 11 Oct", null),
            GateRow(1, "Assisted one-arm pull-up ≤ 40 lb", true, "Target ≤ 40 lb", listOf(GateSide("L", "L 40 lb of 40 lb", 1.0), GateSide("R", "R 45 lb of 40 lb", 0.8889)), null, null),
        ),
    )

    private val ui = ProgressUi(
        meso, listOf(bench, pullup), "wpu", gates,
        (0 until 12).map { i -> BwPoint(first.plusWeeks(i.toLong()), 70.2 + (i % 4) * 0.3 + i * 0.03, 70.4 + i * 0.04, i == 8) },
        (bench.bests.map { BestRow("bench", "Bench press", it) } + pullup.bests.map { BestRow("wpu", "Weighted pull-up", it) }).sortedByDescending { it.best.date },
    )

    private fun shot(name: String, pick: ProgressPick, volume: List<VolumeWeekRow>? = null) {
        compose.setContent {
            FitnessTheme {
                Surface(color = Fit.Ground) {
                    ProgressScreen(ui, volume, pick, params, PaddingValues(0.dp), onPick = {}, onChange = {})
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/progress_$name.png")
    }

    @Test
    fun `exercise tab with the gate line`() = shot("exercise", ProgressPick(exerciseId = "wpu", metric = ProgressMetric.STRENGTH, point = "p21"))

    @Test
    fun `exercise tab on system kg as a table`() =
        shot("table", ProgressPick(exerciseId = "bench", metric = ProgressMetric.STRENGTH, axis = ProgressAxis.SYSTEM, range = ProgressRange.ALL, table = true))

    @Test
    fun `bodyweight tab`() = shot("bodyweight", ProgressPick(tab = ProgressTab.BODYWEIGHT))

    @Test
    fun `volume tab`() = shot(
        "volume", ProgressPick(tab = ProgressTab.VOLUME, volumeGroup = "chest"),
        listOf(
            VolumeWeekRow("2026-W43", mapOf("chest" to 6.0, "quads" to 4.0), mapOf("chest" to 10.0, "quads" to 10.0)),
            VolumeWeekRow("2026-W44", mapOf("chest" to 3.0, "quads" to 2.0), mapOf("chest" to 5.0, "quads" to 5.0)),
            VolumeWeekRow("2026-W45", mapOf("chest" to 4.5, "quads" to 0.0), mapOf("chest" to 12.0, "quads" to 12.0)),
        ),
    )

    @Test
    fun `bests tab`() = shot("bests", ProgressPick(tab = ProgressTab.BESTS))

    @Test
    fun `summary new bests`() {
        val b = ExerciseBests("Bench press", false, bench.bests)
        val w = ExerciseBests("Weighted pull-up", true, pullup.bests)
        val bests = listOf(w to pullup.bests.last(), b to bench.bests.last(), w to pullup.bests[pullup.bests.size - 2], b to bench.bests[bench.bests.size - 2])
        compose.setContent {
            FitnessTheme { Surface(color = Fit.Ground, modifier = Modifier.padding(16.dp)) { NewBestTile(bests, 3) } }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/summary_new_best.png")
    }
}
