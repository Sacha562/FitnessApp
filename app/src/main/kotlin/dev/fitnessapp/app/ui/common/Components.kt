package dev.fitnessapp.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Tabular numbers for every changing value (UX 4). */
val TabularNums = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/**
 * The 64 dp stepper row of the set card (UX 6.1): ( - ) value ( + ). [minusLabel]/[plusLabel] name the effect
 * for assisted machines ("harder" / "easier").
 */
@Composable
fun StepperRow(
    value: String,
    secondary: String?,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    minusLabel: String? = null,
    plusLabel: String? = null,
    enabled: Boolean = true,
    onValueClick: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledTonalIconButton(onClick = onMinus, enabled = enabled, modifier = Modifier.size(64.dp).semantics { contentDescription = minusLabel ?: "Decrease" }) {
                Icon(Icons.Filled.Remove, contentDescription = null)
            }
            if (minusLabel != null) Text(minusLabel, style = MaterialTheme.typography.labelSmall)
        }
        val valueModifier = if (onValueClick != null) Modifier.heightIn(min = 48.dp).clickable(onClick = onValueClick) else Modifier
        Column(Modifier.weight(1f).padding(horizontal = 8.dp).then(valueModifier), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineMedium.merge(TabularNums), textAlign = TextAlign.Center)
            if (secondary != null) Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledTonalIconButton(onClick = onPlus, enabled = enabled, modifier = Modifier.size(64.dp).semantics { contentDescription = plusLabel ?: "Increase" }) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
            if (plusLabel != null) Text(plusLabel, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** A wrapping row of single-choice chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}
