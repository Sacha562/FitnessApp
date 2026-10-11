package dev.fitnessapp.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/** D12: Dutch exercise names are shown and searched only when the phone's language is Dutch. */
fun isDutch(locale: Locale): Boolean = locale.language == "nl"

/** [isDutch] for the current configuration's first locale; recomposes when the phone language changes. */
@Composable
fun dutchNames(): Boolean = isDutch(LocalConfiguration.current.locales[0])
