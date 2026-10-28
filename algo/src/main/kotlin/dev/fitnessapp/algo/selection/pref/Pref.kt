package dev.fitnessapp.algo.selection.pref

import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import java.time.LocalDate

/** The five preference states of C26, in the order the segmented control shows them reversed. */
enum class PrefTier(val code: String) {
    NEVER("never"), DISLIKE("dislike"), NEUTRAL("neutral"), LIKE("like"), FAVORITE("favorite");

    companion object {
        fun of(code: String): PrefTier = entries.first { it.code == code }
    }
}

/** The behaviour events PREF-1 reads (11.2, DATA_MODEL 9.8.1 names). */
enum class PrefEventKind { ADDED, SWAPPED_AWAY, SKIPPED }

/** One event for an exercise: its kind, the reason code (null for a manual add) and the local date. */
data class PrefEvent(val kind: PrefEventKind, val reason: String?, val date: LocalDate)

/** The user's explicit row (`user_exercise_prefs`): null tier = no preference stored, only an unavailable date. */
data class PrefRow(val tier: PrefTier?, val unavailableUntil: LocalDate? = null)

/**
 * PREF-1's output per exercise (11.2): the multiplier `m`, the tier (implicit signals never change it, so they have
 * no gate effect), `hidden` while marked unavailable, the implicit score `z` (0 with an explicit row) and the prompt.
 */
data class PrefValue(val m: Double, val tier: PrefTier, val hidden: Boolean, val z: Double, val explicit: Boolean, val prompt: Boolean)

/**
 * PREF-1 (ALGORITHMS_PLANNING 11, C26, D53): the 5-state preference as a value multiplier; without an explicit row, a
 * small implicit signal from manual adds, swap-aways and `other` skips (external reasons never count); the
 * "Lower your preference?" prompt after repeated swap-aways, which never changes anything by itself; and the
 * exercises that "Apply to similar" offers.
 */
object Pref : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("pref")

    /** Reasons outside the exercise (11.3): a swap or skip for one of these says nothing about liking it. */
    val EXTERNAL_REASONS: Set<String> = setOf("occupied", "equipment_missing", "pain", "no_time", "feeling_bad", "too_tired")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.number("pref.implicit_clamp", "implicit score", 0.0, 0.5),
        ParamSpec.integer("pref.implicit_half_life_d", "days", 14.0, 180.0),
        ParamSpec.integer("pref.implicit_window_d", "days", 56.0, 365.0),
        ParamSpec.table("pref.mult", "multiplier (by preference tier)", 0.0, 2.0),
        ParamSpec.integer("pref.prompt_swaps", "swaps", 2.0, 6.0),
        ParamSpec.integer("pref.prompt_window_d", "days", 14.0, 90.0),
        ParamSpec.number("pref.similar_cos", "cosine", 0.8, 0.99),
        ParamSpec.number("pref.w_add", "implicit score per event", 0.0, 0.3),
        ParamSpec.number("pref.w_skip", "implicit score per event", 0.0, 0.3),
        ParamSpec.number("pref.w_swap", "implicit score per event", 0.0, 0.3),
    )

    fun mult(tier: PrefTier, params: ParamView): Double = params.table("pref.mult").getValue(tier.code)

    /** 11.3 for one exercise on [today]; [events] in any order (events after [today] are ignored). */
    fun evaluate(row: PrefRow?, events: List<PrefEvent>, today: LocalDate, params: ParamView): PrefValue {
        val hidden = row?.unavailableUntil?.isAfter(today) == true
        val prompt = prompt(events, today, params)
        val tier = row?.tier
        if (tier != null) return PrefValue(mult(tier, params), tier, hidden, 0.0, explicit = true, prompt = prompt)
        val window = params.int("pref.implicit_window_d")
        val halfLife = params.int("pref.implicit_half_life_d").toDouble()
        var z = 0.0
        for (ev in events.sortedWith(compareBy({ it.date }, { it.kind.ordinal }, { it.reason ?: "" }))) {
            val age = Week.calendarDays(ev.date, today)
            if (age < 0 || age >= window) continue
            val w = weight(ev, params)
            if (w != 0.0) z += w * Det.exp(-LN2 * age / halfLife)
        }
        val clamp = params.double("pref.implicit_clamp")
        z = Grid.ratio(z.coerceIn(-clamp, clamp))
        return PrefValue(Grid.ratio(mult(PrefTier.NEUTRAL, params) * (1.0 + z)), PrefTier.NEUTRAL, hidden, z, explicit = false, prompt = prompt)
    }

    private fun weight(ev: PrefEvent, params: ParamView): Double = when (ev.kind) {
        PrefEventKind.ADDED -> params.double("pref.w_add")
        PrefEventKind.SWAPPED_AWAY -> if (ev.reason in EXTERNAL_REASONS) 0.0 else -params.double("pref.w_swap")
        PrefEventKind.SKIPPED -> if (ev.reason == "other") -params.double("pref.w_skip") else 0.0
    }

    /** The prompt (11.3): at least `pref.prompt_swaps` swap-aways with a non-external reason in `pref.prompt_window_d`. */
    fun prompt(events: List<PrefEvent>, today: LocalDate, params: ParamView): Boolean {
        val window = params.int("pref.prompt_window_d")
        val n = events.count { ev ->
            ev.kind == PrefEventKind.SWAPPED_AWAY && ev.reason !in EXTERNAL_REASONS &&
                Week.calendarDays(ev.date, today).let { it >= 0 && it < window }
        }
        return n >= params.int("pref.prompt_swaps")
    }

    /**
     * "Apply to similar" (11.3): the other exercises with the same pattern and SUB-1 similarity of at least
     * `pref.similar_cos`, by slug. The caller asks the user before applying anything.
     */
    fun similar(target: CatalogExercise, catalog: Catalog, params: ParamView): List<CatalogExercise> {
        val min = params.double("pref.similar_cos")
        return catalog.exercises.filter { it.slug != target.slug && it.pattern == target.pattern && catalog.similarity(target, it) >= min }
    }

    private val LN2: Double = Det.ln(2.0)
}
