package dev.fitnessapp.app.ui.reliability

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.timer.AlarmGateway
import dev.fitnessapp.app.timer.TimerSelfTest
import dev.fitnessapp.app.ui.theme.AdvisoryAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Manufacturer families of UX 8.6 step 6 / ANDROID.md 15.3. */
enum class Maker(val label: String, val steps: List<String>, val intents: List<Pair<String, String>>) {
    XIAOMI(
        "Xiaomi",
        listOf("Settings > Apps > Manage apps > FitnessApp > Autostart: on.", "Battery saver: No restrictions.", "Notifications: allow floating and lock-screen notifications.", "Recents: lock the app card."),
        listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    ),
    ONEPLUS(
        "OnePlus",
        listOf(
            "Settings > Battery > Battery optimisation > FitnessApp: Don't optimise.",
            "Advanced optimisation: switch off Deep optimisation and Sleep standby optimisation.",
            "App info > Battery usage: allow background activity and auto-launch.",
            "Recents: lock the app card.",
            "Check again after every system update.",
        ),
        listOf(
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        ),
    ),
    OPPO(
        "Oppo / Realme",
        listOf("App info > Allow auto-launch.", "Battery usage: allow background activity, do not optimise.", "Recents: lock the app card.", "Notifications: allow banners and lock screen."),
        listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        ),
    ),
    OTHER("Your phone", listOf("If your phone has its own battery manager, allow this app to run in the background."), emptyList());

    companion object {
        fun detect(): Maker = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> XIAOMI
            "oneplus" -> ONEPLUS
            "oppo", "realme" -> OPPO
            else -> OTHER
        }
    }
}

/** Tries the best-effort autostart intents in order, then the app details page (ANDROID.md 15.3). */
fun openOemSettings(context: Context, maker: Maker): Boolean {
    for ((pkg, cls) in maker.intents) {
        try {
            context.startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (e: ActivityNotFoundException) {
            continue
        } catch (e: SecurityException) {
            continue
        }
    }
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return false
}

/**
 * The reliability rows of UX 8.6 / ANDROID.md 15.4-15.5: each with a reason, a live status that re-checks when
 * the user returns from Settings, and a Grant action; plus the manufacturer steps and the timer self-test.
 */
@Composable
fun ReliabilityContent(c: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val prefs = remember { context.getSharedPreferences("reliability", Context.MODE_PRIVATE) }
    val maker = remember { Maker.detect() }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tick++
        scope.launch { c.fgsEvent("permission_result", mapOf("permission" to "post_notifications", "granted" to granted)) }
    }
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tick++
        scope.launch { c.fgsEvent("permission_result", mapOf("permission" to "activity_recognition", "granted" to granted)) }
    }

    // Statuses are read during composition; key(tick) re-reads them on resume and after permission results.
    key(tick) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row1(
            "Notifications", "Shows the rest countdown on the lock screen and alerts you when rest is over.",
            c.device.notificationsAllowed(), if (Build.VERSION.SDK_INT >= 33) "Allow" else null,
        ) {
            scope.launch { c.fgsEvent("permission_requested", mapOf("permission" to "post_notifications")) }
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Row1("Exact alarms", "So the rest alarm fires on time, even when the phone is idle.", c.device.canScheduleExactAlarms(), "Open settings") {
            if (Build.VERSION.SDK_INT >= 31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)))
        }
        if (Build.VERSION.SDK_INT >= 34) {
            Row1(
                "Physical activity", "Lets the workout session run as a fitness session in the background. The app does not count steps or read sensors.",
                c.device.activityRecognitionGranted(), "Allow",
            ) {
                scope.launch { c.fgsEvent("permission_requested", mapOf("permission" to "activity_recognition")) }
                activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        Row1("Battery optimisation", "Stops Android from pausing the timer to save battery.", c.device.ignoringBatteryOptimizations(), "Allow") {
            try {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
            } catch (e: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        if (Build.VERSION.SDK_INT >= 36) {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            Row1("Live Updates", "Shows the rest countdown as a chip in the status bar.", nm.canPostPromotedNotifications(), "Open settings") {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }

        // Phone-maker settings (step 6): instructions, best-effort intent, user confirmation.
        var confirmed by remember { mutableStateOf(prefs.getBoolean("oem_confirmed", false)) }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusTitle(maker.label + " settings", confirmed)
                Text("Your phone has its own battery manager that can stop apps even when the steps above are done.", style = MaterialTheme.typography.bodyMedium)
                maker.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
                OutlinedButton(onClick = {
                    val ok = openOemSettings(context, maker)
                    scope.launch { c.fgsEvent("oem_settings_opened", mapOf("maker" to maker.name.lowercase(), "intent_ok" to ok)) }
                }) { Text("Open settings") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmed, onCheckedChange = { v ->
                        confirmed = v
                        prefs.edit().putBoolean("oem_confirmed", v).apply()
                        if (v) scope.launch { c.fgsEvent("oem_settings_confirmed", mapOf("maker" to maker.name.lowercase())) }
                    })
                    Text("I've done this")
                }
            }
        }

        SelfTestCard(c)
    } }
    LaunchedEffect(tick) {
        c.fgsEvent("exact_alarm_status", mapOf("allowed" to c.device.canScheduleExactAlarms()))
        c.fgsEvent("battery_optimization_status", mapOf("ignoring" to c.device.ignoringBatteryOptimizations()))
    }
}

