package dev.fitnessapp.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import dev.fitnessapp.app.nav.Routes
import dev.fitnessapp.app.ui.AppRoot
import dev.fitnessapp.app.ui.theme.FitnessTheme

class MainActivity : ComponentActivity() {
    /** The internal route of the last notification tap (ANDROID.md 7.4); consumed by [AppRoot]. */
    private val route = MutableStateFlow<Routes.Target?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        route.value = intent?.getStringExtra("route")?.let(Routes::parse)
        val container = (application as FitnessApplication).container
        if (savedInstanceState == null) reportOpened(intent)
        setContent {
            FitnessTheme { AppRoot(container, route) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("route")?.let { route.value = Routes.parse(it) }
        reportOpened(intent)
    }

    /** `notification_opened {notification_id, channel}` for a notification tap (UX 18). */
    private fun reportOpened(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: return
        intent.removeExtra(EXTRA_NOTIFICATION_ID)
        val c = (application as FitnessApplication).container
        c.appScope.launch { runCatching { c.fgsEvent("notification_opened", mapOf("notification_id" to id, "channel" to channel)) } }
    }

    companion object {
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_CHANNEL = "notification_channel"
    }
}
