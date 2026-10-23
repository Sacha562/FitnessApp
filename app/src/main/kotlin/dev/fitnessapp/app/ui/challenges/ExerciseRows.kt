package dev.fitnessapp.app.ui.challenges

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.ui.theme.Fit

/** A session's exercises, one per row: the name, and "3×3 · pin 50 lb" on the right (setup review, Today's preview). */
@Composable
fun ExerciseRows(exercises: List<ReviewExercise>) {
    exercises.forEach { e ->
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(e.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (e.detail.isNotEmpty()) Text(e.detail, style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
        }
    }
}
