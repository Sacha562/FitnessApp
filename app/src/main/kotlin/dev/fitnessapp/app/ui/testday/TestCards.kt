package dev.fitnessapp.app.ui.testday

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.MoreSection
import dev.fitnessapp.app.ui.common.Pill
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.StepperRow
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TonalButton
import dev.fitnessapp.app.ui.theme.Fit

/** What every test card shows above its input (UX 14.9, D35): the title, the side and attempt, the best so far. */
data class CardHead(val title: String, val side: String? = null, val attempt: String? = null, val best: String? = null, val protocol: String? = null)

@Composable
private fun Head(h: CardHead) {
    Text(h.title, style = MaterialTheme.typography.titleLarge)
    val line = listOfNotNull(h.side, h.attempt).joinToString(" · ")
    if (line.isNotEmpty()) Pill(line)
    h.best?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fit.Muted) }
}

@Composable
private fun Foot(h: CardHead, undo: (() -> Unit)?, skip: (() -> Unit)?) {
    h.protocol?.let { MoreSection("How") { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Muted) } }
    if (undo != null || skip != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            undo?.let { LinkButton("Undo last", it) }
            skip?.let { LinkButton("Skip this test", it, color = Fit.Muted) }
        }
    }
}

/**
 * The Record card (UX 14.9, C63): one value on the 64 dp stepper and one Record button; test entries are observations,
 * so there are no RIR chips. Used for the weigh-in, max reps, max holds and the strength and ladder attempts.
 */
@Composable
fun RecordCard(
    head: CardHead,
    value: String,
    secondary: String?,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    record: String,
    onRecord: () -> Unit,
    enabled: Boolean = true,
    minusLabel: String? = null,
    plusLabel: String? = null,
    secondaryAction: Pair<String, () -> Unit>? = null,
    onUndo: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    onValueClick: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    Tile(spacing = 10.dp) {
        Head(head)
        extra()
        StepperRow(value, secondary, onMinus, onPlus, minusLabel = minusLabel, plusLabel = plusLabel, enabled = enabled, onValueClick = onValueClick)
        if (secondaryAction == null) {
            PrimaryButton(record, onRecord, Modifier.fillMaxWidth(), enabled = enabled)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(record, onRecord, Modifier.weight(1f), enabled = enabled)
                TonalButton(secondaryAction.first, secondaryAction.second, Modifier.weight(1f), enabled = enabled, height = 58.dp)
            }
        }
        Foot(head, onUndo, onSkip)
    }
}

/**
 * The attempt card (UX 14.9): one yes/no attempt per tap, "Clean" or "Not clean". The test day's unassisted negatives
 * and one-arm attempts use it, and so does the attempts block of phase 3 in a live session; [checklist] is the
 * definition's attempt checklist (`challengeRules.attemptChecklist`), folded (D35).
 */
@Composable
fun AttemptCard(
    head: CardHead,
    onClean: () -> Unit,
    onFail: () -> Unit,
    enabled: Boolean = true,
    clean: String = "Clean",
    fail: String = "Not clean",
    checklist: List<String> = emptyList(),
    onUndo: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
) {
    Tile(spacing = 10.dp) {
        Head(head)
        if (checklist.isNotEmpty()) {
            MoreSection("What counts") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    checklist.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = Fit.Muted) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(clean, onClean, Modifier.weight(1f), enabled = enabled)
            TonalButton(fail, onFail, Modifier.weight(1f), enabled = enabled, height = 58.dp)
        }
        Foot(head, onUndo, onSkip)
    }
}
