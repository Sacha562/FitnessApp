package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.params.ParamView

/**
 * One muscle group's week for the bars: [status] from `targets` (`improve`, `maintain`, `skip`, `covered`), done and
 * planned Vhyp from `setcredit` (planned = committed challenge coverage + planned general sessions), the effective
 * [target] (the deload target in a deload week) and the [normalTarget], MRV, and whether the WarningEngine reports a
 * WARN or STRONG volume deficit for the group.
 */
data class VolumeGroup(
    val group: String,
    val status: String,
    val focus: Boolean,
    val done: Double,
    val planned: Double,
    val target: Double,
    val normalTarget: Double,
    val mrv: Double,
    val deficit: Boolean,
)

enum class VolumeState { DEFICIT, OK, OVER }

/**
 * One bar row (UX 10.8, 19.5): fills as shares of the target capped at `ui.volumebars.bar_cap`, the MRV marker and the
 * normal target's faint tick on the same scale (null when not drawn), the owner's state and the `!` flag.
 */
data class VolumeRow(
    val group: String,
    val goal: String,
    val focus: Boolean,
    val done: Double,
    val planned: Double,
    val target: Double,
    val normalTarget: Double,
    val mrv: Double,
    val fillDone: Double,
    val fillPlanned: Double,
    val mrvMark: Double?,
    val normalMark: Double?,
    val state: VolumeState,
    val text: String,
) {
    val flag: Boolean get() = state != VolumeState.OK
}

/** The Week tab's volume section: bar rows in the owner's order, covered groups (no bar), the skip line's groups. */
data class VolumeBars(val rows: List<VolumeRow>, val covered: List<VolumeGroup>, val skipped: List<String>)

/**
 * `ui.volumebars` (UI-09, ALGORITHMS_PLANNING 19.4): presents `setcredit` and `targets`; never counts sets. Covered
 * groups get no bar (challenge volume is exempt from MRV warnings, D6), skip groups collapse into one line, and the
 * DEFICIT state comes only from the WarningEngine, never from a bar threshold.
 */
object UiVolumeBars {
    fun bars(groups: List<VolumeGroup>, params: ParamView): VolumeBars {
        val cap = params.double("ui.volumebars.bar_cap")
        val byGroup = groups.associateBy { it.group }
        val order = Groups.CANONICAL.filter { it in byGroup } + groups.map { it.group }.filter { it !in Groups.CANONICAL }.sorted()
        val rows = ArrayList<VolumeRow>()
        val covered = ArrayList<VolumeGroup>()
        val skipped = ArrayList<String>()
        for (g in order) {
            val v = byGroup.getValue(g)
            when (v.status) {
                "covered" -> covered += v
                "skip" -> skipped += g
                else -> rows += row(v, cap)
            }
        }
        val rank = order.withIndex().associate { it.value to it.index }
        val sorted = rows.sortedWith(
            compareBy<VolumeRow> { if (it.goal == "improve") 0 else 1 }
                .thenBy { if (it.target > 0.0) Grid.ratio((it.done + it.planned) / it.target) else Double.MAX_VALUE }
                .thenBy { rank.getValue(it.group) },
        )
        return VolumeBars(sorted, covered, skipped)
    }

    private fun row(v: VolumeGroup, cap: Double): VolumeRow {
        val tgt = v.target
        fun share(x: Double) = if (tgt > 0.0) Grid.ratio(minOf(x / tgt, cap)) else if (x > 0.0) cap else 0.0
        val fillDone = share(v.done)
        val fillPlanned = Grid.ratio(share(v.done + v.planned) - fillDone)
        val state = when {
            v.deficit -> VolumeState.DEFICIT
            v.done + v.planned > v.mrv -> VolumeState.OVER
            else -> VolumeState.OK
        }
        return VolumeRow(
            v.group, v.status, v.focus, v.done, v.planned, tgt, v.normalTarget, v.mrv, fillDone, fillPlanned,
            mrvMark = if (tgt > 0.0 && v.mrv / tgt <= cap) Grid.ratio(v.mrv / tgt) else null,
            normalMark = if (tgt > 0.0 && v.normalTarget != tgt) share(v.normalTarget) else null,
            state = state, text = text(v.done, v.planned, tgt),
        )
    }

    /** "9.5 (+3) / 12": one decimal, ".0" dropped (UX 5); "(+planned)" only when something is planned. */
    fun text(done: Double, planned: Double, target: Double): String =
        num(done) + (if (planned > 0.0) " (+" + num(planned) + ")" else "") + " / " + num(target)

    /** One decimal on the canonical 0.1 grid (the S grid of `core.det`). */
    private fun num(x: Double): String = Grid.decimal(x, GridKind.S).stripTrailingZeros().let {
        if (it.signum() == 0) "0" else it.toPlainString()
    }
}
