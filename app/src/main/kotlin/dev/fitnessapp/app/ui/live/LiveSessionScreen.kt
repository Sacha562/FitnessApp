package dev.fitnessapp.app.ui.live

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.load.LoadScheme
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.timer.RestDisplay
import dev.fitnessapp.app.timer.RestTimerReducer
import dev.fitnessapp.app.ui.common.Banner
import dev.fitnessapp.app.ui.common.BannerKind
import dev.fitnessapp.app.ui.common.ChipButton
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.FitTextField
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.LogChip
import dev.fitnessapp.app.ui.common.MoreSection
import dev.fitnessapp.app.ui.common.Pill
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.ProgressBar
import dev.fitnessapp.app.ui.common.RingTimer
import dev.fitnessapp.app.ui.common.RoundIconButton
import dev.fitnessapp.app.ui.common.SectionLabel
import dev.fitnessapp.app.ui.common.StepperRow
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.common.TileDivider
import dev.fitnessapp.app.ui.common.TonalButton
import dev.fitnessapp.app.ui.common.dutchNames
import dev.fitnessapp.app.ui.common.undoTimeoutMs
import dev.fitnessapp.app.ui.equipment.Checklist
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.ui.theme.FitType
import dev.fitnessapp.app.uidet.ChipContext
import dev.fitnessapp.app.uidet.ChipRoute
import dev.fitnessapp.app.uidet.UiReasonChips
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Sheet {
    data object Add : Sheet
    data class Skip(val seId: String) : Sheet
    data class Occupied(val seId: String) : Sheet
    data class End(val seId: String) : Sheet
    data object Finish : Sheet
    data object Pain : Sheet
    data object Keypad : Sheet
    data object Stack : Sheet
    data object Abandon : Sheet
    data object RuleUndo : Sheet
}

@Composable
fun LiveSessionRoute(c: AppContainer, sessionId: String, onMinimise: (via: String) -> Unit, onFinished: (String) -> Unit, onAbandoned: () -> Unit) {
    val vm: LiveSessionViewModel = viewModel(key = sessionId, factory = viewModelFactory { initializer { LiveSessionViewModel(c, sessionId) } })
    val ui by vm.ui.collectAsState()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val profile by remember { c.profiles.observe() }.collectAsState(initial = null)
    BackHandler(onBack = { onMinimise("back") })

    // Keep screen on during the session (F20, ANDROID.md 14.3); holds force it on.
    val view = LocalView.current
    val holding = ui.card?.holdPhase in setOf(HoldPhase.LEAD_IN, HoldPhase.HOLDING)
    DisposableEffect(profile?.keep_screen_on, holding) {
        view.keepScreenOn = (profile?.keep_screen_on ?: true) || holding
        onDispose { view.keepScreenOn = false }
    }

    Surface(Modifier.fillMaxSize(), color = Fit.Ground) {
        Column(Modifier.safeDrawingPadding().fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header(ui, { onMinimise("chevron") }, onFinish = {
                if (vm.openExercises().isEmpty()) vm.finish(null, onFinished) else sheet = Sheet.Finish
            }, onAdd = { sheet = Sheet.Add }, onPain = { sheet = Sheet.Pain }, onAbandon = { sheet = Sheet.Abandon })
            if (ui.banner == "pain") Banner(
                "Pain noted, exercise ended. If it hurts, stop and get it checked.",
                BannerKind.SAFETY, action = "OK", onAction = vm::dismissBanner,
            )
            if (ui.banner == "log_failed") Banner("That set could not be saved. Log it again.", BannerKind.SAFETY, action = "OK", onAction = vm::dismissBanner)
            ui.ruleNotice?.let { Banner(it, BannerKind.ADVISORY, action = "OK", onAction = vm::dismissRuleNotice) }
            // UX 11.10 (D14): an in-session rule changed the remaining sets at once; Undo takes it back with a reason.
            ui.ruleLine?.let { line -> Banner(line.text, BannerKind.ADVISORY, action = "Undo", onAction = { sheet = Sheet.RuleUndo }) }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // The list takes what it needs, at most the top 38 % (UX 4.3); the set card gets the rest.
                val listMax = maxHeight * 0.38f
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (ui.items.isNotEmpty()) ExerciseList(
                        ui, Modifier.heightIn(max = listMax), onFocus = vm::focus, onAdd = { sheet = Sheet.Add },
                        onReorder = { ids, moved -> vm.reorder(ids, moved, via = "drag") },
                    )
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ui.rest?.let { RestCard(c, it, ui, vm) }
                        if (ui.switchHint) Pill("Switch to your right side", container = Fit.Accent, content = Fit.OnAccent)
                        val card = ui.card
                        if (card == null) {
                            EmptyAction(ui, onAdd = { sheet = Sheet.Add }, onFinish = { vm.finish(null, onFinished) })
                        } else {
                            val intervalRunning = ui.rest?.let { it.purpose == "interval" && it.state == "running" } == true
                            val attempt = card.attempt
                            if (attempt != null) {
                                // Phase 3's attempts block uses the test day's attempt card (UX 14.9): Clean or Not clean.
                                dev.fitnessapp.app.ui.testday.AttemptCard(
                                    dev.fitnessapp.app.ui.testday.CardHead(
                                        card.item.ex.nameEn, when (card.side) { "L" -> "Left"; "R" -> "Right"; else -> null },
                                        "Attempt ${card.setNumber} of ${maxOf(card.item.planned, card.setNumber)}",
                                    ),
                                    onClean = { vm.logAttempt(true) }, onFail = { vm.logAttempt(false) }, enabled = ui.rest?.state != "running",
                                    checklist = attempt.checklist,
                                )
                            } else SetCard(card, vm, intervalRunning, onMenu = { action ->
                                when (action) {
                                    "skip" -> sheet = Sheet.Skip(card.item.se.id)
                                    "end" -> sheet = Sheet.End(card.item.se.id)
                                    "pain" -> sheet = Sheet.Pain
                                    "stack" -> sheet = Sheet.Stack
                                }
                            }, onKeypad = { sheet = Sheet.Keypad }, onPainReason = { sheet = Sheet.Pain })
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                    // The log row is pinned in the thumb zone (P3, D9); it waits while a rest is counting down.
                    ui.card?.let { card -> if (ui.rest?.state != "running" && card.attempt == null) LogRow(card, vm) }
                }
            }
            UndoBar(ui, vm)
        }
    }

    when (val s = sheet) {
        Sheet.Add -> AddExerciseSheet(c, onDismiss = { sheet = null }) { ex -> vm.addExercise(ex); sheet = null }
        is Sheet.Skip -> ReasonSheet(c, ChipContext.SKIP, "Skip ${nameOf(ui, s.seId)}?", onDismiss = { sheet = null }) { code, pos ->
            when (UiReasonChips.route(code, ChipContext.SKIP)) {
                ChipRoute.DO_LATER -> sheet = Sheet.Occupied(s.seId)
                ChipRoute.PAIN_SHEET -> { vm.skip(s.seId, code, pos); sheet = Sheet.Pain }
                else -> { vm.skip(s.seId, code, pos); sheet = null }
            }
        }
        is Sheet.Occupied -> OccupiedSheet(nameOf(ui, s.seId), vm.canDoLater(s.seId), onDismiss = { sheet = null },
            onDoLater = { vm.doLater(s.seId); sheet = null }, onSkip = { vm.skip(s.seId, "occupied", 1); sheet = null })
        is Sheet.End -> ReasonSheet(c, ChipContext.SKIP, "End ${nameOf(ui, s.seId)}? Logged sets stay.", onDismiss = { sheet = null }) { code, _ ->
            vm.endExercise(s.seId, code)
            sheet = if (code == "pain") Sheet.Pain else null
        }
        Sheet.Finish -> FinishSheet(c, vm.openExercises().map { it.ex.nameEn }, onDismiss = { sheet = null }) { reason ->
            sheet = null
            vm.finish(reason, onFinished)
        }
        Sheet.Pain -> PainSheet(onDismiss = { sheet = null }) { region, side, severity, symptoms ->
            vm.reportPain(region, side, severity, symptoms)
            sheet = null
        }
        Sheet.Keypad -> ui.card?.let { card -> KeypadSheet(card, vm, onDismiss = { sheet = null }) }
        Sheet.Stack -> (ui.card?.scheme as? LoadScheme.Stack)?.let { stack ->
            StackSheet(ui.card?.item?.ex?.nameEn ?: "Machine", stack, onDismiss = { sheet = null }) { unit, start, step, max ->
                vm.saveStack(unit, start, step, max)
                sheet = null
            }
        }
        Sheet.RuleUndo -> AlertDialog(
            onDismissRequest = { sheet = null },
            title = { Text("Undo the rule?") },
            text = { Text("The remaining sets go back to their earlier values. Why?") },
            confirmButton = {
                Row {
                    TextButton(onClick = { sheet = null; vm.undoRule("too_hard") }) { Text("Too hard") }
                    TextButton(onClick = { sheet = null; vm.undoRule("too_easy") }) { Text("Too easy") }
                    TextButton(onClick = { sheet = null; vm.undoRule("other") }) { Text("Other") }
                }
            },
            dismissButton = { TextButton(onClick = { sheet = null }) { Text("Keep") } },
        )
        Sheet.Abandon -> AlertDialog(
            onDismissRequest = { sheet = null },
            title = { Text("Abandon session?") },
            text = { Text("Logged sets are kept; the session is marked abandoned.") },
            confirmButton = { TextButton(onClick = { sheet = null; vm.abandon(onAbandoned) }) { Text("Abandon") } },
            dismissButton = { TextButton(onClick = { sheet = null }) { Text("Keep going") } },
        )
        null -> {}
    }
}

