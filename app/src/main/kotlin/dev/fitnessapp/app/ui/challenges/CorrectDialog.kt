package dev.fitnessapp.app.ui.challenges

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.DateV
import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.StrV
import dev.fitnessapp.algo.challenge.fitexpr.Type
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.FitTextField
import dev.fitnessapp.app.ui.common.StepperRow
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.theme.Fit
import java.math.BigDecimal
import java.time.LocalDate

/**
 * "Correct a value" (UX 14.2, CHALLENGE_ENGINE 4.9): pick a correctable value, set the new one with a stepper (or
 * chips, a switch, a date) and one reason chip; Save writes `manual.override`. The engine type-checks, bounds and
 * rounds the value; the injury mode and the status are not offered here.
 */
@Composable
fun CorrectDialog(targets: List<CorrectTarget>, onSave: (String, Value, String) -> Unit, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf<CorrectTarget?>(null) }
    var value by remember { mutableStateOf<Value?>(null) }
    var dateText by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf<Pair<String, String>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(picked?.label ?: "Correct a value") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val t = picked
                if (t == null) {
                    targets.forEachIndexed { i, x ->
                        if (i > 0) TileDivider()
                        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { picked = x; value = x.current; dateText = (x.current as? DateV)?.d?.toString() ?: "" }.padding(vertical = 6.dp)) {
                            Text(x.label, style = MaterialTheme.typography.bodyMedium)
                            Text(ChallengeTexts.value(x.current), style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                        }
                    }
                } else {
                    Text("Now: ${ChallengeTexts.value(t.current)}", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                    when {
                        t.type is Type.Q && t.step != null -> {
                            val q = (value as? QV) ?: QV((t.type as Type.Q).dim, BigDecimal.ZERO)
                            StepperRow(
                                ChallengeTexts.value(q), null,
                                onMinus = { value = QV(q.dim, Dec.norm(q.v.subtract(t.step))) },
                                onPlus = { value = QV(q.dim, Dec.norm(q.v.add(t.step))) },
                            )
                        }
                        t.type is Type.EnumT -> ChoiceChips(t.values, (value as? StrV)?.s, { it }) { value = StrV(it) }
                        t.type == Type.BoolT -> ChoiceChips(listOf(true, false), (value as? BoolV)?.b, { if (it) "Yes" else "No" }) { value = BoolV.of(it) }
                        t.type == Type.DateT -> FitTextField(
                            dateText, { dateText = it; value = runCatching { DateV(LocalDate.parse(it.trim())) }.getOrNull() },
                            label = "Date (yyyy-mm-dd)", singleLine = true,
                        )
                    }
                    Text("Why?", style = MaterialTheme.typography.labelMedium, color = Fit.Muted)
                    ChoiceChips(OVERRIDE_REASONS, reason, { it.first }) { reason = it }
                }
            }
        },
        confirmButton = {
            val t = picked
            TextButton(
                onClick = { if (t != null) onSave(t.target, value!!, reason!!.second) },
                enabled = t != null && value != null && reason != null && value != t.current,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { if (picked != null) picked = null else onDismiss() }) { Text(if (picked != null) "Back" else "Cancel") } },
    )
}

/** Reason chips of a correction; the DATA_MODEL 9.1.3 vocabulary has no correction codes yet (CHALLENGE_ENGINE 16.2). */
private val OVERRIDE_REASONS = listOf("Loaded a different weight" to "other", "The value was wrong" to "other", "Too hard" to "too_hard", "Too easy" to "too_easy")
