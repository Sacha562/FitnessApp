package dev.fitnessapp.app.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fitnessapp.algo.planning.weekplan.PlanItem
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.uidet.BlockLook
import dev.fitnessapp.app.uidet.GridBlock
import dev.fitnessapp.app.uidet.WeekGrid
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Hour height of the Week grid. */
private val HOUR = 48.dp
private val GUTTER = 30.dp
private val HEADER = 46.dp

/** A column at least this wide (5 or 3 days) shows full labels and the one-tap "Plan it" on suggested blocks (D83 (3), (4)). */
private val WIDE = 64.dp

/** A column this wide (3 days) also gives a suggested block its full label beside the plus. */
private val ROOMY = 88.dp

/**
 * The Week tab's calendar grid (D83): a time gutter, then the days side by side, [daysVisible] of them on the screen; with
 * fewer than 7 the days scroll sideways and open at [scrollTo]. [nowMin] draws the current-time line in today's column.
 */
@Composable
fun WeekGridView(
    grid: WeekGrid,
    daysVisible: Int,
    scrollTo: LocalDate?,
    nowMin: Int?,
    busy: Boolean,
    onOpenDay: (LocalDate) -> Unit,
    onPlanIt: (PlanItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val dayW = (maxWidth - GUTTER) / daysVisible
        val hours = grid.endHour - grid.startHour
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        LaunchedEffect(daysVisible, grid.days.first().date, scrollTo) {
            val idx = grid.days.indexOfFirst { it.date == scrollTo }.takeIf { it >= 0 } ?: 0
            // Today as the first column, unless that runs past Sunday.
            val first = idx.coerceAtMost(7 - daysVisible)
            scroll.scrollTo(with(density) { (dayW * first).roundToPx() })
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
            Box(Modifier.weight(1f).horizontalScroll(scroll, enabled = daysVisible < 7)) {
                Column(Modifier.width(dayW * 7)) {
                    Row(Modifier.height(HEADER)) {
                        grid.days.forEach { d -> DayHeader(d.date, d.today, d.blocked, dayW) { onOpenDay(d.date) } }
                    }
                    Box(Modifier.height(HOUR * hours).fillMaxWidth()) {
                        // Hour lines and day dividers.
                        for (h in 0 until hours) {
                            Box(Modifier.offset(y = HOUR * h).fillMaxWidth().height(1.dp).background(Fit.Line))
                        }
                        grid.days.forEachIndexed { i, d ->
                            Box(
                                Modifier.offset(x = dayW * i).width(dayW).fillMaxHeight()
                                    .background(if (d.blocked) Fit.Raised.copy(alpha = 0.35f) else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable { onOpenDay(d.date) },
                            )
                            Box(Modifier.offset(x = dayW * i).width(1.dp).fillMaxHeight().background(Fit.Line.copy(alpha = 0.6f)))
                            d.blocks.forEach { b ->
                                val laneW = (dayW - 3.dp) / b.lanes
                                val top = HOUR * ((b.startMin - grid.startHour * 60) / 60f)
                                val height = HOUR * ((b.endMin - b.startMin) / 60f)
                                Block(
                                    b, wide = dayW >= WIDE, roomy = dayW >= ROOMY, busy = busy,
                                    modifier = Modifier.offset(x = dayW * i + 2.dp + laneW * b.lane, y = top + 1.dp).width(laneW - 1.dp).height(height - 2.dp),
                                    onClick = { onOpenDay(d.date) }, onPlanIt = onPlanIt,
                                )
                            }
                            if (d.today && nowMin != null && nowMin in grid.startHour * 60 until grid.endHour * 60) {
                                val y = HOUR * ((nowMin - grid.startHour * 60) / 60f)
                                Box(Modifier.offset(x = dayW * i, y = y - 1.dp).width(dayW).height(2.dp).background(Fit.Safety))
                                Box(Modifier.offset(x = dayW * i - 4.dp, y = y - 4.dp).size(8.dp).clip(CircleShape).background(Fit.Safety))
                            }
                        }
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
            Modifier.size(26.dp).clip(CircleShape).background(if (today) Fit.Accent else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall, color = if (today) Fit.OnAccent else if (blocked) Fit.Muted else Fit.Ink)
        }
    }
}

/** One block: gym filled in the accent, suggested outlined, other events muted in the background colour (D83 (2)). */
@Composable
private fun Block(b: GridBlock, wide: Boolean, roomy: Boolean, busy: Boolean, modifier: Modifier, onClick: () -> Unit, onPlanIt: (PlanItem) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    val (fill, ink) = when (b.look) {
        BlockLook.GYM -> (if (b.done) Fit.Accent.copy(alpha = 0.72f) else Fit.Accent) to Fit.OnAccent
        BlockLook.SUGGESTED -> Fit.Accent.copy(alpha = 0.14f) to Fit.Accent
        BlockLook.OTHER -> Fit.Raised to Fit.Muted
    }
    val outline = Fit.Accent
    val label = when (b.look) {
        BlockLook.OTHER -> b.title
        BlockLook.GYM -> if (wide) b.title else b.short
        BlockLook.SUGGESTED -> if (roomy) b.title else b.short
    }
    Box(
        modifier.clip(shape).background(fill)
            .then(
                if (b.look == BlockLook.SUGGESTED) Modifier.drawBehind {
                    val w = 1.5.dp.toPx()
                    drawRoundRect(
                        outline, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), cornerRadius = CornerRadius(6.dp.toPx()),
                        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                    )
                } else Modifier,
            )
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = when (b.look) {
                    BlockLook.GYM -> "Gym: ${b.title} at ${b.time}" + if (b.done) ", done" else ""
                    BlockLook.SUGGESTED -> "Suggested: ${b.title} at ${b.time}"
                    BlockLook.OTHER -> "${b.title} at ${b.time}"
                }
            }
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (b.done) Icon(Icons.Rounded.Check, null, tint = ink, modifier = Modifier.size(12.dp).padding(end = 2.dp))
                Text(
                    label, color = ink, fontSize = if (wide) 12.sp else 11.sp, lineHeight = 13.sp,
                    style = MaterialTheme.typography.labelMedium, maxLines = if (b.look == BlockLook.OTHER) 2 else 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val item = b.planIt
                if (wide && item != null) {
                    // One-tap "Plan it" (D83 (4)): a small plus in the block's corner.
                    Box(
                        Modifier.padding(start = 3.dp).size(22.dp).clip(CircleShape).background(Fit.Accent)
                            .clickable(enabled = !busy) { onPlanIt(item) }
                            .semantics { contentDescription = "Plan it: add ${b.title} to your calendar" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Fit.OnAccent, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (wide || b.look != BlockLook.OTHER) Text(b.time, color = ink.copy(alpha = 0.8f), fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
            if (wide) b.sub?.let { Text(it, color = ink.copy(alpha = 0.85f), fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}
