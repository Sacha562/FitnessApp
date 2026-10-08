package dev.fitnessapp.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.ui.theme.AdvisoryAmber
import dev.fitnessapp.data.remote.AccessCheck

/** Build facts shown at the bottom of the skeleton screen. */
data class BuildInfo(val appVersion: String, val engineVersion: String)

@Composable
fun HomeScreen(
    state: HomeState,
    build: BuildInfo,
    onSignIn: (email: String, password: String) -> Unit,
    onCheck: () -> Unit,
    onSignOut: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("FitnessApp", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                "Foundations build. Logging, challenges and recommendations arrive in the next milestones.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state.phase) {
                Phase.STARTING -> CircularProgressIndicator()
                Phase.SIGNED_OUT -> SignInCard(state, onSignIn)
                Phase.SIGNED_IN -> ConnectionCard(state, onCheck, onSignOut)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "App ${build.appVersion} · Engine ${build.engineVersion}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SignInCard(state: HomeState, onSignIn: (String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sign in", style = MaterialTheme.typography.titleMedium)
            Text(
                "Use your existing account. Forgot your password? Reset it in the Supabase dashboard.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = { onSignIn(email, password) },
                enabled = !state.busy && email.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(if (state.busy) "Signing in…" else "Sign in") }
        }
    }
}

@Composable
private fun ConnectionCard(state: HomeState, onCheck: () -> Unit, onSignOut: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            val (accessText, accessColor) = when (state.access) {
                null -> "Checking access…" to MaterialTheme.colorScheme.onSurfaceVariant
                AccessCheck.Result.Allowed -> "Account enabled" to MaterialTheme.colorScheme.primary
                AccessCheck.Result.NotAllowed ->
                    "This account is not enabled for the fitness app." to MaterialTheme.colorScheme.error
                AccessCheck.Result.SchemaNotExposed ->
                    "The fitness schema is not exposed in the Data API yet." to AdvisoryAmber
                is AccessCheck.Result.Failed -> "Offline or unreachable (${state.access.errorClass})" to AdvisoryAmber
            }
            StatusRow("Access", accessText, accessColor)
            val (rtText, rtColor) = when (val rt = state.roundTrip) {
                null -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
                is RoundTripState.Ok -> "Profile synced (server ${rt.serverUpdatedAt.take(19)})" to MaterialTheme.colorScheme.primary
                RoundTripState.Mismatch -> "Profile read back differs" to MaterialTheme.colorScheme.error
                is RoundTripState.Failed -> "Profile sync failed (${rt.errorClass})" to MaterialTheme.colorScheme.error
            }
            StatusRow("Round trip", rtText, rtColor)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onCheck, enabled = !state.busy, modifier = Modifier.height(48.dp)) { Text("Check again") }
                OutlinedButton(onClick = onSignOut, enabled = !state.busy, modifier = Modifier.height(48.dp)) { Text("Sign out") }
                if (state.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, text: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}