@Composable
private fun SelfTestCard(c: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf(TimerSelfTest.result(context)) }
    var running by remember { mutableStateOf(false) }
    var answered by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val r = result
            val latency = r?.firedAt?.let { it - r.scheduledEnd }
            StatusTitle("Test the rest alarm", latency != null && latency <= c.platformConfig.timerExactIdleP95MaxMs)
            Text("Start, then lock your phone and put it away. It should vibrate in 20 seconds.", style = MaterialTheme.typography.bodyMedium)
            if (running) Text("Waiting for the alarm…", style = MaterialTheme.typography.bodyMedium)
            if (latency != null) {
                Text(
                    if (latency <= 2000) "The alarm was on time (${latency} ms)." else "The alarm was ${latency / 1000} s late. Check the phone-maker settings above.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!answered) {
                    Text("Did you feel the vibration?")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(true, false).forEach { felt ->
                            OutlinedButton(onClick = {
                                answered = true
                                scope.launch {
                                    c.fgsEvent(
                                        "timer_selftest",
                                        mapOf(
                                            "latency_ms" to latency, "felt" to felt, "screen_off" to (r.screenOffAtFire ?: false),
                                            "alarm_mode" to r.alarmMode, "passed" to (felt && latency <= 2000), "platform_config_hash" to c.platformConfig.hash,
                                        ),
                                    )
                                }
                            }) { Text(if (felt) "Yes" else "No") }
                        }
                    }
                }
            }
            Button(onClick = {
                running = true
                answered = false
                scope.launch {
                    val mode = c.profiles.get()?.rest_alarm_mode ?: "alarm_clock"
                    TimerSelfTest.start(context, AlarmGateway(context), c.device, System.currentTimeMillis(), mode)
                    result = TimerSelfTest.result(context)
                    repeat(60) {
                        delay(1000)
                        val now = TimerSelfTest.result(context)
                        if (now?.firedAt != null) {
                            result = now
                            running = false
                            return@launch
                        }
                    }
                    running = false
                }
            }, enabled = !running) { Text(if (result == null) "Start 20 s test" else "Run again") }
        }
    }
}

@Composable
private fun Row1(title: String, reason: String, ok: Boolean, action: String?, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusTitle(title, ok)
            Text(reason, style = MaterialTheme.typography.bodyMedium)
            if (!ok && action != null) OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun StatusTitle(title: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = if (ok) "OK" else "Needs attention",
            tint = if (ok) MaterialTheme.colorScheme.primary else AdvisoryAmber,
        )
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(if (ok) "Allowed" else "Not yet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
