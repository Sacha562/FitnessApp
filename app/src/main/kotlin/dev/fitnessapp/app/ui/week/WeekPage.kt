package dev.fitnessapp.app.ui.week

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fitnessapp.algo.core.calendar.CalendarClassification
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.planning.PlanningFacts
import dev.fitnessapp.app.planning.WeekChallenge
import dev.fitnessapp.app.planning.WeekPlans
import dev.fitnessapp.app.planning.WeekVolume
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.uidet.LoggedSession
import dev.fitnessapp.app.uidet.UiWeekGrid
import dev.fitnessapp.app.uidet.UiWeekRows
import dev.fitnessapp.app.uidet.WeekGrid
import dev.fitnessapp.app.uidet.WeekRow
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One page of the Plan and Goals tabs (UX 10, D82, D114): one ISO week's rows, header facts and volume. */
internal class WeekPage(
    val monday: LocalDate,
    val offset: Int,
    /** This week's plan, or next week's provisional one; null for a past week and the weeks after next. */
    val run: WeekPlans.Run?,
    /** This week's volume, or a past week's; null for the weeks ahead. */
    val volume: WeekVolume?,
    /** The day sheet's rows (D82 (2)). */
    val rows: List<WeekRow>,
    /** The calendar grid (D83); null for the weeks after next. */
    val grid: WeekGrid?,
    val challenge: WeekChallenge?,
    val degraded: Boolean,
)

/**
 * Loads the page [offset] ISO weeks from this one (UX 10.7): this and next week from the week planner, a past week from
 * its logged sessions and volume (read-only), a later week as the challenge's block structure only.
 */
internal suspend fun load(c: AppContainer, offset: Int): WeekPage = withContext(Dispatchers.Default) {
    val now = c.clock.now()
    val zone = PlanningFacts.ZONE
    val today = Week.localDate(now, zone)
    val monday = Week.isoWeekStart(today).plusWeeks(offset.toLong())
    val snap = c.calendar.snapshot.value
    val degraded = snap.status != CalendarClassification.OK
    val canPlan = !degraded && snap.writeOff == null && snap.target != null
    val labels = c.catalogModel.taxonomy.activeGroups.associate { it.id to it.labelEn }
    fun rows(plan: WeekPlan?, done: List<LoggedSession>) =
        UiWeekRows.rows(plan, monday, snap.anchors, snap.instances, snap.links, snap.prompts, done.map { it.date to it.label }, today, now, zone, canPlan) { g -> labels[g] ?: g }
    fun grid(plan: WeekPlan?, done: List<LoggedSession>) = UiWeekGrid.grid(plan, monday, snap.anchors, snap.instances, done, today, now, zone)
    when {
        offset == 0 -> {
            val run = WeekPlans.thisWeek(c, now)
            val shown = UiWeekRows.withMoved(run.plan, c.settings.movedSuggestions(run.plan.isoWeek), zone)
            // The bars count the plan's general sessions as shown (moved suggestions included) beside the challenge's (D113).
            val volume = run.volume.withPlan(shown, c.params)
            WeekPage(monday, 0, run, volume, rows(shown, emptyList()), grid(shown, emptyList()), volume.challenge, degraded)
        }
        offset == 1 -> {
            val run = WeekPlans.nextWeek(c, WeekPlans.thisWeek(c, now), now)
            val shown = UiWeekRows.withMoved(run.plan, c.settings.movedSuggestions(run.plan.isoWeek), zone)
            WeekPage(monday, 1, run, null, rows(shown, emptyList()), grid(shown, emptyList()), runCatching { WeekPlans.blockAt(c, monday) }.getOrNull(), degraded)
        }
        offset < 0 -> {
            val volume = runCatching { WeekVolume.compute(c, Week.atLocal(monday.plusDays(6), LocalTime.of(23, 59), zone)) }.getOrNull()
            val done = c.db.sessions().finishedInIsoWeek(Week.isoWeek(monday))
                .filter { it.habit_id == null && it.deleted_at == null && it.status == "finished" }
                .map { s -> LoggedSession(LocalDate.parse(s.local_date), sessionLabel(s), s.started_at?.let(Instant::ofEpochMilli), s.finished_at?.let(Instant::ofEpochMilli)) }
            WeekPage(monday, offset, null, volume, rows(null, done), grid(null, done), volume?.challenge, degraded)
        }
        else -> WeekPage(monday, offset, null, null, emptyList(), null, runCatching { WeekPlans.blockAt(c, monday) }.getOrNull(), degraded)
    }
}

private fun sessionLabel(s: WorkoutSessionEntity): String = when {
    s.challenge_slot == "TEST" -> "Test day"
    s.challenge_slot != null -> "Session ${s.challenge_slot}"
    s.families.isNotEmpty() -> s.families.joinToString(" + ") { it.lowercase() }.replaceFirstChar { it.uppercase() }
    else -> "Gym session"
}

/** The week title of the Plan and Goals tabs (UX 10.7): week number, dates, and the arrows; [canNext] limits the forward one. */
@Composable
internal fun WeekHeader(c: AppContainer, offset: Int, onPrev: () -> Unit, onNext: () -> Unit, canNext: Boolean = true) {
    val monday = Week.isoWeekStart(Week.localDate(c.clock.now(), PlanningFacts.ZONE)).plusWeeks(offset.toLong())
    val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Week " + monday.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR) + when {
                    offset == 0 -> " · this week"
                    offset == 1 -> " · next week"
                    offset < 0 -> " · past"
                    else -> ""
                },
                style = MaterialTheme.typography.titleSmall, color = Fit.Muted,
            )
            Text(fmt.format(monday) + " – " + fmt.format(monday.plusDays(6)), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        }
        IconButton(onClick = onPrev, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous week") }
        IconButton(onClick = onNext, enabled = canNext, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next week") }
    }
}
