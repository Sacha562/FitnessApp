package dev.fitnessapp.app.ui.common

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.withTimeoutOrNull

/** Undo window base durations (UX P6, 6.5): 6 s for an item, 10 s for a session-level action. */
private const val UNDO_BASE_MS = 6_000
private const val UNDO_SESSION_BASE_MS = 10_000

/**
 * How long an Undo stays offered: the base duration, extended to the user's accessibility timeout when that is
 * longer (UX 23, WCAG 2.2.1).
 */
fun undoTimeoutMs(context: Context, sessionLevel: Boolean = false): Long {
    val base = if (sessionLevel) UNDO_SESSION_BASE_MS else UNDO_BASE_MS
    if (Build.VERSION.SDK_INT < 29) return base.toLong()
    val am = context.getSystemService(AccessibilityManager::class.java) ?: return base.toLong()
    val flags = AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS
    return maxOf(base, am.getRecommendedTimeoutMillis(base, flags)).toLong()
}

/**
 * Shows "[message] · Undo" for [undoTimeoutMs] and returns [SnackbarResult.ActionPerformed] when Undo was tapped.
 * Cancelling `showSnackbar` on timeout removes the snackbar.
 */
suspend fun SnackbarHostState.showUndo(message: String, context: Context, sessionLevel: Boolean = false): SnackbarResult =
    withTimeoutOrNull(undoTimeoutMs(context, sessionLevel)) {
        showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
    } ?: SnackbarResult.Dismissed