private fun nameOf(ui: LiveUi, seId: String) = ui.items.firstOrNull { it.se.id == seId }?.ex?.nameEn ?: "exercise"

@Composable
private fun Header(ui: LiveUi, onMinimise: () -> Unit, onFinish: () -> Unit, onAdd: () -> Unit, onPain: () -> Unit, onAbandon: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    var menu by remember { mutableStateOf(false) }
    val started = ui.session?.started_at ?: now
    val done = ui.items.sumOf { minOf(it.workingDone, maxOf(it.planned, it.workingDone)) }
    val total = ui.items.filter { it.se.status != "skipped" }.sumOf { maxOf(it.planned, it.workingDone) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoundIconButton(Icons.Rounded.KeyboardArrowDown, "Minimise", onMinimise)
        Column(Modifier.weight(1f).padding(start = 2.dp)) {
            Text(ui.title ?: "Free session", style = MaterialTheme.typography.titleMedium, color = Fit.Ink)
            Text(Numbers.clock((now - started) / 1000) + " · $done of $total sets", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
        }
        if (ui.pendingCount > 0) Icon(Icons.Rounded.CloudOff, "Changes waiting to upload", tint = Fit.Muted, modifier = Modifier.size(18.dp))
        TonalButton("Finish", onFinish, height = 48.dp, container = Fit.Tile)
        Box {
            RoundIconButton(Icons.Rounded.MoreVert, "Session menu", { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Add exercise") }, onClick = { menu = false; onAdd() })
                DropdownMenuItem(text = { Text("Report pain") }, onClick = { menu = false; onPain() })
                DropdownMenuItem(text = { Text("Abandon session") }, onClick = { menu = false; onAbandon() })
            }
        }
    }
    ProgressBar(if (total == 0) 0f else done.toFloat() / total, Modifier.padding(horizontal = 4.dp))
}

/**
 * Drag-to-reorder of the exercise list (UX 11.4): the dragged row follows the finger and swaps with the row its middle
 * crosses; [order] is the order shown while dragging and after the drop until Room emits it.
 */
private class ListDrag(val list: LazyListState) {
    var order by mutableStateOf<List<String>?>(null)
    var dragged by mutableStateOf<String?>(null)
    private var startOrder: List<String> = emptyList()
    private var startOffset = 0
    private var distance by mutableFloatStateOf(0f)

    private fun info(key: Any?) = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /** [y] is the finger in the list's own coordinates; only an open exercise row can be dragged. */
    fun start(y: Float, ids: List<String>, draggable: (String) -> Boolean): Boolean {
        val at = y + list.layoutInfo.viewportStartOffset
        val row = list.layoutInfo.visibleItemsInfo.firstOrNull { at.toInt() in it.offset until it.offset + it.size } ?: return false
        val id = (row.key as? String)?.takeIf { it in ids && draggable(it) } ?: return false
        startOrder = ids
        order = ids
        dragged = id
        startOffset = row.offset
        distance = 0f
        return true
    }

    /** Moves the dragged row by [dy]; returns how far the list should scroll to keep it in view. */
    fun drag(dy: Float): Float {
        val id = dragged ?: return 0f
        val ids = order ?: return 0f
        distance += dy
        val row = info(id) ?: return 0f
        val top = startOffset + distance
        val middle = (top + row.size / 2f).toInt()
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key != id && it.key in ids && middle in it.offset until it.offset + it.size }
        if (target != null) {
            val from = ids.indexOf(id)
            val to = ids.indexOf(target.key)
            // Skips a layout that still shows the order before the last swap (the target must lie on the side it moves to).
            if ((to > from) == (target.offset > row.offset)) order = ids.toMutableList().apply { add(to, removeAt(from)) }
        }
        val info = list.layoutInfo
        return when {
            top < info.viewportStartOffset -> top - info.viewportStartOffset
            top + row.size > info.viewportEndOffset -> top + row.size - info.viewportEndOffset
            else -> 0f
        }
    }

    /** The dragged row's shift from its laid-out place. */
    fun shift(id: String): Float = info(id)?.let { startOffset + distance - it.offset } ?: 0f

    /** An interrupted drag puts the rows back. */
    fun cancel() {
        dragged = null
        order = null
    }

    /** Ends the drag; returns the new order and the moved id when the order changed. */
    fun end(): Pair<List<String>, String>? {
        val id = dragged
        dragged = null
        val ids = order
        if (id == null || ids == null || ids == startOrder) { order = null; return null }
        return ids to id
    }
}

@Composable
private fun ExerciseList(ui: LiveUi, modifier: Modifier, onFocus: (String) -> Unit, onAdd: () -> Unit, onReorder: (List<String>, String) -> Unit) {
    val list = rememberLazyListState()
    val drag = remember(list) { ListDrag(list) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val ids = ui.items.map { it.se.id }
    // The dropped order stays until Room has it, or the exercises themselves change.
    LaunchedEffect(ids) { drag.order?.let { o -> if (drag.dragged == null && (o == ids || o.toSet() != ids.toSet())) drag.order = null } }
    val byId = ui.items.associateBy { it.se.id }
    val shown = drag.order?.let { o -> o.mapNotNull(byId::get) + ui.items.filter { it.se.id !in o } } ?: ui.items
    // The gesture outlives recompositions: it reads the latest rows and callback.
    val items by rememberUpdatedState(ui.items)
    val reorder by rememberUpdatedState(onReorder)
    // Long-press 300 ms starts the drag (UX 11.4); Move up / down in the exercise menu is the accessible alternative.
    val base = LocalViewConfiguration.current
    val config = remember(base) { object : ViewConfiguration by base { override val longPressTimeoutMillis = 300L } }
    CompositionLocalProvider(LocalViewConfiguration provides config) {
    LazyColumn(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Fit.Tile).pointerInput(drag) {
            detectDragGesturesAfterLongPress(
                onDragStart = { o ->
                    val open = items.filter { it.open }.map { it.se.id }.toSet()
                    if (drag.start(o.y, drag.order ?: items.map { it.se.id }) { it in open }) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDrag = { change, d ->
                    if (drag.dragged != null) {
                        change.consume()
                        val scroll = drag.drag(d.y)
                        if (scroll != 0f) scope.launch { list.scrollBy(scroll) }
                    }
                },
                onDragEnd = { drag.end()?.let { (o, id) -> reorder(o, id) } },
                onDragCancel = drag::cancel,
            )
        },
        state = list, contentPadding = PaddingValues(6.dp),
    ) {
        items(shown, key = { it.se.id }) { item ->
            val current = item.se.id == ui.focusedId
            val skipped = item.se.status == "skipped"
            val done = item.se.status == "done"
            val dragging = item.se.id == drag.dragged
            Row(
                (if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = drag.shift(item.se.id) } else Modifier.animateItem())
                    .fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(if (current || dragging) Fit.Raised else Fit.Tile)
                    // Disabled while dragging so lifting the finger after a long-press does not also jump to the row.
                    .clickable(enabled = !skipped && drag.dragged == null) { onFocus(item.se.id) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    done -> Box(Modifier.size(22.dp).clip(CircleShape).background(Fit.Raised), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, "Done", tint = Fit.Accent, modifier = Modifier.size(14.dp))
                    }
                    current -> Box(Modifier.size(22.dp).clip(CircleShape).background(Fit.Accent), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Fit.OnAccent))
                    }
                    else -> Box(Modifier.size(22.dp).padding(1.dp).border(2.dp, Fit.Line, CircleShape))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    item.ex.nameEn + if (item.unilateral) " (L/R)" else "",
                    style = (if (current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge).copy(
                        textDecoration = if (skipped) TextDecoration.LineThrough else null,
                    ),
                    color = if (done || skipped) Fit.Muted else Fit.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val each = if (item.unilateral && !item.warmupItem) " each side" else ""
                val tail = when {
                    skipped -> "skipped"
                    item.se.status == "deferred" -> "later · ${item.workingDone}/${item.planned}$each"
                    else -> "${item.workingDone}/${maxOf(item.planned, item.workingDone)}$each"
                }
                Text(tail, style = MaterialTheme.typography.labelLarge, color = if (current) Fit.Ink else Fit.Muted, modifier = Modifier.padding(start = 8.dp))
            }
        }
        item(key = "add") {
            Row(
                Modifier.animateItem().fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onAdd).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, null, tint = Fit.Muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("Add exercise", color = Fit.Muted, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    }
}

@Composable
private fun EmptyAction(ui: LiveUi, onAdd: () -> Unit, onFinish: () -> Unit) {
    Tile(radius = 28.dp, padding = PaddingValues(18.dp), spacing = 12.dp) {
        if (ui.items.isEmpty()) {
            Text("Add your first exercise", style = MaterialTheme.typography.titleLarge)
            PrimaryButton("Add exercise", onAdd, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
        } else {
            Text("All exercises done", style = MaterialTheme.typography.titleLarge)
            PrimaryButton("Finish session", onFinish, Modifier.fillMaxWidth())
            TonalButton("Add another exercise", onAdd, Modifier.fillMaxWidth())
        }
    }
}

/**
 * UX 11.13.2: countdown in a depleting ring, -15 / Skip / +15 (500 ms input guard), next-set preview. In overtime the
 * card hides itself so the set card needs no Skip; the timer stays open until the next set closes it.
 */
@Composable
private fun RestCard(c: AppContainer, t: dev.fitnessapp.data.db.local.RestTimerEntity, ui: LiveUi, vm: LiveSessionViewModel) {
    var display by remember(t.id) { mutableStateOf<RestDisplay?>(null) }
    val haptic = LocalHapticFeedback.current
    var prewarned by remember(t.id) { mutableStateOf(false) }
    LaunchedEffect(t) {
        while (true) {
            val r = RestTimerReducer.remainingMs(t, System.currentTimeMillis(), c.device.elapsedRealtime(), c.device.bootCount())
            display = RestTimerReducer.display(t, System.currentTimeMillis(), c.device.elapsedRealtime(), c.device.bootCount())
            // Pre-warning: one light tick at 10 s left, only while the UI is visible (ANDROID.md 12.4, C72).
            if (!prewarned && t.state == "running" && r in 1..(c.platformConfig.timerPrewarnS * 1000L)) {
                prewarned = true
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                vm.prewarned(t.id)
            }
            delay(200)
        }
    }
    if (display is RestDisplay.Overtime) return
    val shownAt = remember(t.id) { System.currentTimeMillis() }
    val guard = { System.currentTimeMillis() - shownAt > 500 }
    // An interval is the countdown of a timed warm-up item (D33): its name, and Done instead of Skip.
    val interval = t.purpose == "interval"
    Tile(radius = 28.dp, padding = PaddingValues(16.dp), spacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (interval) t.label ?: "Interval" else "Rest")
            Spacer(Modifier.weight(1f))
            if (t.alarm_mode == "inexact") Text("Alarm may ring late", color = Fit.Advisory, style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val d = display) {
                is RestDisplay.Countdown -> RingTimer(d.barFraction.toFloat(), Modifier.size(196.dp)) {
                    Text(d.digits, style = FitType.RestDigits, color = Fit.Ink)
                }
                else -> Box(Modifier.size(196.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val running = t.state == "running"
            TonalButton("−15 s", { if (guard()) vm.adjustRest(-c.platformConfig.timerAdjustStepS) }, Modifier.weight(1f), enabled = running, height = 64.dp)
            TonalButton(if (interval) "Done" else "Skip", { if (guard()) vm.skipRest() }, Modifier.weight(1f), height = 64.dp)
            TonalButton("+15 s", { if (guard()) vm.adjustRest(c.platformConfig.timerAdjustStepS) }, Modifier.weight(1f), enabled = running, height = 64.dp)
        }
        ui.restNext?.takeIf { !interval }?.let {
            Tile(color = Fit.Raised, radius = 18.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), spacing = 2.dp) {
                Text("Up next", style = MaterialTheme.typography.labelMedium, color = Fit.Muted)
                Text(it, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetCard(card: Card, vm: LiveSessionViewModel, intervalRunning: Boolean, onMenu: (String) -> Unit, onKeypad: () -> Unit, onPainReason: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val item = card.item
    // The pacing beep (UX 11.7.4, 11.7.5) is switched in the exercise menu to keep the set card short (D35).
    val beepable = when { card.isTimed -> false; card.isRound -> true; card.isHold -> false; else -> card.isEcc }
    var beep by remember(item.se.id) { mutableStateOf(false) }
    Tile(radius = 28.dp, padding = PaddingValues(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 16.dp), spacing = 10.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.ex.nameEn, style = MaterialTheme.typography.titleLarge)
                val sideLabel = when (card.side) { "L" -> " · Left"; "R" -> " · Right"; else -> "" }
                // An L+R pair is one set (C7), so the count of a sided item says it is per side.
                val each = if (item.unilateral) " each side" else ""
                val count = when {
                    card.warmup -> "Warm-up set"
                    card.isRound -> "Round ${card.setNumber} of ${maxOf(item.planned, card.setNumber)}$each"
                    else -> "Set ${card.setNumber} of ${maxOf(item.planned, card.setNumber)}$each"
                }
                Pill(count + sideLabel)
            }
            Box {
                RoundIconButton(Icons.Rounded.MoreVert, "Exercise menu", { menu = true }, container = Fit.Raised)
                ExerciseMenu(
                    menu, card, vm, beep = beep.takeIf { beepable }, onBeep = { menu = false; beep = !beep },
                    onDismiss = { menu = false }, onAction = { menu = false; onMenu(it) },
                )
            }
        }
        card.lastLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fit.Muted) }
        if (card.sideCapApplied) Text("Right ≤ left: ${card.reps} clean reps", style = MaterialTheme.typography.bodySmall, color = Fit.Accent)
        if (card.deviation.fields.any { it.field == "side_rule" }) Text("Right above left", style = MaterialTheme.typography.bodySmall, color = Fit.Advisory)

        // Load row (UX 11.7); a timed item (the warm-up row) has none.
        when {
            card.isTimed -> {}
            // Finger assistance (UX 11.7): fewer fingers is harder.
            card.mode == LoadMode.ASSISTED_ORDINAL -> StepperRow(
                value = (card.fingers ?: 0).let { "$it finger" + if (it == 1) "" else "s" }, secondary = card.plannedFingers?.let { "planned $it" },
                onMinus = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); vm.stepFingers(up = false) },
                onPlus = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); vm.stepFingers(up = true) },
                minusLabel = "harder", plusLabel = "easier",
            )
            card.mode == LoadMode.BODYWEIGHT -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BW", style = MaterialTheme.typography.displayMedium, modifier = Modifier.weight(1f))
                if (LoadMode.BODYWEIGHT_PLUS_EXTERNAL.code in item.ex.allowedLoadModes) LinkButton("+ Add weight", { vm.setLoadMode(LoadMode.BODYWEIGHT_PLUS_EXTERNAL) })
            }
            // An external load without equipment (a plate on the back of a plank) has nothing to calibrate.
            card.points.isEmpty() && item.ex.equipment.all { it == "bodyweight" || it == "other" } ->
                card.target.native?.takeIf { it > 0 }?.let { Text("+ " + Numbers.num(it, 2) + " kg", style = MaterialTheme.typography.titleMedium) }
            card.points.isEmpty() -> Text("No loadable values known for this machine. Set them in You > Equipment.", style = MaterialTheme.typography.bodyMedium, color = Fit.Advisory)
            else -> {
                val assisted = card.mode == LoadMode.ASSISTED_MACHINE
                StepperRow(
                    value = vm.loadLabel(card), secondary = vm.loadSecondary(card),
                    // Assisted: lower pin = harder; the buttons are labelled by effect (UX 6.1).
                    onMinus = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); if (assisted) vm.stepLoad(harder = true) else vm.stepLoad(harder = false) },
                    onPlus = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); if (assisted) vm.stepLoad(harder = false) else vm.stepLoad(harder = true) },
                    minusLabel = if (assisted) "harder" else null, plusLabel = if (assisted) "easier" else null,
                    onValueClick = onKeypad,
                )
                card.keypadMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fit.Advisory) }
                if (card.mode == LoadMode.BODYWEIGHT_PLUS_EXTERNAL && LoadMode.BODYWEIGHT.code in item.ex.allowedLoadModes) {
                    LinkButton("Bodyweight only", { vm.setLoadMode(LoadMode.BODYWEIGHT) })
                }
            }
        }

        when {
            card.isTimed -> TimedSection(card, intervalRunning, vm)
            card.isRound -> RoundSection(card, vm, beep)
            card.isHold -> HoldSection(card, vm)
            else -> {
                StepperRow(value = "${card.reps ?: 0} reps", secondary = null, onMinus = { vm.stepReps(false) }, onPlus = { vm.stepReps(true) })
                if (card.isEcc) EccSection(card, vm, beep)
            }
        }

        // Reason chips and flags are optional (D35): collapsed for each new set, open when one is already chosen.
        key(card.slotKey) {
            // Reason chips: optional, only when deviating (UX 11.8).
            card.chips?.let { chips ->
                MoreSection("Why the change?", initiallyOpen = card.reason != null) {
                    var more by remember { mutableStateOf(false) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (chips.visible + if (more) chips.more else emptyList()).forEach { chip ->
                            ChipButton(chip.label, selected = card.reason == chip.code, onClick = {
                                vm.chooseReason(chip.code, chip.position, chip.position >= chips.visible.size)
                                if (chip.code == "pain") onPainReason()
                            })
                        }
                        if (!more && chips.more.isNotEmpty()) LinkButton("More", { more = true })
                    }
                }
            }

            // Flags offered by the exercise (C74, UX 11.9).
            if (item.ex.flagsOffered.isNotEmpty()) MoreSection("Flag something", initiallyOpen = card.flags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.ex.flagsOffered.forEach { f ->
                        ChipButton(flagLabel(f), selected = f in card.flags, onClick = { vm.toggleFlag(f) }, role = Role.Checkbox)
                    }
                }
            }
        }

    }
}

