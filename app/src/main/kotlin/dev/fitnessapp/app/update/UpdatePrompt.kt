package dev.fitnessapp.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fitnessapp.app.BuildConfig
import dev.fitnessapp.app.ui.common.TonalButton
import dev.fitnessapp.app.ui.theme.Fit

/** The update prompt of D98 (3)-(4); the caller shows it on the main tabs only. */
@Composable
fun UpdatePrompt(updates: UpdateManager) {
    val s by updates.state.collectAsState()
    val release = when (val x = s) {
        is UpdateState.Available -> x.release
        is UpdateState.Downloading -> x.release
        is UpdateState.ReadyToInstall -> x.release
        is UpdateState.DownloadFailed -> x.release
        else -> return
    }
    val installed = BuildConfig.VERSION_NAME.substringBefore('+')
    AlertDialog(
        onDismissRequest = { if (s !is UpdateState.Downloading) updates.later() },
        title = { Text(if (s is UpdateState.ReadyToInstall) "Update downloaded" else "Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val size = Releases.sizeLabel(release.apkSize)
                Text(
                    "Version ${release.versionName} is out. You have $installed." + if (size.isNotEmpty()) " Download: $size." else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (val x = s) {
                    is UpdateState.Downloading -> {
                        Text(x.progress?.let { "Downloading… ${(it * 100).toInt()}%" } ?: "Downloading…", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                        val p = x.progress
                        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateState.DownloadFailed -> Text("The download failed. Try again.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                    is UpdateState.ReadyToInstall -> Text("Android asks you to confirm the install. Your data stays on the phone.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                    else -> {}
                }
                if (release.notes.isNotBlank()) {
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        Text(release.notes, style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                    }
                }
            }
        },
        confirmButton = {
            when (s) {
                is UpdateState.Available -> TextButton(onClick = updates::download) { Text("Update") }
                is UpdateState.DownloadFailed -> TextButton(onClick = updates::download) { Text("Try again") }
                is UpdateState.ReadyToInstall -> TextButton(onClick = updates::installAgain) { Text("Install") }
                else -> {}
            }
        },
        dismissButton = {
            if (s is UpdateState.Downloading) TextButton(onClick = updates::cancelDownload) { Text("Cancel") }
            else TextButton(onClick = updates::later) { Text("Later") }
        },
    )
}

/** You > About "Check for updates" (D98 (5)). */
@Composable
fun UpdateCheckRow(updates: UpdateManager) {
    val s by updates.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TonalButton(if (s is UpdateState.Checking) "Checking…" else "Check for updates", updates::checkNow, enabled = s !is UpdateState.Checking && s !is UpdateState.Downloading)
        val line = when (val x = s) {
            UpdateState.UpToDate -> "Up to date"
            UpdateState.CheckFailed -> "Couldn't check for updates. Check your connection."
            is UpdateState.Later -> "Version ${x.release.versionName} is available"
            else -> null
        }
        if (line != null) Text(line, style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
    }
}
