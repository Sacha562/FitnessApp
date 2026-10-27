package dev.fitnessapp.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.theme.Fit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One past session "Repeat" offers: its id, date and the exercises it copies. */
data class RepeatOption(val sessionId: String, val date: LocalDate, val exercises: List<String>)

/**
 * "Repeat" on the session start (D49): the finished general sessions, newest first, each with its date and exercises.
 * Picking one starts a session with the same exercises, order and set counts; RX-1 sets loads and reps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatSheet(c: AppContainer, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var options by remember { mutableStateOf<List<RepeatOption>?>(null) }
    LaunchedEffect(Unit) {
        options = c.sessionRepository.repeatable(REPEAT_LIMIT).map { s ->
            RepeatOption(
                s.id, LocalDate.parse(s.local_date),
                c.sessionRepository.exercises(s.id).filter { it.status != "removed" }.mapNotNull { c.catalog.byId(it.exercise_id)?.nameEn },
            )
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Repeat a session", style = MaterialTheme.typography.titleLarge)
            val list = options
            when {
                list == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                list.isEmpty() -> Text("No finished sessions to repeat yet.", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(list, key = { it.sessionId }) { o ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(o.sessionId) }.padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(o.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)), style = MaterialTheme.typography.titleMedium)
                            Text(o.exercises.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                        }
                        TileDivider()
                    }
                }
            }
        }
    }
}

/** How many past sessions the sheet lists. */
private const val REPEAT_LIMIT = 20
