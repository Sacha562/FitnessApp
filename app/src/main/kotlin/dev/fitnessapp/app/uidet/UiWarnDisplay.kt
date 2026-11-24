package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.prescription.warnings.Severity
import dev.fitnessapp.algo.prescription.warnings.Warning
import java.time.Duration
import java.time.Instant

/** A `warning_dismissed` of one rule and entity: when, and at what magnitude (UX 19.5 snooze). */
data class Dismissal(val at: Instant, val magnitude: Double)

/** The marker next to an affected value (UX 17): a muted dot (INFO), an amber dot (WARN, STRONG) or "your choice". */
enum class WarnMarker { MUTED_DOT, AMBER_DOT, YOUR_CHOICE }

/**
 * What one screen shows (UX 17): the SAFETY notices (their own slot, persistent until acknowledged), at most one
 * advisory [banner], the "N more notes" count and the list behind it, and a marker per entity.
 */
data class WarnDisplayResult(
    val safety: List<Warning>,
    val banner: Warning?,
    val moreNotes: Int,
    val notes: List<Warning>,
    val markers: Map<String, WarnMarker>,
)

/**
 * `ui.warndisplay` (UI-10, ALGORITHMS_PLANNING 19.5): where each WarningEngine violation appears on a screen. SAFETY
 * notices are always visible; an enforced SAFETY (the engine already clamped its own output) needs no action and goes
 * to the list with a dot. INFO never makes a banner; WARN and STRONG need `ui.warndisplay.min_magnitude`, and a
 * dismissal snoozes any non-SAFETY note, out of the banner and the list, for `ui.warndisplay.snooze_d` days unless the
 * magnitude grew by `ui.warndisplay.worsen_factor`; an acknowledged SAFETY notice leaves the list as well (D126), and a
 * dismissed enforced one is snoozed like an advisory note (D131).
 */
object UiWarnDisplay {
    fun key(ruleId: String, entity: String) = "$ruleId|$entity"

    /**
     * The SAFETY notices acknowledged today, from the stored `warning_dismissed` events ([dismissed]: key and time), so a
     * rebuilt session screen (the app reopened after the phone slept) does not ask again for an OK already given.
     */
    fun acknowledgedToday(dismissed: List<Pair<String, Instant>>, now: Instant, zone: java.time.ZoneId): Set<String> {
        val today = now.atZone(zone).toLocalDate()
        return dismissed.filter { it.second.atZone(zone).toLocalDate() == today }.map { it.first }.toSortedSet()
    }

    fun display(
        warnings: List<Warning>,
        dismissals: Map<String, Dismissal>,
        useMine: Set<String>,
        acknowledged: Set<String>,
        now: Instant,
        params: ParamView,
    ): WarnDisplayResult {
        val minMag = params.double("ui.warndisplay.min_magnitude")
        val snoozeD = params.int("ui.warndisplay.snooze_d").toLong()
        val worsen = params.double("ui.warndisplay.worsen_factor")
        val safety = ArrayList<Warning>()
        val visible = ArrayList<Warning>()
        val listOnly = ArrayList<Warning>()
        val markers = sortedMapOf<String, WarnMarker>()
        fun mark(entity: String, m: WarnMarker) {
            val cur = markers[entity]
            if (cur == null || m.ordinal > cur.ordinal) markers[entity] = m
        }
        // D126: a dismissed note is snoozed out of the banner and the list alike, unless it grew by the worsen factor.
        fun snoozed(k: String, v: Warning): Boolean {
            val d = dismissals[k] ?: return false
            return Duration.between(d.at, now).toDays() < snoozeD && v.magnitude < d.magnitude * (1.0 + worsen)
        }
        for (v in warnings) {
            val k = key(v.ruleId, v.entity)
            if (v.severity == Severity.SAFETY) {
                when {
                    // D126: an acknowledged notice keeps its dot but leaves the list too (its OK is the dismiss).
                    k in acknowledged && !v.enforced -> mark(v.entity, WarnMarker.MUTED_DOT)
                    v.enforced -> {
                        mark(v.entity, WarnMarker.MUTED_DOT)
                        // D131: an enforced note is dismissable like an advisory one; the clamp itself stays.
                        if (!snoozed(k, v)) listOnly += v
                    }
                    else -> safety += v
                }
                continue
            }
            if (k in useMine) {
                mark(v.entity, WarnMarker.YOUR_CHOICE)
                continue
            }
            mark(v.entity, if (v.severity == Severity.INFO) WarnMarker.MUTED_DOT else WarnMarker.AMBER_DOT)
            // D126: INFO and small notes included.
            if (snoozed(k, v)) continue
            if (v.severity == Severity.INFO || v.magnitude < minMag) {
                listOnly += v
                continue
            }
            visible += v
        }
        val order = compareByDescending<Warning> { it.severity.rank }.thenByDescending { it.magnitude }.thenBy { it.ruleId }.thenBy { it.entity }
        visible.sortWith(order)
        val banners = params.int("ui.warndisplay.max_banners")
        val banner = visible.firstOrNull()
        val notes = (visible.drop(1) + listOnly).sortedWith(order)
        return WarnDisplayResult(
            safety = safety.sortedWith(order), banner = banner,
            moreNotes = maxOf(0, visible.size - banners) + listOnly.size, notes = notes, markers = markers,
        )
    }
}
