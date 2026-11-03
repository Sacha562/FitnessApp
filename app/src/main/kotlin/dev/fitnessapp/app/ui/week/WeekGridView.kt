package dev.fitnessapp.app.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.uidet.BlockLook
import dev.fitnessapp.app.uidet.GridBlock
import dev.fitnessapp.app.uidet.WeekGrid
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Hour height of the Week grid. */
private val HOUR = 48.dp
private val GUTTER = 30.dp
private val HEADER = 46.dp

/** Minutes a drag snaps to, and the step a tap on an empty spot rounds down to (D84). */
private const val DRAG_STEP_MIN = 15
private const val TAP_STEP_MIN = 30

/** A block being dragged: its day column and the finger's travel since the long press, in pixels. */
private data class Drag(val block: GridBlock, val day: Int, val dx: Float, val dy: Float)

/**
 * The Week tab's calendar grid (D83, D84): a time gutter and the seven days of the week side by side. Tapping a header
 * or a block opens that day ([onOpenDay]); tapping an empty spot of a day asks for a session there ([onTapSlot], minutes
 * after midnight, rounded down to the half hour); a long press lifts a movable block, which follows the finger in
 * 15-minute steps and across days and is dropped with [onMove]. [nowMin] draws the current-time line in today's column.
 * [pending] holds dropped blocks (key -> day and start minute) at their drop place until the reloaded week shows them
 * there, so a block never jumps back while its move is written (D84 (2)).
 */
@Composable
fun WeekGridView(
    grid: WeekGrid,
    nowMin: Int?,
    onOpenDay: (LocalDate) -> Unit,
    onTapSlot: (LocalDate, Int) -> Unit,
    onMove: (GridBlock, LocalDate, Int) -> Unit,
    modifier: Modifier = Modifier,
    pending: Map<String, Pair<LocalDate, Int>> = emptyMap(),
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val dayW = (maxWidth - GUTTER) / 7
        val hours = grid.endHour - grid.startHour
        val density = LocalDensity.current
        val haptics = LocalHapticFeedback.current
        val hourPx = with(density) { HOUR.toPx() }
        val dayPx = with(density) { dayW.toPx() }
        var drag by remember { mutableStateOf<Drag?>(null) }

        /** Where a drag lands: the day column and the start minute, snapped and kept inside the visible hours. */
        fun target(d: Drag): Pair<Int, Int> {
            val day = (d.day + (d.dx / dayPx).roundToInt()).coerceIn(0, 6)
            val dur = d.block.endMin - d.block.startMin
            val raw = d.block.startMin + d.dy / hourPx * 60f
            val snapped = (raw / DRAG_STEP_MIN).roundToInt() * DRAG_STEP_MIN
            return day to snapped.coerceIn(grid.startHour * 60, grid.endHour * 60 - dur)
        }

        Row {
            Column(Modifier.width(GUTTER)) {
                Box(Modifier.height(HEADER))
                for (h in grid.startHour until grid.endHour) {
                    Box(Modifier.height(HOUR)) {
                        if (h > grid.startHour) {
                            Text(
                                "%02d".format(Locale.ROOT, h), style = MaterialTheme.typography.labelSmall, color = Fit.Muted,
                                modifier = Modifier.offset(y = (-7).dp).padding(start = 2.dp),
                            )
                        }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.height(HEADER)) {
                    grid.days.forEach { d -> DayHeader(d.date, d.today, d.blocked, dayW) { onOpenDay(d.date) } }
                }
                Box(Modifier.height(HOUR * hours).fillMaxWidth()) {
                    // The empty grid under the blocks: a tap there plans a session at that spot (D84 (1)).
                    Box(
                        Modifier.fillMaxSize().pointerInput(grid) {
                            detectTapGestures { p ->
                                val day = (p.x / dayPx).toInt().coerceIn(0, 6)
                                val minute = grid.startHour * 60 + ((p.y / hourPx * 60f).toInt() / TAP_STEP_MIN) * TAP_STEP_MIN
                                onTapSlot(grid.days[day].date, minute)
                            }
                        },
                    )
                    for (h in 0 until hours) {
                        Box(Modifier.offset(y = HOUR * h).fillMaxWidth().height(1.dp).background(Fit.Line))
                    }
                    grid.days.forEachIndexed { i, d ->
                        if (d.blocked) Box(Modifier.offset(x = dayW * i).width(dayW).fillMaxHeight().background(Fit.Raised.copy(alpha = 0.35f)))
                        Box(Modifier.offset(x = dayW * i).width(1.dp).fillMaxHeight().background(Fit.Line.copy(alpha = 0.6f)))
                    }
                    grid.days.forEachIndexed { i, d ->
                        d.blocks.filter { it.key !in pending }.forEach { b ->
                            val laneW = (dayW - 3.dp) / b.lanes
                            val top = HOUR * ((b.startMin - grid.startHour * 60) / 60f)
                            val height = HOUR * ((b.endMin - b.startMin) / 60f)
                            val lifted = drag?.block?.key == b.key
                            Block(
                                b,
                                modifier = Modifier.offset(x = dayW * i + 2.dp + laneW * b.lane, y = top + 1.dp).width(laneW - 1.dp).height(height - 2.dp)
                                    .alpha(if (lifted) 0.3f else 1f)
                                    .then(
                                        if (b.movable) Modifier.pointerInput(b.key, b.startMin, i) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    drag = Drag(b, i, 0f, 0f)
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    drag = drag?.let { it.copy(dx = it.dx + amount.x, dy = it.dy + amount.y) }
                                                },
                                                onDragEnd = {
                                                    drag?.let { dr ->
                                                        val (day, minute) = target(dr)
                                                        if (day != dr.day || minute != dr.block.startMin) onMove(dr.block, grid.days[day].date, minute)
                                                    }
                                                    drag = null
                                                },
                                                onDragCancel = { drag = null },
                                            )
                                        } else Modifier,
                                    ),
                                onClick = { onOpenDay(d.date) },
                            )
                        }
                        if (d.today && nowMin != null && nowMin in grid.startHour * 60 until grid.endHour * 60) {
                            val y = HOUR * ((nowMin - grid.startHour * 60) / 60f)
                            Box(Modifier.offset(x = dayW * i, y = y - 1.dp).width(dayW).height(2.dp).background(Fit.Safety))
                            Box(Modifier.offset(x = dayW * i - 4.dp, y = y - 4.dp).size(8.dp).clip(CircleShape).background(Fit.Safety))
                        }
                    }
                    // Dropped blocks at their drop place while the move is written and the week reloads.
                    pending.forEach { (key, at) ->
                        val b = grid.days.flatMap { it.blocks }.firstOrNull { it.key == key } ?: return@forEach
                        val day = grid.days.indexOfFirst { it.date == at.first }.takeIf { it >= 0 } ?: return@forEach
                        val dur = b.endMin - b.startMin
                        val moved = b.copy(startMin = at.second, endMin = at.second + dur, time = "%02d:%02d".format(Locale.ROOT, at.second / 60, at.second % 60))
                        Block(
                            moved,
                            modifier = Modifier.offset(x = dayW * day + 2.dp, y = HOUR * ((at.second - grid.startHour * 60) / 60f) + 1.dp)
                                .width(dayW - 4.dp).height(HOUR * (dur / 60f) - 2.dp),
                            onClick = {},
                        )
                    }
                    // The lifted block where it would land, with its new day and time.
                    drag?.let { dr ->
                        val (day, minute) = target(dr)
                        val b = dr.block
                        val dur = b.endMin - b.startMin
                        val ghost = b.copy(startMin = minute, endMin = minute + dur, time = "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60))
                        Block(
                            ghost,
                            modifier = Modifier.offset(x = dayW * day + 1.dp, y = HOUR * ((minute - grid.startHour * 60) / 60f))
                                .width(dayW - 2.dp).height(HOUR * (dur / 60f)).shadow(8.dp, RoundedCornerShape(6.dp))
                                .border(2.dp, Fit.Ink, RoundedCornerShape(6.dp)),
                            onClick = {},
                            showTime = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, today: Boolean, blocked: Boolean, width: Dp, onClick: () -> Unit) {
    val name = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH))
    Column(
        Modifier.width(width).fillMaxHeight().clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = name + (if (today) ", today" else "") + (if (blocked) ", rest day" else "") },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(
            date.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)),
            style = MaterialTheme.typography.labelSmall, color = if (today) Fit.Accent else Fit.Muted, maxLines = 1,
        )
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (today) Fit.Accent else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall, color = if (today) Fit.OnAccent else if (blocked) Fit.Muted else Fit.Ink)
        }
    }
}

