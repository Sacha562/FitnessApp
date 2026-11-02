package dev.fitnessapp.app.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.theme.FitnessTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UX 6.1 long press: holding a stepper button repeats the step, x5 after 8 repeats; the release adds no tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HoldRepeatTest {
    @get:Rule
    val compose = createComposeRule()

    private val params = ParamView.fromParamsFile(
        requireNotNull(javaClass.getResource("/fitness/params/default-params-${AppContainer.PARAMS_VERSION}.json")).readText(Charsets.UTF_8),
    )
    private val delayMs = params.int("ui.prefill.hold_delay_ms").toLong()
    private val repeatMs = params.int("ui.prefill.repeat_ms").toLong()
    private val after = params.int("ui.prefill.accel_after")
    private val factor = params.int("ui.prefill.accel_factor")

    private var plus = 0
    private var rest = 0

    private fun show() = compose.setContent {
        CompositionLocalProvider(LocalParams provides params) {
            FitnessTheme {
                StepperRow("$plus", null, onMinus = {}, onPlus = { plus++ })
                TonalButton("+15 s", { rest += 15 }, onRepeat = { rest += 60 })
            }
        }
    }

    /** Presses [node] for [ms] on the test clock, then lifts. */
    private fun hold(node: androidx.compose.ui.test.SemanticsNodeInteraction, ms: Long) {
        compose.mainClock.autoAdvance = false
        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(ms)
        node.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test
    fun tapStepsOnce() {
        show()
        compose.onNodeWithContentDescription("Increase").performClick()
        compose.waitForIdle()
        assertEquals(1, plus)
    }

    @Test
    fun holdRepeatsAndAccelerates() {
        show()
        val repeats = after + 2
        // The first repeat at the hold delay, then one per repeat interval; lift halfway through the next interval.
        hold(compose.onNodeWithContentDescription("Increase"), delayMs + (repeats - 1) * repeatMs + repeatMs / 2)
        assertEquals(after + 2 * factor, plus)
    }

    @Test
    fun shortPressIsATap() {
        show()
        hold(compose.onNodeWithContentDescription("Increase"), delayMs / 2)
        assertEquals(1, plus)
    }

    @Test
    fun tonalButtonRepeatsWithItsOwnStep() {
        show()
        hold(compose.onNodeWithText("+15 s"), delayMs + repeatMs + repeatMs / 2)
        assertEquals(120, rest)
    }
}
