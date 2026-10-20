package dev.fitnessapp.app.ui.challenges

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.ui.theme.FitnessTheme
import dev.fitnessapp.app.uidet.GateRow
import dev.fitnessapp.app.uidet.GateRows
import dev.fitnessapp.app.uidet.GateSide
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UX screenshot row 18 (Challenges dashboard, GF-UX-10 numbers) and a proposal tile (14.4). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ChallengeScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val gates = GateRows(
        "Gate to phase 2 · 0 of 4 met", 0, 4,
        listOf(
            GateRow(
                0, "Weighted 1RM ≥ 0.65 x BW - 2 kg (gate e1RM, personal curve)", false, "Target ≥ +43.96 kg at 70.7 kg bodyweight",
                listOf(GateSide("none", "+32.5 kg of +43.96 kg", 0.7386)),
                "+32.5 kg (k 30, class prior) · test 30 Aug · x1.10 rule +35.7 kg · 80 % range +30.9 kg to +33.8 kg",
                "Latest, not valid for gates (19 Sep, 3RM only): +39.4 kg (x1.10 +42.8 kg)",
            ),
            GateRow(
                1, "One-arm dead hang ≥ 20 s, both arms, fresh, neutral handle", false, "Target ≥ 20 s",
                listOf(GateSide("L", "L no valid value", 0.0), GateSide("R", "R no valid value", 0.0)), null,
                "Latest, not valid for gates (29 Sep, not a test day): L 15 s · R 20 s",
            ),
            GateRow(2, "5 s one-arm negative without help, both arms", null, null, listOf(GateSide("L", "L not tested", 0.0), GateSide("R", "R not tested", 0.0)), null, null),
            GateRow(3, "One-arm top hold ≥ 8 s, both arms", false, "Target ≥ 8 s", listOf(GateSide("L", "L 0.5 s of 8 s", 0.0625), GateSide("R", "R 1 s of 8 s", 0.125)), null, null),
        ),
    )

    private val ui = DashboardUi(
        title = "One-arm pull-up", status = "active", statusText = "Test pending", mode = "test_pending", phase = 1,
        phases = listOf("Heavy pulling and learning to hang", "Removing assistance", "Attempts"),
        blockLine = "Block 1 · week 5 of 5 · test pending", nextTest = "Next test: Sun 11 Oct · block-relative",
        precondition = "Needs a clean session first",
        week = listOf(WeekSlotUi("C", "C done Tue", true), WeekSlotUi("A", "A Thu 19:45", false), WeekSlotUi("TEST", "TEST Sun 11:00", false)),
        gates = gates, rules = emptyList(), proposals = emptyList(), history = emptyList(), tests = emptyList(), undecided = 0,
        pausedSince = null, targets = emptyList(),
    )

    @Test
    fun dashboardOverview() {
        compose.setContent {
            FitnessTheme {
                Surface(color = Fit.Ground) {
                    Column(Modifier.padding(12.dp).width(387.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { DashboardOverview(ui) }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/challenge_dashboard.png")
    }

    @Test
    fun proposalTile() {
        compose.setContent {
            FitnessTheme {
                Surface(color = Fit.Ground) {
                    Column(Modifier.padding(12.dp).width(387.dp)) {
                        ProposalTile(
                            ProposalUi(
                                "P01", "Gate the weekly session-A pin decrease",
                                "An unconditional -5 lb per week is about +4.9 % one-arm load per week; gating it on completed sets and adding +5 lb after two failed weeks mirrors the program's own 90-degree-hold logic.",
                                "The pin drops 5 lb (10 lb after a controlled calibration negative) after every session A in a normal week, as written.",
                                "prog.A.pin.weekly -> prog.A.pin.gated", "approved",
                            ),
                            onDecide = { _, _ -> }, onViewed = {},
                        )
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/challenge_proposal.png")
    }
}
