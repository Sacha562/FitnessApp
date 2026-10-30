package dev.fitnessapp.app.ui.live

import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.algo.selection.elig.Elig
import dev.fitnessapp.algo.selection.elig.EligContext
import dev.fitnessapp.algo.selection.pref.PrefTier
import dev.fitnessapp.algo.selection.substitute.SubCandidate
import dev.fitnessapp.algo.selection.substitute.SubInput
import dev.fitnessapp.algo.selection.substitute.Substitute
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.uidet.UiSwap
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import java.time.LocalDate

/** One row of the Swap sheet or the Alternatives section: the exercise, its SUB-1 terms, tags and start line. */
data class SwapOption(val ex: CatalogExercise, val candidate: SubCandidate, val rank: Int, val tags: List<String>, val start: String)

/**
 * SUB-1 candidates for the app (M3.6): ELIG-1's pool under the session's constraints (or without a session for the
 * exercise detail), PREF-1 values, the contract's joint flags, and the display tags and start line of [UiSwap].
 */
object SwapCandidates {
    /**
     * The Swap sheet's top 3 for [from] in [session]; [occupied] adds its stations to the ones to avoid and ranks the
     * candidates on stations busy at this weekday and hour after the others (D62).
     */
    suspend fun forSession(
        c: AppContainer, session: WorkoutSessionEntity, from: CatalogExercise, inSession: Set<String>, occupied: Boolean,
    ): List<SwapOption> {
        val today = LocalDate.parse(session.local_date)
        val prefs = c.selection.prefs(today)
        val ctx = ChallengeConstraints.eligContext(c, session, prefs)
        val p = c.params.fork()
        val pool = Elig.pool(c.catalogModel, ctx, p).pool
        val busy = if (occupied) c.selection.busyStations(c.clock.now(), dev.fitnessapp.app.planning.PlanningFacts.ZONE) else emptySet()
        val input = SubInput(from, pool, inSession, if (occupied) from.requiresStation.toSet() else emptySet(), prefs, ctx.jointFlags, busy)
        return options(c, from, Substitute.rank(input, c.catalogModel, p), session.id)
    }

    /** The exercise detail's Alternatives (UX 13.3): no occupied context, stations and preferences only, up to 5. */
    suspend fun alternatives(c: AppContainer, from: CatalogExercise, today: LocalDate): List<SwapOption> {
        val prefs = c.selection.prefs(today)
        val p = c.params.fork()
        val pool = Elig.pool(c.catalogModel, EligContext(availableStations = c.selection.stations(), prefs = prefs), p).pool
        return options(c, from, Substitute.alternatives(SubInput(from, pool, prefs = prefs), c.catalogModel, p), null)
    }

    private suspend fun options(c: AppContainer, from: CatalogExercise, ranked: List<SubCandidate>, sessionId: String?): List<SwapOption> {
        val onlyDisliked = ranked.isNotEmpty() && ranked.all { it.tier == PrefTier.DISLIKE }
        return ranked.mapIndexedNotNull { i, cand ->
            val ex = c.catalogModel.exercise(cand.slug) ?: return@mapIndexedNotNull null
            SwapOption(ex, cand, i + 1, UiSwap.tags(cand, ex, onlyDisliked), UiSwap.startLine(last(c, ex, sessionId), transferFrom(c, ex, sessionId)))
        }
    }

    /** The heaviest working set of the last session with [ex], as "80 kg × 10". */
    private suspend fun last(c: AppContainer, ex: CatalogExercise, sessionId: String?): String? {
        val h = c.sessionRepository.history(c.catalog.idOf(ex), sessionId ?: "", limit = 30).filter { it.set.set_kind != "warmup" }
        val lastSession = h.firstOrNull()?.set?.session_id ?: return null
        val s = h.filter { it.set.session_id == lastSession }.maxByOrNull { it.set.effective_load_kg ?: 0L }?.set ?: return null
        val dur = s.duration_s
        val kg = s.effective_load_kg
        val reps = s.reps
        return when {
            dur != null && reps == null -> Numbers.num(Scaled.toDouble(dur, 1), 1) + " s"
            kg != null && reps != null -> Numbers.kg(Scaled.toDouble(kg, 2)) + " × $reps"
            reps != null -> "$reps reps"
            else -> null
        }
    }

    /** The exercise a transfer ratio would start [ex] from: one with history and a ratio to [ex], by slug. */
    private suspend fun transferFrom(c: AppContainer, ex: CatalogExercise, sessionId: String?): String? {
        for (t in c.catalog.doc.transferRatios.filter { it.toSlug == ex.slug }.sortedBy { it.fromSlug }) {
            val src = c.catalog.bySlug(t.fromSlug) ?: continue
            if (c.sessionRepository.history(c.catalog.idOf(src), sessionId ?: "", limit = 1).isNotEmpty()) return src.nameEn
        }
        return null
    }
}
