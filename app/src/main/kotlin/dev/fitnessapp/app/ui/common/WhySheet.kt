package dev.fitnessapp.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.ui.theme.Fit

/** The Why sheet's sections (UX 16.1): title, summary, numbers, module line, evidence label and the trace to copy. */
data class WhyContent(
    val title: String,
    val summary: String,
    val numbers: List<String>,
    val module: String,
    val evidence: String,
    val trace: String,
    val recommendationId: String,
    val codes: List<String>,
)

/**
 * The Why sheet (UX 16.1, F19 layer 2) for an engine prescription: summary, numbers, module and params, evidence
 * label, and "Copy trace" (layer 3). [onShown] logs `why_opened`, [onCopied] `trace_exported`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhySheet(w: WhyContent, onDismiss: () -> Unit, onShown: () -> Unit, onCopied: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(w.recommendationId) { onShown() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp)).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(w.title, style = MaterialTheme.typography.titleLarge)
            Text(w.summary, style = MaterialTheme.typography.bodyLarge)
            SectionLabel("Numbers")
            w.numbers.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            SectionLabel("Module")
            Text(w.module, style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            SectionLabel("Evidence")
            Text(w.evidence, style = MaterialTheme.typography.bodyMedium)
            LinkButton("Copy trace", {
                clipboard.setText(AnnotatedString(w.trace))
                onCopied()
            })
        }
    }
}
