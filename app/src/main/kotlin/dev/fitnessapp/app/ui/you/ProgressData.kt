package dev.fitnessapp.app.ui.you

import dev.fitnessapp.algo.challenge.eca.GateCriteria
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.bodyweight.Bodyweight
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.selection.rotate.Rotation
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.planning.Mesocycles
import dev.fitnessapp.app.planning.PlanningFacts
import dev.fitnessapp.app.planning.WeekVolume
import dev.fitnessapp.app.uidet.GateRows
import dev.fitnessapp.app.uidet.PersonalBest
import dev.fitnessapp.app.uidet.ProgressExercise
import dev.fitnessapp.app.uidet.ProgressLevel
import dev.fitnessapp.app.uidet.ProgressSet
import dev.fitnessapp.app.uidet.UiGateRows
import dev.fitnessapp.app.uidet.UiProgress
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.dao.FinishedSetRow
import dev.fitnessapp.data.engine.ProgressFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/** One exercise's history for the charts (UX 15.2): its sets, the logged levels and how added kg is derived. */
data class ExerciseProgress(
    val exerciseId: String,
    val slug: String,
    val name: String,
    val sets: List<ProgressSet>,
    val sse: List<ProgressLevel>,
    val hold: List<ProgressLevel>,
    val exercise: ProgressExercise,
    val sessions: Int,
    val lastMs: Long,
    val bests: List<PersonalBest>,
    val gate: UiProgress.Gate?,
)

/** A main lift on the mesocycle card (D86 (5)). */
data class MesoLift(val family: String, val slug: String, val exerciseId: String, val name: String, val reason: String)

data class MesoFamily(val family: String, val label: String, val lifts: List<MesoLift>, val note: String?)

/** The "This mesocycle" card (UX 15.2, D86 (5)). */
data class MesoCard(val title: String, val families: List<MesoFamily>, val rotated: List<String>)

data class BwPoint(val date: LocalDate, val kg: Double, val ewma: Double?, val test: Boolean)

/** One ISO week's group volume against that week's target (UX 15.2 Weekly volume). */
data class VolumeWeekRow(val isoWeek: String, val done: Map<String, Double>, val target: Map<String, Double>)

/** An exercise's bests; [signed] when its e1RM bests read as added kg (a bodyweight lift, C32). */
data class ExerciseBests(val name: String, val signed: Boolean, val bests: List<PersonalBest>)

data class BestRow(val exerciseId: String, val name: String, val best: PersonalBest, val signed: Boolean = false)

data class ProgressUi(
    val meso: MesoCard?,
    val exercises: List<ExerciseProgress>,
    val defaultExerciseId: String?,
    val gates: GateRows?,
    val bodyweight: List<BwPoint>,
    val bests: List<BestRow>,
    /** Mesocycles are turned off (D100): the card offers "Turn on" instead of the main lifts. */
    val mesoOff: Boolean = false,
)

/** Builds You → Progress from the stored facts (D86 (5), (6)); nothing here computes an estimate (C3). */
object ProgressData {
    val FAMILY_LABEL = mapOf("LEGS" to "Legs", "PUSH" to "Push", "PULL" to "Pull", "CORE" to "Core")

    suspend fun load(c: AppContainer): ProgressUi = withContext(Dispatchers.Default) {
        val facts = c.progress.facts()
        val gate = runCatching { gate(c) }.getOrNull()
        val exercises = exercises(c, facts, gate)
        val now = c.clock.now().toEpochMilli()
        // The default (UX 15.2): the most-trained exercise of the last 4 weeks, preferring one with a strength chart
        // (warm-up and grip items of the challenge are trained often but have no e1RM), else the challenge's main lift.
        val since = now - 28L * 86_400_000L
        val recent = exercises.filter { it.lastMs >= since }
        val trained = compareBy<ExerciseProgress> { e -> e.sets.filter { it.atMs >= since }.map { it.sessionId }.distinct().size }.thenByDescending { it.name }
        val default = recent.filter { e -> e.sets.any { it.e1rmKg != null } }.maxWithOrNull(trained)
            ?: exercises.firstOrNull { it.gate != null && it.sets.isNotEmpty() } ?: recent.maxWithOrNull(trained) ?: exercises.maxByOrNull { it.lastMs }
        val cur = runCatching { Mesocycles.current(c) }.getOrNull()
        ProgressUi(
            meso = cur?.let { meso(c, it) },
            exercises = exercises.sortedBy { it.name },
            defaultExerciseId = default?.exerciseId,
            gates = gate?.second,
            bodyweight = bodyweight(c, facts),
            bests = exercises.flatMap { e -> e.bests.map { BestRow(e.exerciseId, e.name, it, e.exercise.fBw > 0.0) } }
                .sortedWith(compareByDescending<BestRow> { it.best.date }.thenBy { it.name }.thenBy { it.best.kind.ordinal }.thenBy { it.best.side }),
            mesoOff = !Mesocycles.enabled(c),
        )
    }

