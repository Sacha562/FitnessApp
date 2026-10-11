package dev.fitnessapp.app.ui.theme

import androidx.compose.ui.graphics.Color

/** The Bento dark palette (D31, UX 4.1). The app is dark only; contrast ratios are listed in UX 4.1. */
object Fit {
    /** Screen and window background. */
    val Ground = Color(0xFF0B0D12)

    /** Tiles, bottom sheets, dialogs, the navigation bar. */
    val Tile = Color(0xFF151922)

    /** Inner tiles, stepper buttons, chips, tonal buttons, the current exercise row, menus. */
    val Raised = Color(0xFF1E2330)

    /** Dividers and decorative outlines; never the only boundary of a control. */
    val Line = Color(0xFF2A3040)

    /** Text-field borders and other UI boundaries that need 3:1. */
    val Outline = Color(0xFF6A7286)

    val Ink = Color(0xFFEEF0F6)
    val Muted = Color(0xFF9AA1B2)

    /** The one accent (P2): primary action, progress, selection, done. */
    val Accent = Color(0xFFA9A4FF)
    val OnAccent = Color(0xFF14123A)

    val Advisory = Color(0xFFF5B85C)
    val AdvisoryContainer = Color(0xFF342F2A)
    val Safety = Color(0xFFFF8A80)
    val SafetyContainer = Color(0xFF36292F)

    /** Muscle map silhouettes (UX 4.1). */
    val MapBase = Color(0xFF262C3A)
    val MapIdle = Color(0xFF343B4C)
}
