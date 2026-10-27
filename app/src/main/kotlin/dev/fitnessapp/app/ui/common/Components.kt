package dev.fitnessapp.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.fitnessapp.app.ui.theme.Fit

/** Tabular numbers for every changing value (UX 4.2); the theme's styles already set it. */
val TabularNums = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")

/** A section label: sentence case, muted (UX 4.2). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Fit.Muted,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
    )
}

/**
 * The 64 dp stepper row of the set card (UX 6.1): ( - ) value ( + ), raised circles with accent glyphs.
 * [minusLabel]/[plusLabel] name the effect for assisted machines ("harder" / "easier").
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
        StepButton(minusLabel, "Decrease", Icons.Rounded.Remove, onMinus, enabled)
        // A small corner radius: a pill clip cuts the corners of a two-line secondary text.
        val valueModifier = if (onValueClick != null) Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onValueClick).padding(4.dp) else Modifier
        Column(Modifier.weight(1f).padding(horizontal = 8.dp).then(valueModifier), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                emphasizeNumbers(value), maxLines = 1,
                style = MaterialTheme.typography.displayMedium.copy(color = Fit.Ink, textAlign = TextAlign.Center),
                // UX 4.8 rule 2: shrink instead of wrapping when a long value meets the 64 dp buttons.
                autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 40.sp, stepSize = 2.sp),
            )
            if (secondary != null) Text(secondary, style = MaterialTheme.typography.bodySmall, color = Fit.Muted, textAlign = TextAlign.Center)
        }
        StepButton(plusLabel, "Increase", Icons.Rounded.Add, onPlus, enabled)
    }
}

private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")

/** Digits at full size, words and units ("BW +", "kg", "reps", "Pin") at half size in the muted colour (D31). */
internal fun emphasizeNumbers(value: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    val small = SpanStyle(fontSize = 0.5.em, color = Fit.Muted)
    for (m in NUMBER.findAll(value)) {
        if (m.range.first > last) withStyle(small) { append(value.substring(last, m.range.first)) }
        append(m.value)
        last = m.range.last + 1
    }
    if (last < value.length) withStyle(small) { append(value.substring(last)) }
}

@Composable
private fun StepButton(label: String?, fallback: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, enabled: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick, enabled = enabled, shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Fit.Raised, contentColor = Fit.Accent, disabledContainerColor = Fit.Raised.copy(alpha = 0.5f), disabledContentColor = Fit.Muted),
            modifier = Modifier.size(64.dp).semantics { contentDescription = label ?: fallback },
        ) { Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp)) }
        if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = Fit.Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A wrapping row of single-choice pill chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> ChipButton(label(o), selected = o == selected, onClick = { onSelect(o) }) }
    }
}

/**
 * A collapsed section for everything a user who keeps the defaults never needs (D35): a 48 dp row with the
 * [title] and a chevron; [content] shows only while it is open.
 */
@Composable
fun MoreSection(title: String, modifier: Modifier = Modifier, initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = Fit.Accent, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, if (open) "Hide" else "Show", tint = Fit.Accent)
        }
        if (open) content()
    }
}
