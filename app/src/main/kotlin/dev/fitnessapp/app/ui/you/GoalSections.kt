package dev.fitnessapp.app.ui.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.targets.GroupGoal
import dev.fitnessapp.algo.targets.Targets
import dev.fitnessapp.algo.targets.TargetsInput
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.planning.PlanningFacts
import dev.fitnessapp.app.planning.WeekChallenge
import dev.fitnessapp.app.planning.WeekVolume
import dev.fitnessapp.app.ui.common.ChipButton
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.FitTextField
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.Segmented
import dev.fitnessapp.app.ui.common.StepperRow
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.common.TonalButton
import dev.fitnessapp.app.uidet.UiVolumeBars
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.data.db.entity.ProfileEntity
import dev.fitnessapp.data.repo.Setting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalTime

/** The presets of UX 8.9 in the order shown; their goals come from `goal-presets` (ALGORITHMS_PLANNING 5.6). */
private val PRESETS = listOf("balanced" to "Balanced", "legs_only" to "Legs only", "no_legs" to "No legs", "pull_focus" to "Pull focus, push + legs maintained")

private val GOALS = listOf("improve", "maintain", "skip")

/**
 * You > Preferences, Muscle goals (UX 8.9 and 15.4, D6, D55): a preset in one tap, then Improve / Maintain / Skip and Focus
 * per group, with the weekly target `targets` gives each. Groups the active challenge owns show "Covered by challenge"
 * (contract obligation 1). Until the goals are saved the editor shows the Balanced preset as a preview.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MuscleGoalsTile(c: AppContainer, profile: ProfileEntity?) {
    val scope = rememberCoroutineScope()
    val saved by remember { c.goals.observe() }.collectAsState(initial = null)
    var challenge by remember { mutableStateOf<WeekChallenge?>(null) }
    LaunchedEffect(Unit) { challenge = runCatching { withContext(Dispatchers.Default) { WeekVolume.challenge(c) } }.getOrNull() }
    val goals = saved?.takeIf { it.isNotEmpty() } ?: c.goalPresets.goals("balanced")
    val owned = challenge?.owns.orEmpty()
    val today = LocalDate.now(PlanningFacts.ZONE)
    val targets = remember(goals, owned, profile?.training_age, profile?.birth_year, challenge?.deload) {
        Targets.compute(
            TargetsInput(
                isoWeek = dev.fitnessapp.algo.core.clock.Week.isoWeek(today), goals = goals, trainingAge = profile?.training_age,
                age = profile?.birth_year?.let { today.year - it }, ownedGroups = owned, contractActive = challenge != null,
                challengeDeloadWeek = false, committed = emptyMap(), done = emptyMap(), trainedDates = emptyMap(), today = today,
            ),
            c.params,
        )
    }
    fun change(g: String, next: GroupGoal) = scope.launch { c.goals.set(g, next, goals) }

    Tile(spacing = 0.dp, padding = PaddingValues(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 6.dp)) {
        TileLabel("Muscle goals", Modifier.padding(start = 10.dp, bottom = 4.dp))
        Text(
            if (saved.isNullOrEmpty()) "Not saved yet. Pick a preset, or change any group: the plan then knows what to train."
            else "Improve gets more sets each week, Maintain keeps what you have with fewer, Skip leaves a group out.",
            style = MaterialTheme.typography.bodySmall, color = if (saved.isNullOrEmpty()) Fit.Advisory else Fit.Muted,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        FlowRow(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { (id, label) ->
                ChipButton(label, selected = !saved.isNullOrEmpty() && profile?.muscle_goal_preset == id, onClick = { scope.launch { c.goals.applyPreset(c.goalPresets, id) } })
            }
        }
        Groups.CANONICAL.forEach { g ->
            TileDivider(Modifier.padding(horizontal = 10.dp))
            val goal = goals[g] ?: GroupGoal("skip")
            val gt = targets.groups[g]
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(groupLabel(c, g), style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                g in owned -> "Covered by challenge"
                                gt == null || goal.goal == "skip" -> "Not trained"
                                goal.goal == "improve" -> "Improve · ${num(gt.t)} sets/week"
                                else -> "Maintain · ${num(gt.t)} sets/week, at least 1 hard set at normal load"
                            },
                            style = MaterialTheme.typography.bodySmall, color = Fit.Muted,
                        )
                    }
                    if (goal.goal == "improve") {
                        ChipButton("Focus", selected = goal.focus, role = Role.Checkbox, onClick = { change(g, goal.copy(focus = !goal.focus)) })
                    }
                }
                Segmented(GOALS, goal.goal, { it.replaceFirstChar(Char::uppercase) }, { next -> if (next != goal.goal) change(g, GroupGoal(next)) })
                if (g in owned && goal.goal == "skip") {
                    Text("Your challenge trains this group while it is active.", style = MaterialTheme.typography.bodySmall, color = Fit.Advisory)
                }
            }
        }
    }
}

/**
 * You > Preferences, Sessions (UX 8.11 and 15.4, D55): training experience and age for `targets`, the planner's
 * maximum visits, usual windows, default length and changeover (F11, C49), running priority (F6), supersets (F13) and
 * the opt-in RIR check sets (F14).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionsTile(c: AppContainer, profile: ProfileEntity?) {
    val scope = rememberCoroutineScope()
    var editAge by remember { mutableStateOf(false) }
    var editWindows by remember { mutableStateOf(false) }
    val p = profile
    fun <T> set(s: Setting<T>, v: T) = scope.launch { c.profiles.set(s, v) }
    Tile(spacing = 10.dp) {
        TileLabel("Sessions")
        Text("Training experience", style = MaterialTheme.typography.titleMedium)
        Text("Regular lifting. It sets the weekly sets of an improve group (10, 12 or 14).", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
        ChoiceChips(listOf("novice", "intermediate", "advanced"), p?.training_age, { AGE_LABELS.getValue(it) }) { set(Setting.TRAINING_AGE, it) }

        TileDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Age", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Optional. Only used to adjust maintenance volume if you are 60 or older." +
                        (p?.birth_year?.let { " Now ${LocalDate.now(PlanningFacts.ZONE).year - it}." } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = Fit.Muted,
                )
            }
            LinkButton(if (p?.birth_year == null) "Add" else "Edit", { editAge = true })
        }

        TileDivider()
        Text("Gym visits per week, at most", style = MaterialTheme.typography.titleMedium)
        val visits = p?.max_gym_visits_per_week ?: 4
        StepperRow("$visits visits", null, onMinus = { set(Setting.MAX_VISITS, (visits - 1).coerceIn(1, 7)) }, onPlus = { set(Setting.MAX_VISITS, (visits + 1).coerceIn(1, 7)) })

        Text("Session length without a calendar event", style = MaterialTheme.typography.titleMedium)
        val len = p?.default_session_min ?: 75
        StepperRow("$len min", null, onMinus = { set(Setting.DEFAULT_SESSION_MIN, (len - 5).coerceIn(20, 240)) }, onPlus = { set(Setting.DEFAULT_SESSION_MIN, (len + 5).coerceIn(20, 240)) })

        Text("Changeover", style = MaterialTheme.typography.titleMedium)
        Text("Subtracted from a gym event in your calendar: the event is training time, travel happens before it.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
        val change = p?.changeover_min ?: 5
        StepperRow("$change min", null, onMinus = { set(Setting.CHANGEOVER_MIN, (change - 1).coerceIn(0, 60)) }, onPlus = { set(Setting.CHANGEOVER_MIN, (change + 1).coerceIn(0, 60)) })

        TileDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Usual training times", style = MaterialTheme.typography.titleMedium)
                Text(windowsSummary(p?.availability), style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            }
            LinkButton("Edit", { editWindows = true })
        }

        TileDivider()
        Text("Running priority", style = MaterialTheme.typography.titleMedium)
        Text("How much the plan protects your runs: higher keeps hard leg work further from them.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
        ChoiceChips(listOf("low", "medium", "high"), p?.running_priority ?: "low", { it.replaceFirstChar(Char::uppercase) }) { set(Setting.RUNNING_PRIORITY, it) }

        TileDivider()
        SwitchRow("Supersets", "Only when a plan does not fit the time; otherwise off.", p?.allow_supersets == true) { set(Setting.SUPERSETS, it) }
        SwitchRow(
            "RIR check sets",
            "At most once a week, the last set of one machine, cable or isolation exercise: tap how many reps you think are left, then keep going to failure. It calibrates your reps-in-reserve.",
            p?.rir_calibration_opt_in == true,
        ) { on -> scope.launch { c.profiles.setRirCalibrationOptIn(on) } }
    }
    if (editAge) AgeDialog(p?.birth_year, onDismiss = { editAge = false }) { year ->
        editAge = false
        set(Setting.BIRTH_YEAR, year)
    }
    if (editWindows) WindowsSheet(p?.availability, onDismiss = { editWindows = false }) { json ->
        editWindows = false
        set(Setting.AVAILABILITY, json)
    }
}

@Composable
private fun SwitchRow(title: String, body: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun AgeDialog(birthYear: Int?, onDismiss: () -> Unit, onSave: (Int?) -> Unit) {
    val year = LocalDate.now(PlanningFacts.ZONE).year
    var text by remember { mutableStateOf(birthYear?.let { (year - it).toString() } ?: "") }
    val age = text.toIntOrNull()?.takeIf { it in 10..100 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Age") },
        text = {
            FitTextField(text, { text = it.filter(Char::isDigit).take(3) }, label = "Years", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        },
        confirmButton = { TextButton(onClick = { onSave(age?.let { year - it }) }, enabled = age != null) { Text("Save") } },
        dismissButton = {
            Row {
                if (birthYear != null) TextButton(onClick = { onSave(null) }) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private val DAYS = listOf("mon" to "Monday", "tue" to "Tuesday", "wed" to "Wednesday", "thu" to "Thursday", "fri" to "Friday", "sat" to "Saturday", "sun" to "Sunday")

/** UX 8.11 default windows: weekdays 19:45-21:15, weekends 09:00-16:00 (planning lens facts, editable). */
private val DEFAULT_WINDOWS = DAYS.associate { (d, _) -> d to if (d == "sat" || d == "sun") ("09:00" to "16:00") else ("19:45" to "21:15") }

