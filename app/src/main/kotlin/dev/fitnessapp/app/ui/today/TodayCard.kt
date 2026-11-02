package dev.fitnessapp.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.planning.today.TodayOption
import dev.fitnessapp.algo.planning.today.TodayOptions
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.planning.PlanningFacts
import dev.fitnessapp.app.planning.WeekPlans
import dev.fitnessapp.app.uidet.CardState
import dev.fitnessapp.app.uidet.TimeChip
import dev.fitnessapp.app.uidet.TodayCard
import dev.fitnessapp.app.uidet.UiTodayCard
import dev.fitnessapp.app.uidet.UiTrim
import dev.fitnessapp.app.ui.common.ChipButton
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.Pill
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.data.telemetry.EventDraft
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** What Today shows from the planner (D70): this week's and next week's plans, today's options, the card and its chips. */
class TodayPlan(
    val week: WeekPlans.Run,
    val next: WeekPlan?,
    val options: TodayOptions,
    val card: TodayCard,
    val chips: List<TimeChip>,
    val chip: TimeChip?,
)

object TodayPlans {
    /** Runs the planner and `today` for [chipMin] (null: the calendar budget or the default length) and maps the card. */
    suspend fun load(c: AppContainer, chipMin: Int?, activeSession: Boolean, finishedToday: Boolean, setupNeeded: Boolean): TodayPlan {
        val now = c.clock.now()
        val week = WeekPlans.thisWeek(c, now)
        val options = WeekPlans.today(c, week, chipMin?.toDouble())
        val next = runCatching { WeekPlans.nextWeek(c, week, now).plan }.getOrNull()
        val params = c.params.fork()
        val labels = c.catalogModel.taxonomy.activeGroups.associate { it.id to (it.labelEn ?: it.id) }
        val card = UiTodayCard.card(options, activeSession, finishedToday, false, setupNeeded, now, PlanningFacts.ZONE, params) { g ->
            labels[g] ?: g.replace('_', ' ').replaceFirstChar(Char::uppercase)
        }
        val calendarMin = options.budgetMin.toInt().takeIf { options.budgetSource == "calendar" && options.planned?.kind == "general" }
        val chips = UiTrim.chips(calendarMin, listOf(45, 60, 75), params.int("today.max_budget_min"))
        val chip = chips.firstOrNull { it.minutes == chipMin } ?: chips.firstOrNull { it.source == "calendar" } ?: chips.firstOrNull { it.minutes == options.budgetMin.toInt() }
        runCatching { c.planSnapshots.week(week.plan) }
        runCatching { c.planSnapshots.today(options) }
        return TodayPlan(week, next, options, card, chips, chip)
    }

    /** `today_card_shown` (UX 20.4) with the registry payload. */
    suspend fun shown(c: AppContainer, p: TodayPlan) {
        c.sessionRepository.event(
            EventDraft(
                type = "today_card_shown", entityType = "none", screen = "today", moduleId = "ui.todaycard", moduleVersion = ModuleRegistry.ref("ui.todaycard").version.toString(),
                payload = mapOf(
                    "state" to JsonPrimitive(p.card.state.name), "verdict" to JsonPrimitive(p.options.verdict),
                    "variant" to (p.card.variant?.let { JsonPrimitive(it) } ?: JsonPrimitive("")),
                    "options" to JsonArray(p.options.options.map { JsonPrimitive(it.optionId) }),
                    "chips" to JsonArray(p.card.chips.map { JsonPrimitive(it.code) }),
                    "primary_option_id" to JsonPrimitive(p.options.primaryOptionId ?: ""),
                    "unknown_codes" to JsonArray(p.card.unknownCodes.map { JsonPrimitive(it) }),
                    "inputs_hash" to JsonPrimitive(p.options.weekPlanId),
                ),
            ),
        )
    }

    /** `today_action {action, rank}` (UX 20.4). */
    suspend fun action(c: AppContainer, action: String, rank: Int) {
        c.sessionRepository.event(
            EventDraft(type = "today_action", entityType = "none", screen = "today", payload = mapOf("action" to JsonPrimitive(action), "rank" to JsonPrimitive(rank), "latency_ms" to JsonPrimitive(0))),
        )
    }
}

