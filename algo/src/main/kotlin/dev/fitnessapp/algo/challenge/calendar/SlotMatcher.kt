package dev.fitnessapp.algo.challenge.calendar

import dev.fitnessapp.algo.core.calendar.EnrollmentView
import dev.fitnessapp.algo.core.calendar.GymCandidate
import dev.fitnessapp.algo.core.calendar.MatchMethod
import dev.fitnessapp.algo.core.calendar.SlotKey
import dev.fitnessapp.algo.core.calendar.SlotMatch
import dev.fitnessapp.algo.core.calendar.SlotMatching
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * `challenge.calendar` slot assignment (CHALLENGE_ENGINE 9.3): per ISO week, the maximum-weight bipartite matching of
 * gym candidates to the week's required slots, by brute force (at most about ten events and four slots). Manual
 * corrections and this enrollment's markers are fixed edges; title edges need the slot's pattern and reach the
 * acceptance score with the gym, duration, weekday and opening-hour evidence; an alias edge (B for TEST and back)
 * costs the alias penalty and carries `slot_mismatch` (C36). Ties go to the earlier start, then the smaller
 * instance key.
 */
object SlotMatcher : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("challenge.calendar")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.number("challenge.calendar.accept_score", "match weight", 0.4, 0.9),
        ParamSpec.number("challenge.calendar.alias_penalty", "match weight", 0.0, 0.3),
        ParamSpec.number("challenge.calendar.confirm_below_score", "match weight", 0.6, 1.0),
        ParamSpec.integer("challenge.calendar.duration_tolerance_min", "min", 15.0, 90.0),
        ParamSpec.number("challenge.calendar.w_duration", "match weight", 0.0, 1.0),
        ParamSpec.number("challenge.calendar.w_gym_evidence", "match weight", 0.0, 1.0),
        ParamSpec.number("challenge.calendar.w_gym_hours", "match weight", 0.0, 1.0),
        ParamSpec.number("challenge.calendar.w_pattern", "match weight", 0.0, 1.0),
        ParamSpec.number("challenge.calendar.w_pref_weekday", "match weight", 0.0, 1.0),
    )

    /** `slot_mismatch` (DATA_MODEL 9.7.3 attention). */
    const val SLOT_MISMATCH = "slot_mismatch"

    /** The weight of a fixed edge (marker or manual correction, 9.3). */
    private const val FIXED = 1.0

    private class W(p: ParamView) {
        val accept = p.double("challenge.calendar.accept_score")
        val alias = p.double("challenge.calendar.alias_penalty")
        val tolerance = p.int("challenge.calendar.duration_tolerance_min").toLong()
        val duration = p.double("challenge.calendar.w_duration")
        val gym = p.double("challenge.calendar.w_gym_evidence")
        val hours = p.double("challenge.calendar.w_gym_hours")
        val pattern = p.double("challenge.calendar.w_pattern")
        val weekday = p.double("challenge.calendar.w_pref_weekday")
    }

    private class Edge(val cand: GymCandidate, val slot: String, val weight: Double, val method: MatchMethod, val attention: String?)

    /** The [SlotMatching] the classifier calls, bound to a params view and the profile zone. */
    fun matching(params: ParamView, zone: ZoneId): SlotMatching = SlotMatching { view, cands -> matchSlots(view, cands, params, zone) }

    fun matchSlots(view: EnrollmentView, candidates: List<GymCandidate>, params: ParamView, zone: ZoneId): List<SlotMatch> {
        val w = W(params)
        val patterns = view.patterns.map { it.first to Regex(it.second) }
        val out = ArrayList<SlotMatch>()
        for ((iso, required) in view.slotsPerWeek.toSortedMap()) {
            val cands = candidates.filter { it.isoWeek == iso }.sortedWith(compareBy({ it.beginUtc }, { it.instanceKey }))
            val taken = sortedSetOf<String>()
            val usedSlots = sortedSetOf<String>()
            // Fixed edges: manual corrections first, then this enrollment's markers for a required slot of this week.
            for (c in cands) {
                val slot = c.manualSlot ?: continue
                out += SlotMatch(c.instanceKey, iso, slot, FIXED, MatchMethod.MANUAL, null)
                taken += c.instanceKey
                usedSlots += slot
            }
            for (c in cands) {
                if (c.instanceKey in taken) continue
                val key = c.marker?.parsedKey() as? SlotKey.Challenge ?: continue
                if (key.slug != view.slug || key.isoWeek != iso || key.slot !in required || key.slot in usedSlots) continue
                out += SlotMatch(c.instanceKey, iso, key.slot, FIXED, MatchMethod.MARKER, null)
                taken += c.instanceKey
                usedSlots += key.slot
            }
            val open = required.filter { it !in usedSlots }.distinct()
            val free = cands.filter { it.instanceKey !in taken }
            val edges = free.flatMap { c -> open.mapNotNull { slot -> edge(view, c, slot, patterns, w, zone) } }
                .filter { Det.cmpScore(it.weight, w.accept) >= 0 }
            for (e in best(open, edges)) out += SlotMatch(e.cand.instanceKey, iso, e.slot, e.weight, e.method, e.attention)
        }
        return out.sortedWith(compareBy({ it.isoWeek }, { it.slot }, { it.instanceKey }))
    }

    /** The edge of [c] to [slot]: its own pattern, else an alias pattern with the penalty; markers of another week or slot become aliases too. */
    private fun edge(view: EnrollmentView, c: GymCandidate, slot: String, patterns: List<Pair<String, Regex>>, w: W, zone: ZoneId): Edge? {
        val method = when {
            c.marker != null -> MatchMethod.MARKER
            c.adopted -> MatchMethod.ADOPTED
            else -> MatchMethod.TITLE_PATTERN
        }
        val markerSlot = (c.marker?.parsedKey() as? SlotKey.Challenge)?.takeIf { it.slug == view.slug }?.slot
        val aliases = view.slotAliases.filter { slot in it }.flatten().filter { it != slot }.distinct()
        val own = markerSlot?.let { it == slot } ?: patterns.any { it.first == slot && it.second.containsMatchIn(c.normalizedTitle) }
        val viaAlias = !own && (markerSlot?.let { it in aliases } ?: patterns.any { it.first in aliases && it.second.containsMatchIn(c.normalizedTitle) })
        if (!own && !viaAlias) return null
        if (markerSlot != null) {
            // A marked event of this enrollment in another week or for the aliased slot: app-owned, rewritten by the write plan.
            val weight = if (own) FIXED else FIXED - w.alias
            return Edge(c, slot, Grid.ratio(weight), method, if (own) null else SLOT_MISMATCH)
        }
        var weight = w.pattern
        if (c.gymEvidence) weight += w.gym
        val booked = view.bookedMin[slot]
        val dur = Duration.between(c.beginUtc, c.endUtc).toMinutes()
        if (booked != null && kotlin.math.abs(dur - booked) <= w.tolerance) weight += w.duration
        val pref = view.preferred[slot]
        if (pref != null && c.localDate.dayOfWeek.value == pref.isoDow) weight += w.weekday
        if (withinHours(view, c, zone)) weight += w.hours
        if (viaAlias) weight -= w.alias
        return Edge(c, slot, Grid.ratio(weight), method, if (viaAlias) SLOT_MISMATCH else null)
    }

    private fun withinHours(view: EnrollmentView, c: GymCandidate, zone: ZoneId): Boolean {
        val h = view.gymHours.firstOrNull { it.isoDow == c.localDate.dayOfWeek.value } ?: return false
        val start = ChronoUnit.MINUTES.between(LocalTime.MIDNIGHT, Week.localTime(c.beginUtc, zone))
        return start >= h.openMin && start < h.closeMin
    }

    /** Brute-force maximum-weight matching; equal weights prefer the earlier start, then the smaller instance key, slot by slot. */
    private fun best(slots: List<String>, edges: List<Edge>): List<Edge> {
        var bestPick: List<Edge> = emptyList()
        var bestWeight = 0.0
        val chosen = ArrayList<Edge>()
        fun better(pick: List<Edge>, weight: Double): Boolean {
            val c = Det.cmpScore(weight, bestWeight)
            if (c != 0) return c > 0
            if (pick.size != bestPick.size) return pick.size > bestPick.size
            for (slot in slots) {
                val a = pick.firstOrNull { it.slot == slot }?.cand
                val b = bestPick.firstOrNull { it.slot == slot }?.cand
                if (a == null || b == null) {
                    if (a != b) return a != null
                    continue
                }
                val byStart = a.beginUtc.compareTo(b.beginUtc)
                if (byStart != 0) return byStart < 0
                val byKey = a.instanceKey.compareTo(b.instanceKey)
                if (byKey != 0) return byKey < 0
            }
            return false
        }
        fun rec(i: Int, weight: Double) {
            if (i == slots.size) {
                if (better(chosen, weight)) {
                    bestPick = chosen.toList()
                    bestWeight = weight
                }
                return
            }
            rec(i + 1, weight)
            for (e in edges) {
                if (e.slot != slots[i] || chosen.any { it.cand.instanceKey == e.cand.instanceKey }) continue
                chosen += e
                rec(i + 1, weight + e.weight)
                chosen.removeAt(chosen.size - 1)
            }
        }
        rec(0, 0.0)
        return bestPick
    }
}
