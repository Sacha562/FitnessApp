package dev.fitnessapp.algo.core.registry

/** Registry module id (E49), e.g. "rx" or "calendar.classify". */
@JvmInline
value class ModuleId(val value: String) {
    init {
        require(PATTERN.matches(value)) { "invalid module id: $value" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("^[a-z]+(\\.[a-z]+)*$")
    }
}

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "negative semver part" }
    }

    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")

        fun parse(text: String): SemVer {
            val m = requireNotNull(PATTERN.matchEntire(text)) { "invalid semver: $text" }
            return SemVer(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
    }
}

/** Rendered `id@semver`, e.g. "rx@1.0.0" (C30). */
data class ModuleRef(val id: ModuleId, val version: SemVer) {
    override fun toString(): String = "$id@$version"
}

enum class Layer { CORE, CHALLENGE, ACCOUNTING, ESTIMATION, TARGETS, PLANNING, SELECTION, PRESCRIPTION, UI, OFFLINE }

/**
 * The 51 registry modules (DATA_MODEL 9.1.9, C30). Every module starts at 1.0.0; a module's version moves
 * here ([BUMPED]) when its behaviour changes (ALGORITHMS.md A7), together with ENGINE_VERSION.
 */
object ModuleRegistry {
    data class Entry(val ref: ModuleRef, val layer: Layer)

    private val V1 = SemVer(1, 0, 0)

    /**
     * Modules past 1.0.0 (ALGORITHMS.md A7): structured test protocols (D36, ENGINE_VERSION 1.1.0) and a slot that goes
     * first in its week (D38, ENGINE_VERSION 1.2.0); the setup's decisions fold with the creation (D40, ENGINE_VERSION 1.3.0); the comparison baseline per measurement (D41, ENGINE_VERSION 1.4.0); train now tries every slot not done yet, at any time of day (D42, ENGINE_VERSION 1.5.0); definition updates of a running enrollment (D44, ENGINE_VERSION 1.6.0); a creation that corrects the imported earlier tests (D48, ENGINE_VERSION 1.7.0); REST-1's modifiers, pair and superset rests, readout and adaptation (M3.3, ENGINE_VERSION 1.8.0); the rest multiplier, also in RX-1's inputs (D50, ENGINE_VERSION 1.9.0); no WE-V1 in a deload week (D61, ENGINE_VERSION 1.10.0); SUB-1's "Do {k} now" in ORD-1 order with busy stations ranked last (D62, ENGINE_VERSION 1.11.0); a plate-loaded machine's own mass counted outside the shown load (D68, ENGINE_VERSION 1.12.0); the next set's target refitted to the last set and the actual rest, and RANGE rep proposals across the whole range (D69, ENGINE_VERSION 1.13.0); the re-entry reason names the group with the most direct sets, shown as "Lighter start for {group}" (D71, ENGINE_VERSION 1.14.0); H6 and H1 see the previous week's general sessions (D74, ENGINE_VERSION 1.15.0); no free slot is a soft rest with options from now (D76, ENGINE_VERSION 1.16.0); a free day's start chosen among candidates every START_STEP_MIN (D77, ENGINE_VERSION 1.17.0).
     */
    private val BUMPED: Map<String, SemVer> = mapOf(
        "autoreg" to SemVer(1, 1, 0),
        "challenge.cds" to SemVer(1, 2, 0),
        "challenge.materialize" to SemVer(1, 1, 0),
        "challenge.plan" to SemVer(1, 2, 0),
        "challenge.enrollment" to SemVer(1, 4, 0),
        "load" to SemVer(1, 1, 0),
        "rest" to SemVer(1, 2, 0),
        "rx" to SemVer(1, 1, 0),
        "substitute" to SemVer(1, 1, 0),
        "today" to SemVer(1, 4, 0),
        "ui.todaycard" to SemVer(1, 1, 0),
        "warnings" to SemVer(1, 1, 0),
        "weekplan" to SemVer(1, 2, 0),
    )

    private fun layer(layer: Layer, vararg ids: String): List<Entry> =
        ids.map { Entry(ModuleRef(ModuleId(it), BUMPED[it] ?: V1), layer) }

    val entries: List<Entry> = listOf(
        layer(Layer.CORE, "clock", "load", "bodyweight", "calendar.classify", "safety"),
        layer(
            Layer.CHALLENGE, "challenge.cds", "challenge.fitexpr", "challenge.enrollment", "challenge.eca",
            "challenge.materialize", "challenge.plan", "challenge.contract", "challenge.calendar",
        ),
        layer(Layer.ACCOUNTING, "setcredit", "readiness"),
        layer(Layer.ESTIMATION, "rircal", "rcm", "sse", "fatigue", "hold", "stall"),
        layer(Layer.TARGETS, "targets", "runs"),
        layer(Layer.PLANNING, "allocator", "today", "weekplan"),
        layer(Layer.SELECTION, "catalog", "pref", "elig", "select", "order", "substitute", "rotate", "time", "injury"),
        layer(Layer.PRESCRIPTION, "rx", "warmup", "rest", "reason", "autoreg", "warnings"),
        layer(
            Layer.UI, "ui.todaycard", "ui.prefill", "ui.reasonchips", "ui.prompts", "ui.volumebars",
            "ui.warndisplay", "ui.progress",
        ),
        layer(Layer.OFFLINE, "tuner", "sim", "replay"),
    ).flatten()

    fun ref(id: String): ModuleRef =
        requireNotNull(entries.firstOrNull { it.ref.id.value == id }) { "unknown module id: $id" }.ref
}
