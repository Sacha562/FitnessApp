package dev.fitnessapp.app.ui.week

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.fitnessapp.algo.core.calendar.Anchor
import dev.fitnessapp.algo.core.calendar.AnchorKind
import dev.fitnessapp.algo.core.calendar.CalendarInstance
import dev.fitnessapp.algo.core.calendar.MatchMethod
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.planning.weekplan.Band
import dev.fitnessapp.algo.planning.weekplan.FixedItem
import dev.fitnessapp.algo.planning.weekplan.PlanDay
import dev.fitnessapp.algo.planning.weekplan.PlanItem
import dev.fitnessapp.algo.planning.weekplan.ReasonCode
import dev.fitnessapp.algo.planning.weekplan.Visits
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.algo.planning.weekplan.WeeklyPlanner
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.ui.theme.FitnessTheme
import dev.fitnessapp.app.uidet.UiWeekGrid
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** D83's Week grid (7 days, D84) on a phone-wide screen, on a week shaped like W45 (challenge C, A, B and busy evenings). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WeekGridScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.of("Europe/Amsterdam")
    private val monday = LocalDate.parse("2026-11-02")
    private val today = LocalDate.parse("2026-11-03")
    private val now = Week.atLocal(today, LocalTime.of(18, 30), zone)

    private fun at(d: String, t: String) = Week.atLocal(LocalDate.parse(d), LocalTime.parse(t), zone)

    private fun item(kind: String, d: String, from: String, to: String, label: String, slot: String? = null, type: String? = null, est: Double = 90.0) =
        PlanItem("$kind:$d", kind, slot, type, label, at(d, from), at(d, to), est, writePolicy = "never", source = "weekplan")

    private val plan = WeekPlan(
        "2026-W45", "id", false,
        listOf(
            PlanDay(LocalDate.parse("2026-11-02"), "past", emptyList(), ReasonCode("PAST_DAY")),
            PlanDay(LocalDate.parse("2026-11-03"), "free", emptyList(), ReasonCode("NO_SLOT")),
            PlanDay(
                LocalDate.parse("2026-11-04"), "fixed",
                listOf(item(FixedItem.KIND_CHALLENGE, "2026-11-04", "19:45", "21:15", "Session C", slot = "C"), item(WeeklyPlanner.KIND_ADDON, "2026-11-04", "20:45", "21:00", "Push add-on", type = "PUSH", est = 15.0)),
                null,
            ),
            PlanDay(LocalDate.parse("2026-11-05"), "free", listOf(item(WeeklyPlanner.KIND_RECOMMENDED, "2026-11-05", "19:45", "20:40", "Legs + push + rear delts", type = "FULL", est = 49.6)), null),
            PlanDay(LocalDate.parse("2026-11-06"), "fixed", listOf(item(FixedItem.KIND_CHALLENGE, "2026-11-06", "19:45", "21:15", "Session A", slot = "A")), null),
            PlanDay(LocalDate.parse("2026-11-07"), "blocked", emptyList(), ReasonCode("REST_BEFORE", sortedMapOf("slot" to "B"))),
            PlanDay(LocalDate.parse("2026-11-08"), "fixed", listOf(item(FixedItem.KIND_CHALLENGE, "2026-11-08", "11:00", "12:30", "Session B", slot = "B")), null),
        ),
        Visits(1, 3, 1, 4), 4, emptyMap(), 4.0, emptyMap(), Band(4.0, 0.05, 1, "only"), emptyList(), emptyList(), 1, 1, false, emptyList(),
    )

    private val busy = listOf(
        Triple("2026-11-02", "14:30" to "15:30", "Atos stagiairs"),
        Triple("2026-11-02", "17:00" to "18:00", "Papa ophalen"),
        Triple("2026-11-02", "19:00" to "20:00", "Sgaak - jong & oud"),
        Triple("2026-11-02", "20:00" to "22:30", "Sgaak · interne competitie"),
        Triple("2026-11-03", "10:00" to "11:00", "Kapper"),
        Triple("2026-11-03", "17:30" to "21:00", "Global AI Night Utrecht"),
        Triple("2026-11-06", "14:45" to "17:30", "Helpen bij sgoolsgaak"),
        Triple("2026-11-06", "23:00" to "23:59", "Rosa komt"),
    )
    private val anchors = busy.mapIndexed { i, (d, t, _) ->
        Anchor(
            "b$i", "b$i", i.toLong(), 1, at(d, t.first), at(d, t.second), LocalDate.parse(d), LocalTime.parse(t.first), LocalTime.parse(t.second),
            AnchorKind.BUSY, method = MatchMethod.SCORE, classifierVersion = "1.0.0",
        )
    }
    private val instances = busy.mapIndexed { i, (d, t, title) ->
        "b$i" to CalendarInstance("b$i", "b$i", i.toLong(), 1, "primary", true, at(d, t.first), at(d, t.second), false, title, null, null, "confirmed", false, false)
    }.toMap()

    private fun shot(days: Int) {
        val grid = UiWeekGrid.grid(plan, monday, anchors, instances, emptyList(), today, now, zone)
        compose.setContent {
            FitnessTheme {
                Surface(color = Fit.Ground) {
                    Column(Modifier.padding(16.dp).width(379.dp)) {
                        WeekGridView(grid, nowMin = 18 * 60 + 30, onOpenDay = {}, onTapSlot = { _, _ -> }, onMove = { _, _, _ -> })
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/week_grid_$days.png")
    }

    @Test
    fun week() = shot(7)
}
