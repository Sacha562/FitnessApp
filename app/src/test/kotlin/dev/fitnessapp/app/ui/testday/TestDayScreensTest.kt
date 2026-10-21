package dev.fitnessapp.app.ui.testday

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.fitnessapp.algo.challenge.materialize.LogMode
import dev.fitnessapp.algo.challenge.materialize.PlannedItem
import dev.fitnessapp.algo.challenge.materialize.PlannedRow
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.ui.theme.FitnessTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UX 14.9 test-day wizard (D35, D36): overview, a Record card, an attempt card, the review and the report. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TestDayScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, content: @Composable ColumnScope.() -> Unit) {
        compose.setContent {
            FitnessTheme {
                Surface(color = Fit.Ground) {
                    Column(Modifier.padding(12.dp).width(387.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun step(n: Int, key: String, kind: StepKind, metric: String?, sides: List<String> = listOf("both"), skipped: String? = null) = TestStep(
        n, key, kind,
        listOf(PlannedItem(key, key, key, mapOf("en" to key), "test", "planned", null, sides.map { PlannedRow(it, "test", 1, logMode = LogMode.TEST_RECORD) }, progressionMode = "none", metric = metric)),
        skipped,
    )

    private val lr = listOf("L", "R")

    @Test
    fun overview() = shot("test_overview") {
        TestOverview(
            TestDayUi(
                phase = Phase.OVERVIEW, validity = ValidityUi(false, "First one normal session without pain.", "needs_clean_session"),
                steps = listOf(
                    step(1, "bw", StepKind.WEIGH, "bw"), step(2, "warmup", StepKind.WARMUP, null), step(3, "str", StepKind.ATTEMPTS, "wpu_3rm_added"),
                    step(4, "hang", StepKind.HOLD, "oa_hang_s", lr), step(5, "top", StepKind.HOLD, "oa_top_hold_s", lr),
                    step(6, "assist", StepKind.LADDER, "oa_least_assist_pin", lr), step(7, "neg", StepKind.BOOL, "oa_neg5_unassisted", lr, "skipIf: s.elbowTrace"),
                    step(8, "max", StepKind.REPS, "bw_max_reps"),
                ),
            ),
        )
    }

    @Test
    fun strengthAttempt() = shot("test_record_card") {
        RecordCard(
            CardHead("Weighted pull-up 3RM", null, "Attempt 2 of 3", "Best so far: +32.5 kg", "Ramp 50 %, 70 %, 85 % of the expected 3RM total x 1-3 reps; then attempts in +2.5 kg steps."),
            "BW + 35 kg", "20 + 15 on belt", {}, {}, "Clean × 3", {}, secondaryAction = "Not clean" to {}, onUndo = {}, onSkip = {},
        )
    }

    @Test
    fun attempt() = shot("test_attempt_card") {
        AttemptCard(
            CardHead("One-arm 5 s negative, no help", "Left", null, null, "5 s one-arm negative without any help; the hand does not shift."),
            {}, {}, checklist = listOf("Started from a dead hang with the arm fully extended", "Chin over the bar"), onSkip = {},
        )
    }

    @Test
    fun review() = shot("test_review") {
        TestReview(
            ReviewUi(
                listOf(
                    LineUi("Bodyweight", "70.4 kg", "vs 30 Aug: −0.3 kg"), LineUi("Weighted pull-up 3RM", "+35 kg", "vs 30 Aug: +2.5 kg"),
                    LineUi("One-arm dead hang", "L 16 s · R 21 s", "vs 30 Aug: R +1 s"), LineUi("Least assistance for one rep", "L 45 lb · R 40 lb"),
                    LineUi("Max strict pull-ups", "19", "vs 30 Aug: +1"),
                ),
                true, null, "Goals for phase 2 · 1 of 4 met",
            ),
        )
    }

    @Test
    fun report() = shot("test_report") {
        TestReport(
            ReportUi(
                "Test saved", null, LineUi("Weighted pull-up strength", "+41.97 kg"), "×1.10 estimate +42.8 kg · 80 % range +37.2 kg to +44.4 kg",
                listOf(
                    LineUi("Weighted pull-up, session C (anchor)", "+22.5 kg", "was +20 kg"), LineUi("Weighted pull-up, session A", "+25 kg", "was +22.5 kg"),
                    LineUi("Assisted pin, session A", "55 lb", "was 40 lb"),
                ),
                "Goals for phase 2 · 1 of 4 met", "Next test: Sun 29 Nov", emptyList(),
            ),
        )
    }
}