/** One block: gym filled in the accent, suggested outlined, other events muted in the background colour (D83 (2)). */
@Composable
private fun Block(b: GridBlock, modifier: Modifier, onClick: () -> Unit, showTime: Boolean = b.look != BlockLook.OTHER) {
    val shape = RoundedCornerShape(6.dp)
    val (fill, ink) = when (b.look) {
        BlockLook.GYM -> (if (b.done) Fit.Accent.copy(alpha = 0.72f) else Fit.Accent) to Fit.OnAccent
        BlockLook.SUGGESTED -> Fit.Accent.copy(alpha = 0.14f) to Fit.Accent
        BlockLook.OTHER -> Fit.Raised to Fit.Muted
    }
    val outline = Fit.Accent
    val label = if (b.look == BlockLook.OTHER) b.title else b.short
    Box(
        modifier.clip(shape).background(fill)
            .then(
                if (b.look == BlockLook.SUGGESTED) Modifier.drawBehind {
                    val w = 1.5.dp.toPx()
                    drawRoundRect(
                        outline, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), cornerRadius = CornerRadius(6.dp.toPx()),
                        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                    )
                } else Modifier,
            )
            // Other events are background (D84 follow-up): a tap on them does nothing, and does not reach the empty grid.
            .then(if (b.look == BlockLook.OTHER) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier.clickable(onClick = onClick))
            .semantics(mergeDescendants = true) {
                contentDescription = when (b.look) {
                    BlockLook.GYM -> "Gym: ${b.title} at ${b.time}" + (if (b.done) ", done" else "") + if (b.movable) ", long press to move" else ""
                    BlockLook.SUGGESTED -> "Suggested: ${b.title} at ${b.time}, long press to move and plan it"
                    BlockLook.OTHER -> "${b.title} at ${b.time}"
                }
            }
            .padding(horizontal = 3.dp, vertical = 3.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (b.done) Icon(Icons.Rounded.Check, null, tint = ink, modifier = Modifier.size(12.dp).padding(end = 2.dp))
                Text(
                    label, color = ink, fontSize = 11.sp, lineHeight = 13.sp, style = MaterialTheme.typography.labelMedium,
                    maxLines = if (b.look == BlockLook.OTHER) 2 else 3, overflow = TextOverflow.Ellipsis,
                )
            }
            if (showTime) Text(b.time, color = ink.copy(alpha = 0.8f), fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
        }
    }
}
