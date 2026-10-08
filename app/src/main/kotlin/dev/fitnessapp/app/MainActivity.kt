package dev.fitnessapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.app.ui.home.BuildInfo
import dev.fitnessapp.app.ui.home.HomeScreen
import dev.fitnessapp.app.ui.home.HomeViewModel
import dev.fitnessapp.app.ui.theme.FitnessTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FitnessApplication).container
        val build = BuildInfo(BuildConfig.VERSION_NAME, EngineVersion.ENGINE_VERSION)
        setContent {
            FitnessTheme {
                val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
                val state by vm.state.collectAsStateWithLifecycle()
                HomeScreen(state, build, onSignIn = vm::signIn, onCheck = vm::checkConnection, onSignOut = vm::signOut)
            }
        }
    }
}
