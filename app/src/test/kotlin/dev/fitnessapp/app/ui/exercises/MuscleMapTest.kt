package dev.fitnessapp.app.ui.exercises

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.fitnessapp.app.ui.theme.FitnessTheme
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** D26 muscle map: the generated shapes use only active group ids, every path parses, and the map renders. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MuscleMapTest {
    @get:Rule
    val compose = createComposeRule()

    private val activeGroups: Set<String> by lazy {
        val text = requireNotNull(javaClass.getResource("/fitness/reference/muscle_groups.json")).readText(Charsets.UTF_8)
        Json.parseToJsonElement(text).jsonObject.getValue("rows").jsonArray.map { it.jsonObject }
            .filter { it.getValue("active").jsonPrimitive.boolean }.map { it.getValue("id").jsonPrimitive.content }.toSet()
    }

    @Test
    fun shapesUseActiveGroupsAndParse() {
        val groups = MuscleMapData.shapes.mapNotNull { it.group }.toSet()
        activeGroups.containsAll(groups) shouldBe true
        // Every active group is drawn somewhere (neck is inactive, C44).
        groups shouldContainAll activeGroups
        MuscleMapData.shapes.forEach { PathParser().parsePathString(it.pathData).toNodes().isNotEmpty() shouldBe true }
        // Base silhouettes are drawn first in each view.
        for (v in MapView.entries) MuscleMapData.shapes.first { it.view == v }.group shouldBe null
    }

    @Test
    fun shadingLevels() {
        shadeAlpha(1.0) shouldBe 1f
        shadeAlpha(0.75) shouldBe 0.70f
        shadeAlpha(0.5) shouldBe 0.45f
        shadeAlpha(0.25) shouldBe 0.20f
        shadeAlpha(0.0) shouldBe 0f
    }

    private fun shot(name: String, coefficients: Map<String, Double>, dark: Boolean) {
        compose.setContent {
            FitnessTheme(darkTheme = dark) {
                Surface { MuscleMap(coefficients, Modifier.padding(16.dp).width(360.dp)) }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/musclemap_$name.png")
    }

    @Test
    fun legPressLight() = shot("legpress_light", mapOf("quads" to 1.0, "glutes" to 0.5, "adductors" to 0.5), dark = false)

    @Test
    fun pullUpDark() = shot(
        "pullup_dark", mapOf("lats" to 1.0, "biceps" to 0.75, "upper_back" to 0.5, "rear_delts" to 0.5, "forearms" to 0.5, "abs" to 0.25), dark = true,
    )
}