    /** Every exercise's bests for the Session Summary (UX 12.3), by exercise id. */
    suspend fun bests(c: AppContainer): Map<String, ExerciseBests> = withContext(Dispatchers.Default) {
        exercises(c, c.progress.facts(), null).associate { it.exerciseId to ExerciseBests(it.name, it.exercise.fBw > 0.0, it.bests) }
    }

    fun exercises(c: AppContainer, facts: ProgressFacts, gate: Pair<Pair<String, UiProgress.Gate>, GateRows>?): List<ExerciseProgress> {
        val params = c.params.fork()
        return facts.sets.filter { it.set.set_kind != "warmup" }.groupBy { it.set.exercise_id }.mapNotNull { (exerciseId, rows) ->
            val ex = c.catalog.byId(exerciseId) ?: return@mapNotNull null
            val sets = rows.map { set(it, facts) }
            val ms = rows.mapNotNull { it.session_started_at }
            ExerciseProgress(
                exerciseId, ex.slug, ex.nameEn, sets,
                facts.sse.filter { it.exerciseId == exerciseId }.map { ProgressLevel(it.sessionId, it.startedAtMs, it.side, it.m, it.lo, it.hi, it.bodyweightKg) },
                facts.hold.filter { it.exerciseId == exerciseId }.map { ProgressLevel(it.sessionId, it.startedAtMs, it.side, it.m, it.lo, it.hi, it.bodyweightKg) },
                ProgressExercise(ex.bwFraction, ex.implementBaseKg ?: 0.0),
                rows.map { it.set.session_id }.distinct().size, ms.maxOrNull() ?: 0L,
                UiProgress.bests(sets, params),
                gate?.first?.takeIf { it.first == ex.slug }?.second,
            )
        }
    }

    private fun set(r: FinishedSetRow, facts: ProgressFacts): ProgressSet {
        val s = r.set
        val e = facts.e1rm[s.id]
        val bw = s.bodyweight_kg?.let { Scaled.toDouble(it, 2) }
        val load = s.effective_load_kg?.let { Scaled.toDouble(it, 2) }
        val fBw = s.f_bw?.let { Scaled.toDouble(it, 4) } ?: 0.0
        val base = Scaled.toDouble(s.implement_base_kg, 2)
        val shown = load?.let { if (fBw > 0.0 && bw != null) it - fBw * bw - base else it - base }
        val at = r.session_started_at ?: s.completed_at
        return ProgressSet(
            setId = s.id, sessionId = s.session_id, atMs = at, date = LocalDate.parse(r.session_local_date), side = s.side,
            test = r.session_kind == "test", e1rmKg = e?.e1rmKg, e1rmAddedKg = e?.e1rmAddedKg, estimatorClass = e?.estimatorClass,
            loadKg = load, shownKg = shown, reps = s.reps, holdS = s.duration_s?.takeIf { s.reps == null }?.let { it / 10.0 },
            assistKg = s.assist_kg?.let { Scaled.toDouble(it, 2) }, assistPin = s.assist_pin_native?.let { Scaled.toDouble(it, 2) },
            bodyweightKg = bw, failedRep = "failed_rep" in s.flags, pain = s.pain_flag || s.deviation_reason_code == "pain",
        )
    }

    private fun meso(c: AppContainer, cur: Mesocycles.Current): MesoCard {
        val m = cur.m
        val title = buildString {
            append("Mesocycle ${m.n}")
            m.blockIndex?.let { append(" · block $it") }
            append(" · week ${cur.clock.week} of ${m.weeks}")
        }
        val families = Rotation.FAMILIES.map { f ->
            val lifts = m.anchors.filter { it.family == f }.map { a ->
                MesoLift(f, a.slug, Mesocycles.id(c, a.slug), c.catalog.bySlug(a.slug)?.nameEn ?: a.slug, a.reason)
            }
            val note = when {
                lifts.isNotEmpty() -> null
                cur.challengeName != null && f == "PULL" -> "covered by ${cur.challengeName}"
                f == "CORE" -> "no compound lifts, accessories only"
                else -> "none yet"
            }
            MesoFamily(f, FAMILY_LABEL.getValue(f), lifts, note)
        }
        val name = { slug: String -> (c.catalog.bySlug(slug)?.nameEn ?: slug).replaceFirstChar { it.lowercase() } }
        val rotated = m.rotated.map { r ->
            val why = when (r.reason) {
                "stalled" -> "no new best in ${c.params.int("rotate.stall_exposures")} sessions"
                "disliked" -> "you dislike it"
                "stalled_disliked" -> "stalled and disliked"
                else -> "low keep score"
            }
            if (r.to != null) "Rotated: ${name(r.from)} → ${name(r.to!!)} ($why)" else "Rotated out: ${name(r.from)} ($why)"
        }
        return MesoCard(title, families, rotated)
    }

