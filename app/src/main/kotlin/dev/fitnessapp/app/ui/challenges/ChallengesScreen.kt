package dev.fitnessapp.app.ui.challenges

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.common.Banner
import dev.fitnessapp.app.ui.common.BannerKind
import dev.fitnessapp.app.ui.common.Pill
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.SectionLabel
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TonalButton
import dev.fitnessapp.app.ui.theme.Fit

/** The Challenges tab (UX 14.1): active, available and past challenges, and the absence prompt (4.6). */
@Composable
fun ChallengesRoute(c: AppContainer, modifier: Modifier, onOpen: (String) -> Unit, onSetup: (String) -> Unit) {
    val vm: ChallengesViewModel = viewModel(factory = viewModelFactory { initializer { ChallengesViewModel(c) } })
    val ui by vm.ui.collectAsState()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Challenges", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        ui.error?.let { Banner(it, BannerKind.ADVISORY, action = "OK", onAction = vm::dismissError) }
        ui.absence?.let { (_, last) ->
            Tile {
                Text("Were you away?", style = MaterialTheme.typography.titleMedium)
                Text("No challenge session since ${ChallengeTexts.date(last)}. Count it as a pause?", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Yes, I was away", vm::acceptAbsence, Modifier.weight(1f), height = 48.dp)
                    TonalButton("No", vm::declineAbsence, Modifier.weight(1f), height = 48.dp)
                }
            }
        }
        if (ui.loading) {
            Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
            return@Column
        }
        if (ui.active.isEmpty() && ui.past.isEmpty()) {
            Text("No challenge running.", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
        }
        if (ui.active.isNotEmpty()) {
            SectionLabel("Active")
            ui.active.forEach { card -> CardTile(card, onClick = { card.enrollmentId?.let(onOpen) }) }
        }
        if (ui.available.isNotEmpty()) {
            SectionLabel("Available")
            ui.available.forEach { card ->
                CardTile(card) {
                    PrimaryButton("Set up", { onSetup(card.definitionId) }, Modifier.fillMaxWidth(), height = 48.dp)
                }
            }
        }
        if (ui.past.isNotEmpty()) {
            SectionLabel("Past")
            ui.past.forEach { card -> CardTile(card, onClick = { card.enrollmentId?.let(onOpen) }) }
        }
    }
}

@Composable
private fun CardTile(card: ChallengeCard, onClick: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Tile(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            card.status?.let { Pill(it) }
        }
        card.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fit.Muted) }
        card.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Advisory) }
        actions()
    }
}