/** `profiles.availability` (DATA_MODEL 9.3.1): day -> [[from, to]]; `{}` means the gym's opening hours. One window a day here. */
private fun parseWindows(json: String?): Map<String, Pair<String, String>> {
    val o = runCatching { Json.parseToJsonElement(json ?: "{}").jsonObject }.getOrNull() ?: return emptyMap()
    return o.mapNotNull { (d, v) ->
        val w = runCatching { v.jsonArray.first().jsonArray }.getOrNull() ?: return@mapNotNull null
        d to (w[0].jsonPrimitive.content to w[1].jsonPrimitive.content)
    }.toMap()
}

private fun windowsJson(w: Map<String, Pair<String, String>>): String = JsonObject(
    DAYS.mapNotNull { (d, _) -> w[d]?.let { (a, b) -> d to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(a), JsonPrimitive(b))))) } }.toMap(),
).toString()

private fun windowsSummary(json: String?): String {
    val w = parseWindows(json)
    if (w.isEmpty()) return "Any time the gym is open"
    return DAYS.filter { it.first in w }.joinToString(" · ") { (d, _) -> d.replaceFirstChar(Char::uppercase) + " " + w.getValue(d).let { "${it.first}-${it.second}" } }
}

/** One window per weekday, in 15-minute steps; "Any time" stores `{}` (the gym's opening hours). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowsSheet(json: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var w by remember { mutableStateOf(parseWindows(json).ifEmpty { DEFAULT_WINDOWS }) }
    fun shift(t: String, min: Long) = LocalTime.parse(t).plusMinutes(min).let { if (it.minute % 15 != 0) it.withMinute(it.minute / 15 * 15) else it }.toString()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Usual training times", style = MaterialTheme.typography.titleLarge)
                Text("The planner suggests sessions inside these times.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                DAYS.forEach { (d, name) ->
                    val win = w[d]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(checked = win != null, onCheckedChange = { on -> w = if (on) w + (d to DEFAULT_WINDOWS.getValue(d)) else w - d })
                    }
                    if (win != null) {
                        StepperRow("from ${win.first}", null,
                            onMinus = { w = w + (d to (shift(win.first, -15) to win.second)) },
                            onPlus = { if (shift(win.first, 15) < win.second) w = w + (d to (shift(win.first, 15) to win.second)) })
                        StepperRow("to ${win.second}", null,
                            onMinus = { if (shift(win.second, -15) > win.first) w = w + (d to (win.first to shift(win.second, -15))) },
                            onPlus = { if (win.second < "23:45") w = w + (d to (win.first to shift(win.second, 15))) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalButton("Any time", { onSave("{}") }, Modifier.weight(1f))
                PrimaryButton("Save", { onSave(windowsJson(w)) }, Modifier.weight(1f))
            }
        }
    }
}

private val AGE_LABELS = mapOf("novice" to "Under 1 year", "intermediate" to "1-3 years", "advanced" to "More than 3 years")

private fun num(x: Double) = UiVolumeBars.text(x, 0.0, 0.0).substringBefore(" / ")

fun groupLabel(c: AppContainer, group: String): String = c.taxonomy.groups.firstOrNull { it.id == group }?.labelEn?.substringBefore(" (") ?: group