    /**
     * The active challenge's gate rows (UX 14.2) and the gate line of the exercise whose personal curve the gate metric
     * reads (`userK('<slug>')` in its derived expression, D7): the forward threshold at today's bodyweight and the one
     * on the test day's (C62), with the test value and its x1.10 value.
     */
    private suspend fun gate(c: AppContainer): Pair<Pair<String, UiProgress.Gate>, GateRows>? {
        val enrollment = c.challenges.open().firstOrNull() ?: return null
        val f = c.challenges.folded(enrollment.id)
        if (f.state.status != EnrollmentState.ACTIVE) return null
        val facts = c.challengeFacts.facts(f.def, f.state)
        val rows = UiGateRows.build(f.def, c.params, f.state, facts, facts.bwKg, emptyList())
        val criteria = GateCriteria.criteria(f.def, c.params, f.state, facts)
        val test = f.state.lastValidTest
        for (cr in criteria) {
            val metric = cr.metrics.firstOrNull() ?: continue
            val derived = f.def.metrics[metric]?.spec?.derived ?: continue
            val slug = Regex("userK\\('([a-z0-9_]+)'\\)").find(derived)?.groupValues?.get(1) ?: continue
            val now = GateCriteria.rhsAt(f.def, c.params, f.state, facts, cr.phaseId, cr.index, facts.bwKg)?.v?.toDouble() ?: continue
            val bwTest = (test?.metrics?.get("bw") as? QV)?.v
            val atTest = bwTest?.let { GateCriteria.rhsAt(f.def, c.params, f.state, facts, cr.phaseId, cr.index, it)?.v?.toDouble() }
            val value = (test?.metrics?.get(metric) as? QV)?.v?.toDouble()
            val m110 = (test?.metrics?.get("${metric}_m110") as? QV)?.v?.toDouble()
            val label = cr.label?.get("en") ?: "Gate"
            return (slug to UiProgress.Gate(label, value, test?.date, m110, now, facts.bwKg?.toDouble(), atTest, bwTest?.toDouble(), null)) to rows
        }
        return null
    }

    private suspend fun bodyweight(c: AppContainer, facts: ProgressFacts): List<BwPoint> {
        val obs = c.bodyweight.observations().sortedBy { it.observedAt }
        val tests = facts.sets.filter { it.session_kind == "test" }.map { it.set.session_id }.toSet()
        val params = c.params.fork()
        return obs.map { o ->
            val end = Week.atLocal(o.localDate, LocalTime.MAX, PlanningFacts.ZONE)
            BwPoint(o.localDate, o.valueKg, Bodyweight.ewma(obs, end, params), o.sessionId in tests)
        }
    }

    /** The weekly volume of the last up to 12 ISO weeks with logged sets (UX 15.2), each against its own targets. */
    suspend fun volume(c: AppContainer): List<VolumeWeekRow> = withContext(Dispatchers.Default) {
        val today = Week.localDate(c.clock.now(), PlanningFacts.ZONE)
        val first = c.progress.facts().sets.minOfOrNull { LocalDate.parse(it.session_local_date) } ?: return@withContext emptyList()
        var monday = Week.isoWeekStart(today).minusWeeks(11)
        if (monday.isBefore(Week.isoWeekStart(first))) monday = Week.isoWeekStart(first)
        val out = ArrayList<VolumeWeekRow>()
        while (!monday.isAfter(today)) {
            val end = minOf(monday.plusDays(6), today)
            val at = if (end == today) c.clock.now() else Week.atLocal(end, LocalTime.of(23, 59), PlanningFacts.ZONE)
            runCatching { WeekVolume.compute(c, at) }.getOrNull()?.let { w ->
                val groups = Groups.CANONICAL.filter { g -> w.targets.groups[g]?.let { it.status == "improve" || it.status == "maintain" } == true }
                out += VolumeWeekRow(
                    Week.isoWeek(monday),
                    groups.associateWith { w.account.done[it]?.vhyp ?: 0.0 },
                    groups.associateWith { w.bars.rows.firstOrNull { r -> r.group == it }?.target ?: 0.0 },
                )
            }
            monday = monday.plusWeeks(1)
        }
        out
    }
}
