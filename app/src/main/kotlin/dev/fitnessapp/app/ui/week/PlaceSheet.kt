package dev.fitnessapp.app.ui.week

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fitnessapp.algo.core.calendar.Marker
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.planning.allocator.Allocation
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.planning.weekplan.WeeklyPlanner
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.planning.PlanItContent
import dev.fitnessapp.app.planning.PlanningFacts
import dev.fitnessapp.app.planning.SessionBuild
import dev.fitnessapp.app.planning.WeekPlans
import dev.fitnessapp.app.ui.common.Banner
import dev.fitnessapp.app.ui.common.BannerKind
import dev.fitnessapp.app.ui.common.ChipButton
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.LoadingDumbbell
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.uidet.UiTrim
import dev.fitnessapp.app.uidet.WeekRow
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The length chips of a placed session (UX 9.4's time chips without the calendar one). */
private val LENGTHS = listOf(45, 60, 75, 90)

private fun nearestLength(min: Double): Int = LENGTHS.minByOrNull { kotlin.math.abs(it - min) } ?: LENGTHS[2]

/**
 * A planned general session the day sheet's "Change" opens (D92): the app event's [marker], its [start] (fixed), its
 * planned [type] and its budget (the event's length minus the changeover).
 */
internal class Changing(val marker: Marker, val start: Instant, val type: SessionType?, val budgetMin: Double)

/**
 * "New gym session" for a tapped spot of the Week grid (D84 (1)): the day and start (±30 min), the eight types ranked by
 * what the allocator gives at that time (the ones it cannot fill disabled), the length, the exercise preview, and a
 * banner when the day is a challenge rest day or already has a gym session. [onAdd] writes it with the chosen type and
 * budget. With [change] it is "Change gym session" (D92): the start fixed, the session's type and length preselected, no
 * banner, and "Save" once the type or the length differs.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlaceSheet(
    c: AppContainer,
    run: WeekPlans.Run,
    row: WeekRow?,
    at: Instant,
    onDismiss: () -> Unit,
    change: Changing? = null,
    onAdd: (SessionType, Double) -> Unit,
) {
    val zone = PlanningFacts.ZONE
    var start by remember { mutableStateOf(at) }
    val initialBudget = remember { change?.let { nearestLength(it.budgetMin) } ?: LENGTHS[2] }
    var budget by remember { mutableIntStateOf(initialBudget) }
    var ranked by remember { mutableStateOf<List<Pair<SessionType, Allocation>>?>(null) }
    var type by remember { mutableStateOf<SessionType?>(null) }
    var preview by remember { mutableStateOf<PlanItContent.Content?>(null) }
    var building by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (change != null) return@LaunchedEffect
        budget = nearestLength((c.profiles.get()?.default_session_min ?: LENGTHS[2]).toDouble())
    }
    LaunchedEffect(start, budget) {
        val r = runCatching { PlanItContent.rank(c, run.plan, start, budget.toDouble()) }.getOrNull().orEmpty()
        ranked = r
        val usable = r.filter { UiTrim.usable(it.second) }.map { it.first }
        // The plan's suggestion for that day first, then the best the allocator finds at that time.
        val suggested = row?.item?.takeIf { it.kind == WeeklyPlanner.KIND_RECOMMENDED }?.type?.let { runCatching { SessionType.of(it) }.getOrNull() }
        type = type?.takeIf { it in usable } ?: change?.type?.takeIf { it in usable } ?: suggested?.takeIf { it in usable } ?: usable.firstOrNull()
    }
    LaunchedEffect(type, start, budget) {
        val t = type ?: return@LaunchedEffect
        building = true
        preview = runCatching { PlanItContent.build(c, run.plan, PlanItContent.placed(t, start, budget.toDouble()), budget.toDouble()) }.getOrNull()
        building = false
    }
    val local = Week.localTime(start, zone)
    val date = Week.localDate(start, zone)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (change != null) "Change gym session" else "New gym session", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)) + " · " + "%02d:%02d".format(Locale.ROOT, local.hour, local.minute),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                // D92: a change keeps the start; moving stays a drag in the grid.
                if (change == null) {
                    LinkButton("−30", { start = start.minusSeconds(1800).takeIf { it.isAfter(c.clock.now()) && Week.localDate(it, zone) == date } ?: start })
                    LinkButton("+30", { start = start.plusSeconds(1800).takeIf { Week.localDate(it, zone) == date } ?: start })
                }
            }
            if (change == null) when (row?.kind) {
                "blocked" -> Banner(
                    "Rest day" + (row.detail?.let { ": $it" } ?: "") + ". Your challenge asks for no gym that day; the session would still be planned.",
                    BannerKind.SAFETY,
                )
                "challenge", "test", "planned", "done" -> Banner("This day already has ${row.title.removeSuffix(" (done)")}.", BannerKind.ADVISORY)
                else -> Unit
            }
            val r = ranked
            if (r == null) {
                LoadingDumbbell(listOf("Weighing the options…"), height = 40.dp)
            } else {
                TileLabel("Train")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.forEach { (t, a) -> ChipButton(SessionBuild.label(t), selected = t == type, onClick = { type = t }, enabled = UiTrim.usable(a)) }
                }
                val blocked = r.filter { !UiTrim.usable(it.second) }.map { SessionBuild.label(it.first) }
                if (blocked.isNotEmpty()) {
                    Text("Not then: " + blocked.joinToString(", ") + " (covered by your challenge, protected or already done this week)", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                }
            }
            TileLabel("Length")
            // One line that scrolls sideways when the chips do not fit, instead of wrapping "90 min".
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LENGTHS.forEach { m -> ChipButton("$m min", selected = budget == m, onClick = { budget = m }) }
            }
            val pv = preview
            when {
                type == null && r != null -> Text("Nothing to train at that time: everything is covered or rests.", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                building && pv == null -> LoadingDumbbell(listOf("Building the session…"), height = 40.dp)
                pv != null -> {
                    Text(pv.label + " · ~" + UiTrim.minutes(pv.result.estMin) + " min", style = MaterialTheme.typography.titleMedium)
                    pv.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                else -> Unit
            }
            val changed = change == null || type != change.type || budget != initialBudget
            PrimaryButton(
                if (change != null) "Save" else "Add to calendar", onClick = { type?.let { onAdd(it, budget.toDouble()) } }, enabled = type != null && pv != null && changed,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}