/**
 * The log action of the current set (D9), pinned below the set card: one tap logs; the RIR is never prefilled or
 * highlighted. A row that appears under the finger ignores taps for 500 ms (P5).
 */
@Composable
private fun LogRow(card: Card, vm: LiveSessionViewModel) {
    if (card.isHold && !card.isTimed && !card.isRound && card.holdPhase != HoldPhase.RESULT) return
    val haptic = LocalHapticFeedback.current
    val shownAt = remember(card.slotKey) { System.currentTimeMillis() }
    fun guarded(action: () -> Unit) {
        if (System.currentTimeMillis() - shownAt < 500) return
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        action()
    }
    Tile(radius = 28.dp, padding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), spacing = 8.dp) {
        when {
            card.warmup -> PrimaryButton("Done", { guarded { vm.log(control = "done") } }, Modifier.fillMaxWidth(), height = 64.dp)
            card.isHold || card.isEcc -> {
                Text("How did it feel?", style = MaterialTheme.typography.labelMedium, color = Fit.Muted, modifier = Modifier.padding(start = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Max" to 0.0, "Hard" to 2.0, "OK" to 5.0, "Easy" to 10.0).forEach { (label, s) ->
                        LogChip(label, { guarded { vm.log(feltReserveS = s, feltLowerBound = s == 10.0, control = "felt_chip") } }, Modifier.weight(1f))
                    }
                }
            }
            else -> {
                // The side is on the set card's pill.
                Text("Reps left in the tank?", style = MaterialTheme.typography.labelMedium, color = Fit.Muted, modifier = Modifier.padding(start = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..5).forEach { r ->
                        LogChip(if (r == 5) "5+" else r.toString(), { guarded { vm.log(rir = r, control = "rir_chip") } }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** The readable label of an exercise flag code (C74); History and the set edit sheet use it too. */
internal fun flagLabel(f: String) = when (f) {
    "grip_slip_before_last" -> "Grip slipped before last rep"
    "torso_rotation" -> "Torso rotated"
    "jerky" -> "Jerky lowering"
    "hip_rotation" -> "Hips rotated"
    "hand_shift" -> "Hand shifted"
    "technique_breakdown" -> "Technique broke down"
    "failed_rep" -> "Failed rep"
    "used_straps" -> "Used straps"
    else -> f.replace('_', ' ')
}

@Composable
private fun ExerciseMenu(open: Boolean, card: Card, vm: LiveSessionViewModel, beep: Boolean?, onBeep: () -> Unit, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    val se = card.item.se
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        if (card.item.workingDone == 0) DropdownMenuItem(text = { Text("Skip") }, onClick = { onAction("skip") })
        if (vm.canDoLater(se.id)) DropdownMenuItem(text = { Text("Do later") }, onClick = { onDismiss(); vm.doLater(se.id) })
        DropdownMenuItem(text = { Text("Add a set") }, onClick = { onDismiss(); vm.addSet(se.id) })
        DropdownMenuItem(text = { Text(if (card.warmup) "Log working sets" else "Log a warm-up set") }, onClick = { onDismiss(); vm.setWarmup(!card.warmup) })
        if (card.item.workingDone > 0) DropdownMenuItem(text = { Text("End exercise") }, onClick = { onAction("end") })
        if (card.item.sets.isEmpty()) DropdownMenuItem(text = { Text("Remove") }, onClick = { onDismiss(); vm.remove(se.id) })
        DropdownMenuItem(text = { Text("Move up") }, onClick = { onDismiss(); vm.move(se.id, up = true) })
        DropdownMenuItem(text = { Text("Move down") }, onClick = { onDismiss(); vm.move(se.id, up = false) })
        card.item.ex.allowedLoadModes.map(LoadMode::of).filter { it != card.mode && it != LoadMode.ASSISTED_ORDINAL }.forEach { m ->
            val label = when (m) {
                LoadMode.BODYWEIGHT -> "Bodyweight only"
                LoadMode.BODYWEIGHT_PLUS_EXTERNAL -> "Add weight (belt)"
                LoadMode.ASSISTED_MACHINE -> "Use the assisted machine"
                else -> "External load"
            }
            DropdownMenuItem(text = { Text(label) }, onClick = { onDismiss(); vm.setLoadMode(m) })
        }
        if (card.stackRowId != null) DropdownMenuItem(text = { Text("Stack setup") }, onClick = { onAction("stack") })
        // The optional beep per second; null when this set has none.
        beep?.let { on -> DropdownMenuItem(text = { Text(if (on) "Stop the beep" else "Beep each second") }, onClick = onBeep) }
        DropdownMenuItem(text = { Text("Something hurts", color = Fit.Safety) }, onClick = { onAction("pain") })
    }
}

/**
 * UX 11.7.6: "Row 3:00 [Start 3:00]". Start runs the alarm-backed countdown in the rest card (D33); zero logs the item
 * done with its seconds. Done below logs the planned seconds without a countdown.
 */
@Composable
private fun TimedSection(card: Card, intervalRunning: Boolean, vm: LiveSessionViewModel) {
    val s = card.holdS ?: return
    val clock = Numbers.clock(s.toLong())
    Text(card.item.ex.nameEn + " " + clock, style = MaterialTheme.typography.titleMedium)
    // While it runs, the rest card above shows the countdown.
    if (!intervalRunning) PrimaryButton("Start $clock", vm::startInterval, Modifier.fillMaxWidth(), height = 64.dp)
}

/**
 * UX 11.7.5 negatives: "Shortest lowering" is the minimum lowering time of the set's reps (`ecc_s_actual`), because
 * the rules compare the minimum; the optional beep (exercise menu) paces the lowering.
 */
@Composable
private fun EccSection(card: Card, vm: LiveSessionViewModel, beep: Boolean) {
    card.eccTargetS?.let { Text("Lower in " + Numbers.num(it, 1) + " s or more", style = MaterialTheme.typography.titleMedium) }
    StepperRow(
        value = card.eccS?.let { Numbers.num(it, 1) + " s" } ?: "– s", secondary = "shortest lowering",
        onMinus = { vm.stepEcc(false) }, onPlus = { vm.stepEcc(true) },
    )
    if (beep) CountAloud()
}

/** UX 11.7.4 top-hold round: one field per short attempt (0.5 s steps), the felt chip logs them all. */
@Composable
private fun RoundSection(card: Card, vm: LiveSessionViewModel, beep: Boolean) {
    val attempts = card.attemptsS ?: return
    Text("${attempts.size} short attempts · no help", style = MaterialTheme.typography.titleMedium)
    attempts.forEachIndexed { i, s ->
        StepperRow(value = Numbers.num(s, 1) + " s", secondary = "attempt ${i + 1}", onMinus = { vm.stepAttempt(i, false) }, onPlus = { vm.stepAttempt(i, true) })
    }
    if (beep) CountAloud()
}

/**
 * The optional beep per second while the user does the set (UX 11.7.4, 11.7.5): switched in the exercise menu, off by
 * default, never logged. Composed only while on.
 */
@Composable
private fun CountAloud() {
    val haptic = LocalHapticFeedback.current
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    LaunchedEffect(Unit) {
        while (true) {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            delay(1000)
        }
    }
}

/** UX 11.7.3: ready, 5 s lead-in with 3-2-1, holding (tap anywhere to stop), result. */
@Composable
private fun HoldSection(card: Card, vm: LiveSessionViewModel) {
    val haptic = LocalHapticFeedback.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    LaunchedEffect(card.holdPhase, card.holdPhaseStart) {
        var lastSecond = -1L
        while (card.holdPhase == HoldPhase.LEAD_IN || card.holdPhase == HoldPhase.HOLDING) {
            now = System.currentTimeMillis()
            val sec = (now - card.holdPhaseStart) / 1000
            if (card.holdPhase == HoldPhase.LEAD_IN) {
                val left = 5 - sec
                if (sec != lastSecond && left in 1..3) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80) }
                if (left <= 0) { haptic.performHapticFeedback(HapticFeedbackType.Confirm); tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 150); vm.holdGo(); return@LaunchedEffect }
            } else {
                val target = card.holdTargetS
                if (sec != lastSecond && sec > 0) {
                    if (target != null && sec == target.toLong()) { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200); haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                    else if (sec % 5 == 0L && (target == null || sec < target)) { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60); haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                }
                if (sec >= 300) { vm.stopHold(auto = true); return@LaunchedEffect }
            }
            lastSecond = sec
            delay(100)
        }
    }
    card.holdTargetS?.let { Text("Target " + Numbers.num(it, 1) + " s", style = MaterialTheme.typography.titleMedium) }
    when (card.holdPhase) {
        HoldPhase.READY -> PrimaryButton("Start (5 s lead-in)", vm::startHold, Modifier.fillMaxWidth(), height = 64.dp)
        HoldPhase.LEAD_IN -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(maxOf(1, 5 - (now - card.holdPhaseStart) / 1000).toString(), style = FitType.HoldDigits, color = Fit.Ink)
            Text("Get on the bar", style = MaterialTheme.typography.titleMedium, color = Fit.Muted)
            LinkButton("Cancel", vm::cancelHold)
        }
        HoldPhase.HOLDING -> Box(
            Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(24.dp)).background(Fit.Raised).clickable { vm.stopHold() },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Numbers.clock((now - card.holdPhaseStart) / 1000), style = FitType.HoldDigits, color = Fit.Accent)
                Text("Tap anywhere to stop", style = MaterialTheme.typography.titleMedium, color = Fit.Ink)
            }
        }
        HoldPhase.RESULT -> {
            StepperRow(
                value = Numbers.num(card.holdS ?: 0.0, 1) + " s", secondary = card.holdTimerS?.let { "timer ran " + Numbers.oneDecimal(it) + " s" },
                onMinus = { vm.stepHold(false) }, onPlus = { vm.stepHold(true) },
            )
        }
    }
}

