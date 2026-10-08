package dev.fitnessapp.app.ui.onboarding

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.equipment.EquipmentContent
import dev.fitnessapp.app.ui.equipment.EquipmentViewModel
import dev.fitnessapp.app.ui.reliability.ReliabilityContent
import kotlinx.coroutines.launch

/** The M1a onboarding steps (UX 8.1); set/rep, goals, calendars and the challenge offer follow later milestones. */
private enum class Step(val title: String) { ABOUT("About"), PROFILE("Profile"), RELIABILITY("Reliability"), EQUIPMENT("Your gym"), DONE("Done") }

/** Resumable: the step index is saved after each completed step (UX 8.1). */
@Composable
fun OnboardingScreen(c: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("onboarding", Context.MODE_PRIVATE) }
    var stepIndex by rememberSaveable { mutableIntStateOf(prefs.getInt("step", 0).coerceIn(0, Step.entries.size - 1)) }
    val step = Step.entries[stepIndex]
    val scope = rememberCoroutineScope()
    fun next() {
        stepIndex = (stepIndex + 1).coerceAtMost(Step.entries.size - 1)
        prefs.edit().putInt("step", stepIndex).apply()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().fillMaxSize()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Step ${stepIndex + 1} of ${Step.entries.size} · ${step.title}", style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(progress = { (stepIndex + 1f) / Step.entries.size }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            when (step) {
                Step.ABOUT -> AboutStep { scope.launch { c.profiles.acceptDisclaimer(); next() } }
                Step.PROFILE -> ProfileStep(c) { next() }
                Step.RELIABILITY -> Scrolling(
                    primary = "Continue", onPrimary = {
                        scope.launch { c.profiles.stepCompleted("permissions", emptyList()) }
                        next()
                    },
                ) {
                    Text("Make the rest timer reliable", style = MaterialTheme.typography.headlineSmall)
                    Text("Your phone is among the most aggressive at stopping apps in the background. Each step is optional, but the timer is only as reliable as these settings.", style = MaterialTheme.typography.bodyMedium)
                    ReliabilityContent(c)
                }
                Step.EQUIPMENT -> {
                    val vm: EquipmentViewModel = viewModel(factory = viewModelFactory { initializer { EquipmentViewModel(c) } })
                    val state by vm.state.collectAsState()
                    Scrolling(
                        primary = "Confirm equipment", onPrimary = {
                            prefs.edit().putBoolean("equipment_later", false).apply()
                            vm.save(confirmed = true) { next() }
                        },
                        secondary = "Later", onSecondary = {
                            prefs.edit().putBoolean("equipment_later", true).apply()
                            vm.save(confirmed = false) { next() }
                        },
                    ) {
                        state?.let { EquipmentContent(it, vm) }
                    }
                }
                Step.DONE -> Scrolling(primary = "Open Today", onPrimary = {
                    scope.launch {
                        c.profiles.completeOnboarding()
                        prefs.edit().putInt("step", 0).apply()
                        onDone()
                    }
                }) {
                    Text("You're set.", style = MaterialTheme.typography.headlineSmall)
                    Text("Today is where you start a session. Add exercises as you go; the app remembers what you did last time and fills it in.", style = MaterialTheme.typography.bodyLarge)
                    Text("Weights are in kilograms. Machines marked in pounds (like the assisted pull-up machine) are shown in pounds with kilograms next to them.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Scrolling(
    primary: String,
    onPrimary: () -> Unit,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (secondary != null) TextButton(onClick = onSecondary) { Text(secondary) }
            Button(onClick = onPrimary, enabled = enabled, modifier = Modifier.weight(1f).height(56.dp)) { Text(primary) }
        }
    }
}

/** UX 8.4: three short paragraphs and the disclaimer checkbox. */
@Composable
private fun AboutStep(onContinue: () -> Unit) {
    var checked by rememberSaveable { mutableStateOf(false) }
    Scrolling(primary = "Continue", onPrimary = onContinue, enabled = checked) {
        Text("About this app", style = MaterialTheme.typography.headlineSmall)
        Text("The app plans your week, picks exercises and suggests weights from what you log. Every recommendation can be explained (tap Why) and overridden.", style = MaterialTheme.typography.bodyLarge)
        Text("It is offline-first: everything is saved on this phone and uploaded when you have internet.", style = MaterialTheme.typography.bodyLarge)
        Text("This app does not give medical advice. If something hurts, stop and get it checked.", style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth().clickable { checked = !checked }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { checked = it })
            Text("I understand this is not medical advice.")
        }
    }
}

/** UX 8.5: bodyweight (recommended), training experience, optional age and name. No sex field (D3). */
@Composable
private fun ProfileStep(c: AppContainer, onContinue: () -> Unit) {
    val scope = rememberCoroutineScope()
    var bw by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf<String?>(null) }
    var birthYear by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val experience = listOf("novice" to "Under 1 year", "intermediate" to "1-3 years", "advanced" to "More than 3 years")
    Scrolling(primary = "Continue", onPrimary = {
        scope.launch {
            val kg = bw.replace(',', '.').toDoubleOrNull()?.takeIf { it in 20.0..300.0 }
            if (kg != null) c.bodyweight.logWeighIn(kg)
            c.profiles.saveBasics(name.ifBlank { null }, age, birthYear.toIntOrNull()?.takeIf { it in 1900..2100 }, null)
            onContinue()
        }
    }, secondary = "Skip", onSecondary = {
        scope.launch { c.profiles.stepCompleted("profile", emptyList()) }
        onContinue()
    }) {
        Text("A few facts", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = bw, onValueChange = { bw = it }, label = { Text("Bodyweight (kg)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Needed for pull-ups, dips and assisted machines (system load).") },
        )
        Text("Training experience", style = MaterialTheme.typography.titleSmall)
        ChoiceChips(experience.map { it.first }, age, { code -> experience.first { it.first == code }.second }) { age = it }
        OutlinedTextField(
            value = birthYear, onValueChange = { birthYear = it.filter(Char::isDigit).take(4) }, label = { Text("Birth year (optional)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Only used to adjust maintenance volume if you are 60 or older.") },
        )
        OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("Display name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}
