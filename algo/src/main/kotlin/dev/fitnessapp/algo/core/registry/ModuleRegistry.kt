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
     * first in its week (D38, ENGINE_VERSION 1.2.0); the setup's decisions fold with the creation (D40, ENGINE_VERSION 1.3.0); the comparison baseline per measurement (D41, ENGINE_VERSION 1.4.0).
     */
    private val BUMPED: Map<String, SemVer> = mapOf(
        "challenge.cds" to SemVer(1, 2, 0),
        "challenge.materialize" to SemVer(1, 1, 0),
        "challenge.plan" to SemVer(1, 1, 0),
        "challenge.enrollment" to SemVer(1, 2, 0),
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
