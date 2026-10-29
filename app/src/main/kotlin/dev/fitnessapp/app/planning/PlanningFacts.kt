package dev.fitnessapp.app.planning

import dev.fitnessapp.algo.accounting.setcredit.CreditSet
import dev.fitnessapp.algo.accounting.setcredit.CreditedSet
import dev.fitnessapp.algo.accounting.setcredit.SetCredit
import dev.fitnessapp.algo.core.calendar.AnchorKind
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.targets.runs.Run
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.uidet.Prompt
import dev.fitnessapp.app.uidet.PromptHistoryEntry
import dev.fitnessapp.app.uidet.PromptOutcome
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.repo.PromptEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/**
 * The facts the M4 planning modules read, as the phone holds them: credited sets (`setcredit` on the logged history),
 * runs from the classified calendar (`runs`, F6) and the prompt history (`ui.prompts`).
 */
object PlanningFacts {
    val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

    /** How far back the history reaches: re-entry looks for a 21-day gap, novelty for 21 days (ALGORITHMS_PLANNING 3, 5.5). */
    private const val HISTORY_DAYS = 56L

    /**
     * SetCredit on every live set since [HISTORY_DAYS] before [now]'s week, oldest first. A set's novelty reads the last
     * earlier day the same exercise was done; holds give their felt chip (`hold_reserve_s` 0 / 2 / 5 / 10 s).
     */
    suspend fun credited(c: AppContainer, now: Instant): List<CreditedSet> {
        val from = Week.atLocal(Week.isoWeekStart(Week.localDate(now, ZONE)).minusDays(HISTORY_DAYS), java.time.LocalTime.MIDNIGHT, ZONE)
        val rows = c.db.setLogs().liveSince(from.toEpochMilli())
        val catalog = c.catalogModel
        val seen = TreeMap<String, LocalDate>()
        val out = ArrayList<CreditedSet>()
        for (r in rows) {
            val ex = c.catalog.byId(r.exercise_id) ?: continue
            val model = catalog.exercise(ex.slug) ?: continue
            val set = creditSet(r, ex.slug)
            val day = Week.localDate(set.ts, ZONE)
            val prior = seen[ex.slug]?.takeIf { it < day }
            out += CreditedSet(set, SetCredit.credit(set, model, catalog, prior, ZONE, c.params))
            if ((seen[ex.slug] ?: LocalDate.MIN) < day) seen[ex.slug] = day
        }
        return out
    }

    fun creditSet(r: SetLogEntity, slug: String): CreditSet = CreditSet(
        id = r.id, sessionId = r.session_id, exerciseSlug = slug, side = r.side, setKind = r.set_kind, completed = true,
        ts = Instant.ofEpochMilli(r.completed_at), durationS = r.duration_s?.let { it / 10.0 },
        felt = r.hold_reserve_s?.let { felt(it / 10.0) }, rirReported = r.rir_reported?.let { it / 10.0 },
        plannedRir = r.planned_rir?.let { it / 10.0 },
    )

    /** The felt chip of a hold from its logged reserve (UX 11.7.3): max 0 s, hard 2 s, ok 5 s, easy 10 s. */
    private fun felt(reserveS: Double): String = when {
        reserveS <= 0.0 -> "max"
        reserveS <= 2.0 -> "hard"
        reserveS <= 5.0 -> "ok"
        else -> "easy"
    }

    /** The last time each group had direct sets (FS > 0): soreness is asked only within `ui.prompts.sore_window_h`. */
    fun lastTrained(credited: List<CreditedSet>): Map<String, Instant> {
        val out = TreeMap<String, Instant>()
        for (cs in credited) for ((g, fs) in cs.credit.fs) if (fs > 0.0 && (out[g]?.isBefore(cs.set.ts) != false)) out[g] = cs.set.ts
        return out
    }

    /** Runs of the ISO week of [monday] from the classified calendar (F6: leg and whole-body fatigue only). */
    fun runs(c: AppContainer, monday: LocalDate): List<Run> {
        val week = Week.isoWeek(monday)
        return c.calendar.snapshot.value.anchors.filter { it.kind == AnchorKind.RUN && Week.isoWeek(it.localDate) == week }
            .sortedWith(compareBy({ it.beginUtc }, { it.instanceKey }))
            .map { a ->
                val minutes = a.runMinutes?.toDouble() ?: ((a.endUtc.epochSecond - a.beginUtc.epochSecond) / 60.0)
                Run(a.instanceKey, a.beginUtc, a.endUtc, minutes, a.runIntensity?.code ?: "easy", a.runLong)
            }
    }

    /**
     * The prompt history of `ui.prompts`: one outcome per session and prompt, in the order the sessions first asked. A
     * shown prompt without a `prompt_skipped` was answered; a skip's cause tells a user skip from a budget or reduced
     * drop. Soreness rows collapse into one outcome per session (answered if any row was answered).
     */
    fun promptHistory(events: List<PromptEvent>): List<PromptHistoryEntry> {
        data class Round(val first: Long, val shown: MutableSet<String> = HashSet(), val skips: MutableMap<String, MutableList<String>> = HashMap())
        val out = ArrayList<Pair<Long, PromptHistoryEntry>>()
        val rounds = LinkedHashMap<String, Round>()
        val shownRows = HashMap<String, Int>()
        val skippedRows = HashMap<String, Int>()
        for (e in events.sortedBy { it.at }) {
            if (e.type == "reset") {
                Prompt.of(e.prompt)?.let { out += e.at to PromptHistoryEntry(it, PromptOutcome.RESET) }
                continue
            }
            val sid = e.sessionId ?: continue
            val r = rounds.getOrPut(sid) { Round(e.at) }
            val k = "$sid|${e.prompt}"
            when (e.type) {
                "prompt_shown" -> { r.shown += e.prompt; shownRows[k] = (shownRows[k] ?: 0) + 1 }
                "prompt_skipped" -> {
                    r.skips.getOrPut(e.prompt) { ArrayList() } += e.cause ?: "user"
                    if (e.cause == "user") skippedRows[k] = (skippedRows[k] ?: 0) + 1
                }
            }
        }
        for ((sid, r) in rounds) {
            for (p in Prompt.entries) {
                val k = "$sid|${p.code}"
                val outcome = when {
                    p.code in r.shown -> if ((skippedRows[k] ?: 0) >= (shownRows[k] ?: 0)) PromptOutcome.SKIPPED else PromptOutcome.ANSWERED
                    r.skips[p.code]?.contains("reduced") == true -> PromptOutcome.REDUCED
                    r.skips[p.code]?.contains("budget") == true -> PromptOutcome.BUDGET
                    else -> continue
                }
                out += r.first to PromptHistoryEntry(p, outcome)
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }
}
