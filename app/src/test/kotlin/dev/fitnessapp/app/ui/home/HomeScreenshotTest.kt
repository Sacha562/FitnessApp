package dev.fitnessapp.app.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.fitnessapp.app.ui.theme.FitnessTheme
import dev.fitnessapp.data.remote.AccessCheck
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M0 skeleton screenshots (ANDROID.md 3.7). SDK pinned to 34 until a newer one is proven to render on
 * this Windows machine. `gradlew :app:recordRoborazziDebug` writes the PNGs; verify compares baselines.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val build = BuildInfo("0.0.1+abcdef0", "1.0.0")

    private fun shot(name: String, state: HomeState, dark: Boolean = false) {
        compose.setContent {
            FitnessTheme(darkTheme = dark) { HomeScreen(state, build, { _, _ -> }, {}, {}) }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/home_$name.png")
    }

    @Test
    fun signedOut() = shot("signed_out", HomeState(phase = Phase.SIGNED_OUT))

    @Test
    fun connectedLight() = shot(
        "connected_light",
        HomeState(phase = Phase.SIGNED_IN, access = AccessCheck.Result.Allowed,
            roundTrip = RoundTripState.Ok("2026-10-08T12:00:00.000+00:00")),
    )

    @Test
    fun connectedDark() = shot(
        "connected_dark",
        HomeState(phase = Phase.SIGNED_IN, access = AccessCheck.Result.Allowed,
            roundTrip = RoundTripState.Ok("2026-10-08T12:00:00.000+00:00")),
        dark = true,
    )

    @Test
    fun schemaNotExposed() = shot(
        "schema_not_exposed",
        HomeState(phase = Phase.SIGNED_IN, access = AccessCheck.Result.SchemaNotExposed),
    )
}
