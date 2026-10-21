package dev.fitnessapp.app.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.fitnessapp.app.ui.theme.FitnessTheme
import dev.fitnessapp.data.db.entity.WellnessLogEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZonedDateTime

/** UX 15.3 quick check: the answered rows before and after the 24 h window; empty ones show only an Add link; the links and the hint disappear. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class QuickCheckTest {
    @get:Rule
    val compose = createComposeRule()

    private val pain = WellnessLogEntity(
        id = "w1", user_id = "u", client_updated_at = 0, device_id = "d", app_version = "t", kind = "pain", phase = "post",
        observed_at = 0, local_date = "2026-10-09", pain_body_region = "elbow_lateral", pain_severity = "moderate",
    )

    private fun show(editableUntil: Long, name: String) {
        compose.mainClock.autoAdvance = false // keep the window open: the composable flips at editableUntil
        compose.setContent {
            FitnessTheme {
                Surface {
                    Column(Modifier.padding(16.dp).width(380.dp)) {
                        QuickCheck(srpe = 9, pains = listOf(pain), note = null, editableUntil = editableUntil, onEffort = {}, onPain = {}, onNote = {})
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/quickcheck_$name.png")
    }

    @Test
    fun editableShowsLinksAndHint() {
        show(ZonedDateTime.parse("2099-10-12T11:10:00+02:00[Europe/Amsterdam]").toInstant().toEpochMilli(), "editable")
        compose.onNodeWithText("Session effort: 9").assertIsDisplayed()
        compose.onNodeWithText("Pain: Elbow, outer side (moderate)").assertIsDisplayed()
        compose.onAllNodesWithText("Note", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Change").assertCountEquals(1)
        compose.onNodeWithText("Add pain").assertIsDisplayed()
        compose.onNodeWithText("Add note").assertIsDisplayed()
        compose.onNodeWithText("Editable until Mon 12 Oct 11:10").assertIsDisplayed()
    }

    @Test
    fun lockedKeepsTheRowsWithoutLinks() {
        show(ZonedDateTime.parse("2026-10-10T11:10:00+02:00[Europe/Amsterdam]").toInstant().toEpochMilli(), "locked")
        compose.onNodeWithText("Session effort: 9").assertIsDisplayed()
        compose.onNodeWithText("Pain: Elbow, outer side (moderate)").assertIsDisplayed()
        compose.onAllNodesWithText("Note", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Change").assertCountEquals(0)
        compose.onAllNodesWithText("Add pain").assertCountEquals(0)
        compose.onAllNodesWithText("Editable until", substring = true).assertCountEquals(0)
    }
}
