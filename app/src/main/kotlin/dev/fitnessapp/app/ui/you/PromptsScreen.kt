package dev.fitnessapp.app.ui.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.planning.PromptPlans
import dev.fitnessapp.app.ui.common.FitScaffold
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TileLabel
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.uidet.Prompt
import dev.fitnessapp.app.uidet.PromptAdaptive
import dev.fitnessapp.data.repo.Setting
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * You > Prompts (UX 15.8, F15): the five feedback prompts with their switches, the guarded ones locked with the reason,
 * and the adaptive state with Reset ("Readiness is asked every 3rd session because it was skipped 3 times in a row").
 */
@Composable
fun PromptsRoute(c: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val profile by remember { c.profiles.observe() }.collectAsState(initial = null)
    var tick by remember { mutableIntStateOf(0) }
    var painRule by remember { mutableStateOf(false) }
    var lastPain by remember { mutableStateOf<Instant?>(null) }
    var adaptive by remember { mutableStateOf<Map<Prompt, PromptAdaptive>>(emptyMap()) }
    LaunchedEffect(tick) {
        painRule = runCatching { PromptPlans.challengePainRule(c) }.getOrDefault(false)
        lastPain = c.prompts.lastPainAt()
        adaptive = PromptPlans.adaptive(c)
    }
    val p = c.params.fork()
    val guardD = p.int("ui.prompts.pain_guard_d").toLong()
    val painGuard = painRule || lastPain?.let { Duration.between(it, Instant.now()) < Duration.ofDays(guardD) } == true
    val every = p.int("ui.prompts.reduced_every")
    val streak = p.int("ui.prompts.skip_streak")
    val t = PromptPlans.toggles(profile)

    FitScaffold("Prompts", onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Tile(spacing = 10.dp) {
                TileLabel("Before a gym session")
                Text("One optional sheet before the first exercise. Start is always available; questions you leave empty are skipped.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                Row1("Readiness", "Worse, normal or better than usual.", t.readiness, null) { on -> scope.launch { c.profiles.set(Setting.PROMPT_READINESS, on, "you.prompts") } }
                TileDivider()
                Row1("Elbow check", "0 none to 3 pain, with the kind of pain at 2 or more.", t.elbowCheck || painRule,
                    if (painRule) "Required while your challenge's elbow rule is active" else null) { on -> scope.launch { c.profiles.set(Setting.PROMPT_ELBOW, on, "you.prompts") } }
                TileDivider()
                Row1("Soreness", "For the muscles about to be trained, only when they were trained in the last 4 days.", t.soreness, null) { on ->
                    scope.launch { c.profiles.set(Setting.PROMPT_SORENESS, on, "you.prompts") }
                }
            }
            Tile(spacing = 10.dp) {
                TileLabel("After a session")
                Row1("Session effort", "0-10 for the whole session (Foster 2001).", t.srpe, null) { on -> scope.launch { c.profiles.set(Setting.PROMPT_SRPE, on, "you.prompts") } }
                TileDivider()
                Row1("Joint pain", "None, or where and how bad.", t.painAfter || painGuard,
                    when {
                        painRule -> "Required while your challenge's pain rule is active"
                        painGuard -> "Required for $guardD days after a pain report"
                        else -> null
                    }) { on -> scope.launch { c.profiles.set(Setting.PROMPT_PAIN, on, "you.prompts") } }
            }
            Tile(spacing = 6.dp) {
                TileLabel("Asked less often")
                val reduced = adaptive.filterValues { it.reduced }.keys.filter { !(it == Prompt.ELBOW_CHECK && painRule) && !(it == Prompt.JOINT_PAIN && painGuard) }
                if (reduced.isEmpty()) {
                    Text(
                        "Nothing. A question you skip $streak sessions in a row is asked every ${ordinal(every)} session until you answer it again.",
                        style = MaterialTheme.typography.bodySmall, color = Fit.Muted,
                    )
                }
                reduced.forEach { pr ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${NAMES.getValue(pr)} is asked every ${ordinal(every)} session because it was skipped $streak times in a row.",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        )
                        LinkButton("Reset", { scope.launch { c.prompts.reset(pr.code); tick++ } })
                    }
                }
            }
            Tile(spacing = 4.dp) {
                TileLabel("Weigh-in")
                Text("The Today screen asks for your weight when the last weigh-in is older than 14 days.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            }
        }
    }
}

@Composable
private fun Row1(title: String, body: String, on: Boolean, locked: String?, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            locked?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Advisory) }
        }
        Switch(checked = on, onCheckedChange = onChange, enabled = locked == null)
    }
}

private val NAMES = mapOf(
    Prompt.READINESS to "Readiness", Prompt.ELBOW_CHECK to "The elbow check", Prompt.SORENESS to "Soreness",
    Prompt.SRPE to "Session effort", Prompt.JOINT_PAIN to "Joint pain",
)

private fun ordinal(n: Int) = when (n) { 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }
