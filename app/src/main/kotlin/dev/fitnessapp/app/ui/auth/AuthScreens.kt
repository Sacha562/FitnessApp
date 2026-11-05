package dev.fitnessapp.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fitnessapp.app.ui.common.FitTextField
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TonalButton
import dev.fitnessapp.app.ui.theme.Fit

/** UX 8.2: sign in with the existing account; no sign-up, no stored password. */
@Composable
fun SignInScreen(
    busy: Boolean, error: String?, onSignIn: (String, String) -> Unit,
    initialEmail: String = "", subtitle: String = "Sign in with your existing account",
) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var password by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = Fit.Ground) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Fitness", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 44.sp, lineHeight = 48.sp), color = Fit.Accent)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Fit.Muted)
            FitTextField(
                value = email, onValueChange = { email = it }, label = "Email", singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
            )
            FitTextField(
                value = password, onValueChange = { password = it }, label = "Password", singleLine = true, enabled = !busy,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { LinkButton(if (show) "Hide" else "Show", { show = !show }) },
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                isError = error != null,
                supportingText = error?.let { { Text(it, color = Fit.Safety) } },
            )
            Text("First sign-in needs internet; after that it works offline.", style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
            if (busy) {
                Box(Modifier.fillMaxWidth().height(58.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp) }
            } else {
                PrimaryButton("Sign in", { onSignIn(email, password) }, Modifier.fillMaxWidth(), enabled = email.isNotBlank() && password.isNotEmpty())
            }
        }
    }
}

/** UX 8.3 and the other blocking states before onboarding. */
@Composable
fun GateMessageScreen(title: String, body: String, email: String?, primary: String, onPrimary: () -> Unit, secondary: String?, onSecondary: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Fit.Ground) {
        Column(
            Modifier.safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Tile(spacing = 8.dp) {
                Text(body, style = MaterialTheme.typography.bodyLarge)
                if (email != null) Text("Signed in as " + mask(email), style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
            }
            TonalButton(primary, onPrimary, Modifier.fillMaxWidth())
            if (secondary != null) TonalButton(secondary, onSecondary, Modifier.fillMaxWidth(), container = Fit.Tile)
        }
    }
}

@Composable
fun LoadingScreen(text: String) {
    Surface(Modifier.fillMaxSize(), color = Fit.Ground) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator(trackColor = Fit.Raised)
            Spacer(Modifier.height(16.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Fit.Muted)
        }
    }
}

/** "y***@e***.com" (UX 8.3). */
fun mask(email: String): String {
    val at = email.indexOf('@')
    if (at <= 0) return "***"
    val domain = email.substring(at + 1)
    val dot = domain.lastIndexOf('.')
    val host = if (dot > 0) domain.substring(0, dot) else domain
    val tld = if (dot > 0) domain.substring(dot) else ""
    return email.take(1) + "***@" + host.take(1) + "***" + tld
}
