package dev.fitnessapp.app.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import dev.fitnessapp.algo.core.params.ParamView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance

/** The params for UI behaviour such as the long-press timing (UX 6.1); null without an app (screen tests): no repeat. */
val LocalParams = staticCompositionLocalOf<ParamView?> { null }

/** A button's [interactionSource] and the [onClick] to pass to it, from [rememberHoldRepeat]. */
class HoldRepeat(val interactionSource: MutableInteractionSource, val onClick: () -> Unit)

/**
 * Long press (UX 6.1): after `ui.prefill.hold_delay_ms` [onRepeat] runs every `ui.prefill.repeat_ms` with the
 * repeat count (1 = the first repeat) until the finger lifts. The release that ends a repeating press is no tap:
 * the returned click handler skips it, so [onClick] runs only for a short tap (and accessibility clicks).
 */
@Composable
fun rememberHoldRepeat(enabled: Boolean, onClick: () -> Unit, onRepeat: (Int) -> Unit): HoldRepeat {
    val params = LocalParams.current
    val source = remember { MutableInteractionSource() }
    val repeated = remember { booleanArrayOf(false) }
    val click by rememberUpdatedState(onClick)
    val repeat by rememberUpdatedState(onRepeat)
    LaunchedEffect(source, enabled, params) {
        if (!enabled || params == null) return@LaunchedEffect
        val delayMs = params.int("ui.prefill.hold_delay_ms").toLong()
        val repeatMs = params.int("ui.prefill.repeat_ms").toLong()
        // A release or cancel arrives as the next press interaction and cancels the repeat loop.
        source.interactions.filterIsInstance<PressInteraction>().collectLatest { i ->
            if (i !is PressInteraction.Press) return@collectLatest
            repeated[0] = false
            delay(delayMs)
            var n = 0
            while (true) {
                repeated[0] = true
                repeat(++n)
                delay(repeatMs)
            }
        }
    }
    return remember(source) { HoldRepeat(source) { if (repeated[0]) repeated[0] = false else click() } }
}
