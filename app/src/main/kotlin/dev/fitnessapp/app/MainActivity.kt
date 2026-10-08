package dev.fitnessapp.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow
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
        setContent {
            FitnessTheme { AppRoot(container, route) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("route")?.let { route.value = Routes.parse(it) }
    }
}