/**
 * The Today card (UX 9.3, D70) for a day without a challenge session, or the add-on choice of today's challenge
 * session. The challenge session itself, its Start and the habits stay on the challenge tile below the card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayCardTile(
    p: TodayPlan,
    picked: String?,
    onPick: (TodayOption) -> Unit,
    onChip: (TimeChip) -> Unit,
    onOtherOptions: () -> Unit,
    onLogAnyway: () -> Unit,
    onRestToday: () -> Unit,
) {
    val card = p.card
    val o = p.options
    val zone = PlanningFacts.ZONE
    fun time(i: Instant) = Week.localTime(i, zone).let { t -> (if (t.hour < 10) "0" else "") + t.hour + ":" + (if (t.minute < 10) "0" else "") + t.minute }
    when (card.state) {
        CardState.IN_PROGRESS, CardState.SETUP_NEEDED -> return
        CardState.PLANNED -> if (card.variant != "GENERAL") {
            // A challenge session today: only its add-on choice lives here (C34, D62, D70).
            val choices = o.options.filter { it.addon != null || o.options.size > 1 }
            if (choices.size < 2 || card.variant == "TEST") return
            Tile(spacing = 4.dp) {
                Text("After the session", style = MaterialTheme.typography.titleMedium)
                OptionRows(choices, picked ?: o.primaryOptionId) { onPick(it) }
                ChipRow(card)
                LinkButton("Other options", onOtherOptions)
            }
            return
        }
        else -> Unit
    }
    Tile(spacing = 6.dp) {
        val title = when (card.state) {
            CardState.OPTIONAL -> "No gym planned. If you go today:"
            CardState.REST -> "Rest today"
            CardState.PLANNED -> "Gym today ${o.planned?.start?.let(::time) ?: ""} · ${card.primary?.label ?: "general session"}"
            CardState.PLANNED_BUT_REST -> if (card.variant == "HARD") "Planned ${o.planned?.start?.let(::time) ?: ""}, but today is a rest day"
            else "Planned ${o.planned?.start?.let(::time) ?: ""}, but rest is advised"
            CardState.DONE -> "Done today"
            CardState.ENGINE_ERROR -> "Couldn't work out a recommendation"
            else -> ""
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        ChipRow(card)
        when (card.state) {
            CardState.OPTIONAL -> {
                val rows = listOfNotNull(card.primary) + if (card.alternativesInline) card.alternatives else emptyList()
                OptionRows(rows, picked ?: o.primaryOptionId) { onPick(it) }
            }
            CardState.DONE -> nextLine(p.week.plan, time = ::time)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fit.Muted) }
            else -> Unit
        }
        card.notToday.forEach { Text("Not today: $it", style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
        if (card.state == CardState.OPTIONAL || card.state == CardState.PLANNED || (card.state == CardState.PLANNED_BUT_REST && card.variant == "SOFT")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.chips.forEach { ch -> ChipButton(ch.label, selected = ch == p.chip, onClick = { onChip(ch) }) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                card.state == CardState.REST && card.variant == "SOFT" -> LinkButton("Train anyway", onOtherOptions)
                card.state == CardState.REST || (card.state == CardState.PLANNED_BUT_REST && card.variant == "HARD") -> LinkButton("Log a session anyway", onLogAnyway)
                card.state == CardState.PLANNED_BUT_REST -> LinkButton("Rest today", onRestToday)
                card.state == CardState.OPTIONAL || card.state == CardState.PLANNED -> LinkButton("Other options", onOtherOptions)
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(card: TodayCard) {
    if (card.chips.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        card.chips.forEach { ch -> Pill(ch.text, content = if (ch.muted) Fit.Muted else Fit.Ink) }
    }
}

/** Radio rows: the Start target is the selected one (UX 9.3 OPTIONAL close; the add-on choice of a challenge day). */
@Composable
private fun OptionRows(rows: List<TodayOption>, selected: String?, onPick: (TodayOption) -> Unit) {
    Column {
        rows.forEach { r ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = r.optionId == selected, role = Role.RadioButton, onClick = { onPick(r) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = r.optionId == selected, onClick = null)
                Text(r.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (r.sessionType != null) Text("~${UiTrim.minutes(r.estMinutes)} min", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
            }
        }
    }
}

/** "Next: Sun 11 Oct, Test day 13:00" (UX 9.3 DONE) from the week plan. */
private fun nextLine(plan: WeekPlan, time: (Instant) -> String): String? {
    val now = Instant.now()
    val item = plan.days.flatMap { it.items }.filter { it.start.isAfter(now) && it.kind != "done" && it.kind != "done_general" }.minByOrNull { it.start } ?: return null
    val d = Week.localDate(item.start, PlanningFacts.ZONE)
    val day = d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH))
    return "Next: $day, ${item.label} ${time(item.start)}"
}
