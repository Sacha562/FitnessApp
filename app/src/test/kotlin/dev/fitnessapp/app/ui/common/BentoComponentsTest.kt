package dev.fitnessapp.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.ui.theme.FitType
import dev.fitnessapp.app.ui.theme.FitnessTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** D31 Bento components: the set card, the rest ring and the navigation tile render with the bundled Manrope. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BentoComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            FitnessTheme {
                Surface(color = Fit.Ground) {
                    Column(Modifier.padding(12.dp).width(387.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/bento_$name.png")
    }

    @Test
    fun setCard() {
        show("set_card") {
            Tile(radius = 28.dp, padding = PaddingValues(16.dp), spacing = 10.dp) {
                Text("Weighted pull-up", style = MaterialTheme.typography.titleLarge)
                Pill("Set 2 of 2")
                Text("Last BW + 22.5 × 4 4 4 3 · Thu 1 Oct", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                StepperRow("BW + 22.5 kg", "20 + 2.5 on belt", {}, {})
                StepperRow("4 reps", null, {}, {})
                Text("Reps left in the tank? Tap to log.", style = MaterialTheme.typography.labelMedium, color = Fit.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..5).forEach { r -> LogChip(if (r == 5) "5+" else r.toString(), {}, Modifier.weight(1f)) }
                }
            }
            PrimaryButton("Start session", {}, Modifier.fillMaxWidth())
        }
        compose.onNodeWithText("Weighted pull-up").assertIsDisplayed()
        compose.onNodeWithText("5+").assertIsDisplayed()
    }

    @Test
    fun restAndNavigation() {
        show("rest_nav") {
            Tile(radius = 28.dp, spacing = 12.dp) {
                Pill("Rest")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RingTimer(0.77f, Modifier.size(196.dp)) { Text("2:18", style = FitType.RestDigits, color = Fit.Ink) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TonalButton("−15 s", {}, Modifier.weight(1f), height = 64.dp)
                    TonalButton("Skip", {}, Modifier.weight(1f), height = 64.dp)
                    TonalButton("+15 s", {}, Modifier.weight(1f), height = 64.dp)
                }
            }
            Banner("Exact alarms are off: the rest alarm may be late", BannerKind.ADVISORY)
            Banner("This app does not give medical advice.", BannerKind.SAFETY)
            ChoiceChips(listOf("Mild", "Moderate", "Sharp"), "Moderate", { it }) {}
            FitNavBar(
                listOf(NavItem("Today", Icons.Rounded.FitnessCenter), NavItem("Exercises", Icons.AutoMirrored.Rounded.FormatListBulleted), NavItem("You", Icons.Rounded.Person)),
                selected = 0, onSelect = {},
            )
        }
        compose.onNodeWithText("2:18").assertIsDisplayed()
        compose.onNodeWithText("Moderate").assertIsSelected()
    }
}