@Composable
private fun UndoBar(ui: LiveUi, vm: LiveSessionViewModel) {
    val l = ui.lastLogged ?: return
    val context = LocalContext.current
    LaunchedEffect(l.setId) { delay(undoTimeoutMs(context)); vm.dismissUndo() }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(CircleShape).background(Fit.Line).padding(start = 18.dp, end = 4.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Check, null, tint = Fit.Accent, modifier = Modifier.size(18.dp))
        Text("Logged: ${l.label}", style = MaterialTheme.typography.bodyMedium, color = Fit.Ink, modifier = Modifier.weight(1f).padding(start = 10.dp))
        LinkButton("Undo", vm::undoLast)
    }
}

// ---------------------------------------------------------------------------------------------------- sheets

/** Sheet content padding: 16 dp sides, room for the gesture bar at the bottom. */
private val SheetPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExerciseSheet(c: AppContainer, onDismiss: () -> Unit, onPick: (dev.fitnessapp.algo.selection.catalog.CatalogExercise) -> Unit) {
    var query by remember { mutableStateOf("") }
    val dutch = dutchNames()
    val results = remember(query, dutch) { c.catalog.search(query, dutch) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(SheetPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add exercise", style = MaterialTheme.typography.titleLarge)
            FitTextField(
                value = query, onValueChange = { query = it }, placeholder = if (dutch) "Search (English or Dutch)" else "Search",
                singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Fit.Muted) }, modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(results, key = { it.slug }) { ex ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(ex) }.padding(horizontal = 8.dp, vertical = 12.dp)) {
                        Text(ex.nameEn, style = MaterialTheme.typography.titleMedium)
                        Text(listOfNotNull(ex.nameNl?.takeIf { dutch }, ex.equipment.joinToString()).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                    }
                    TileDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReasonSheet(c: AppContainer, context: ChipContext, title: String, onDismiss: () -> Unit, onChoose: (String, Int) -> Unit) {
    val chips = remember(context) { UiReasonChips.chipsFor(context, dev.fitnessapp.app.uidet.ExerciseFlags(emptyList()), c.reasons, c.params.fork()).value }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(SheetPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (chips.visible + chips.more).forEach { chip ->
                    ChipButton(chip.label, selected = false, onClick = { onChoose(chip.code, chip.position) }, role = Role.Button)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OccupiedSheet(name: String, canDoLater: Boolean, onDismiss: () -> Unit, onDoLater: () -> Unit, onSkip: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(SheetPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$name is occupied", style = MaterialTheme.typography.titleLarge)
            if (canDoLater) PrimaryButton("Do later", onDoLater, Modifier.fillMaxWidth())
            TonalButton("Skip anyway", onSkip, Modifier.fillMaxWidth())
        }
    }
}

/** UX 16.13: the remaining exercises and a reason (no time preselected). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FinishSheet(c: AppContainer, remaining: List<String>, onDismiss: () -> Unit, onFinish: (String) -> Unit) {
    val chips = remember { UiReasonChips.chipsFor(ChipContext.SESSION_END, dev.fitnessapp.app.uidet.ExerciseFlags(emptyList()), c.reasons, c.params.fork()).value }
    var reason by remember { mutableStateOf(chips.preselected ?: "no_time") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(SheetPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Not done yet", style = MaterialTheme.typography.titleLarge)
            Tile(color = Fit.Raised, radius = 18.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), spacing = 4.dp) {
                remaining.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.visible.forEach { chip -> ChipButton(chip.label, selected = reason == chip.code, onClick = { reason = chip.code }) }
            }
            PrimaryButton("Finish", { onFinish(reason) }, Modifier.fillMaxWidth())
            TonalButton("Keep going", onDismiss, Modifier.fillMaxWidth())
        }
    }
}

/** UX 16.2: location, side, severity (mild 2 / moderate 5 / sharp 8, C64). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PainSheet(onDismiss: () -> Unit, preselectedRegion: String? = null, onSubmit: (String, String, String, List<String>) -> Unit) {
    val regions = PAIN_REGIONS
    var region by remember { mutableStateOf(preselectedRegion) }
    var side by remember { mutableStateOf("none") }
    var severity by remember { mutableStateOf<String?>(null) }
    var symptoms by remember { mutableStateOf(emptySet<String>()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(SheetPadding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Where does it hurt?", style = MaterialTheme.typography.titleLarge)
            ChoiceChips(regions.map { it.first }, region, { code -> regions.first { it.first == code }.second }) { region = it }
            SectionLabel("Side")
            ChoiceChips(listOf("L", "R", "both", "none"), side, { mapOf("L" to "Left", "R" to "Right", "both" to "Both", "none" to "No side")[it]!! }) { side = it }
            SectionLabel("How bad")
            ChoiceChips(listOf("mild", "moderate", "sharp"), severity, { it.replaceFirstChar(Char::uppercase) }) { severity = it }
            // The kind of pain (DATA_MODEL 9.1.6): the elbow kinds are the challenge's stop signals (C33).
            val kinds = PAIN_KINDS.filter { (_, regions, _) -> regions.isEmpty() || region in regions }
            if (kinds.isNotEmpty()) {
                SectionLabel("What kind")
                kinds.forEach { (code, _, label) ->
                    ChipButton(label, code in symptoms, { symptoms = if (code in symptoms) symptoms - code else symptoms + code }, role = androidx.compose.ui.semantics.Role.Checkbox)
                }
            }
            Banner("Not medical advice. If something hurts, stop and get it checked.", BannerKind.SAFETY, Modifier.padding(top = 8.dp))
            PrimaryButton("Save", { onSubmit(region!!, side, severity!!, symptoms.toList()) }, Modifier.fillMaxWidth().padding(top = 4.dp), enabled = region != null && severity != null)
        }
    }
}

/**
 * "Stack setup" (D43): this machine's stack as marked on it, in kg or lb. Switching the unit restarts the steps at the
 * unit's default and keeps the heaviest at the same kg, floored to the step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StackSheet(name: String, stack: LoadScheme.Stack, onDismiss: () -> Unit, onSave: (unit: String, start: Double, step: Double, max: Double) -> Unit) {
    var unit by remember { mutableStateOf(stack.unit) }
    var step by remember { mutableStateOf(Numbers.num(stack.step)) }
    var start by remember { mutableStateOf(Numbers.num(stack.start)) }
    var max by remember { mutableStateOf(Numbers.num(stack.max)) }
    fun parse(t: String) = t.replace(',', '.').toDoubleOrNull()
    val stepV = parse(step)?.takeIf { it > 0 }
    val startV = parse(start)?.takeIf { it >= 0 }
    val maxV = parse(max)?.takeIf { m -> startV != null && m >= startV }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(SheetPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Stack setup · $name", style = MaterialTheme.typography.titleLarge)
            Text("As marked on this machine. Only this machine changes.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            ChoiceChips(listOf("lb", "kg"), unit, { it }) { u ->
                if (u != unit) {
                    val maxKg = (parse(max) ?: stack.max) * Checklist.unitKg(unit)
                    val d = Checklist.defaultStackStep(u)
                    unit = u
                    step = Numbers.num(d)
                    start = Numbers.num(d)
                    max = Numbers.num(kotlin.math.floor(maxKg / Checklist.unitKg(u) / d + 1e-9) * d)
                }
            }
            listOf(Triple("Step", step) { t: String -> step = t }, Triple("Lightest", start) { t: String -> start = t }, Triple("Heaviest", max) { t: String -> max = t })
                .forEach { (label, value, set) ->
                    FitTextField(
                        value = value, onValueChange = set, label = "$label ($unit)", singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                    )
                }
            if (unit == "lb" && stepV != null) {
                Text("${Numbers.num(stepV)} lb ≈ ${Numbers.oneDecimal(stepV * Checklist.LB_KG)} kg; the set card shows both.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
            }
            PrimaryButton("Save", { onSave(unit, startV!!, stepV!!, maxV!!) }, Modifier.fillMaxWidth(), enabled = stepV != null && startV != null && maxV != null)
            TonalButton("Cancel", onDismiss, Modifier.fillMaxWidth())
        }
    }
}

/** UX 16.7: type a value; chips with the nearest lower and higher loadable; "Set" snaps (ties to easier). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeypadSheet(card: Card, vm: LiveSessionViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val typed = text.replace(',', '.').toDoubleOrNull()
    val (lo, hi) = remember(typed) { typed?.let { vm.keypadChips(it) } ?: (null to null) }
    val unit = if (card.mode == LoadMode.ASSISTED_MACHINE) "pin, lb" else card.point?.nativeUnit ?: "kg"
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(SheetPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Type a load", style = MaterialTheme.typography.titleLarge)
            FitTextField(
                value = text, onValueChange = { text = it }, label = "Load ($unit)", singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull(lo, hi).distinct().forEach { p ->
                    TonalButton(Numbers.loadMain(p, card.mode), { vm.keypad(p.assistPinNative ?: p.nativeValue ?: 0.0); onDismiss() })
                }
            }
            PrimaryButton("Set", { typed?.let(vm::keypad); onDismiss() }, Modifier.fillMaxWidth(), enabled = typed != null)
        }
    }
}

/** Body regions of the Pain sheet (UX 16.2): code and label. */
internal val PAIN_REGIONS = listOf(
    "elbow_medial" to "Elbow, inner side", "elbow_lateral" to "Elbow, outer side", "elbow_anterior" to "Elbow crease",
    "elbow_posterior" to "Elbow, back", "forearm" to "Forearm",
    "shoulder" to "Shoulder", "wrist" to "Wrist", "hand_fingers" to "Hand and fingers", "neck" to "Neck", "upper_back" to "Upper back",
    "lower_back" to "Lower back", "hip" to "Hip", "knee" to "Knee", "ankle_foot" to "Ankle and foot", "other" to "Other",
)

/** The readable label of a pain region code, for lines that show a saved report. */
internal fun painRegionLabel(code: String): String = PAIN_REGIONS.firstOrNull { it.first == code }?.second ?: code.replace('_', ' ')

/** `symptom_codes` (DATA_MODEL 9.1.6): code, the body regions it belongs to (empty = any), label. */
internal val PAIN_KINDS = listOf(
    Triple("medial_elbow_ache_after", listOf("elbow_medial"), "Ache on the inner elbow afterwards"),
    Triple("elbow_crease_pain_on_extension", listOf("elbow_anterior"), "Pain in the elbow crease when straightening"),
    Triple("ulnar_tingling", listOf("elbow_medial", "hand_fingers"), "Tingling in the ring and little finger"),
    Triple("sharp_pain_during", emptyList(), "Sharp pain during the set"),
    Triple("dull_ache_during", emptyList(), "Dull ache during the set"),
    Triple("ache_after", emptyList(), "Ache afterwards"),
    Triple("stiffness", emptyList(), "Stiffness"),
    Triple("swelling", emptyList(), "Swelling"),
)
