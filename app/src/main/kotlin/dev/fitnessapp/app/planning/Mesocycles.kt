package dev.fitnessapp.app.planning

import dev.fitnessapp.algo.challenge.eca.Records
import dev.fitnessapp.algo.challenge.eca.RuleEnv
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.pipeline.Building
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.algo.selection.elig.Elig
import dev.fitnessapp.algo.selection.elig.EligContext
import dev.fitnessapp.algo.selection.rotate.ChallengeBlock
import dev.fitnessapp.algo.selection.rotate.ExerciseHistory
import dev.fitnessapp.algo.selection.rotate.Exposure
import dev.fitnessapp.algo.selection.rotate.LoadReps
import dev.fitnessapp.algo.selection.rotate.Mesocycle
import dev.fitnessapp.algo.selection.rotate.MesocycleClock
import dev.fitnessapp.algo.selection.rotate.MesocycleSelection
import dev.fitnessapp.algo.selection.rotate.RotateInput
import dev.fitnessapp.algo.selection.rotate.Rotation
import dev.fitnessapp.algo.selection.select.SelectInput
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.engine.ProgressFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap

/**
 * The mesocycle in the app (ROT-1, D86): reads the stored `rotate.mesocycle` state, runs the start the first time the
 * app needs a new mesocycle (writing its snapshot), and hands each build its anchors, consistency set and rotated-out
 * exercises. The user's change of a main lift writes the next revision.
 */
object Mesocycles {
    /** The mesocycle of today: its state, where today falls, and the exercises logged in general blocks since it began. */
    class Current(val m: Mesocycle, val clock: MesocycleClock, val usedSinceStart: Set<String>, val challengeName: String?)

    private val startLock = Mutex()

    suspend fun current(c: AppContainer, at: Instant = c.clock.now()): Current? = withContext(Dispatchers.Default) {
        val today = Week.localDate(at, PlanningFacts.ZONE)
        val (block, name) = challengeBlock(c, today)
        startLock.withLock {
            val stored = stored(c)
            val clock = Rotation.clock(today, block, stored, c.params.fork())
            val m = if (!clock.isNew && stored != null) stored else start(c, at, clock, stored)
            val used = c.db.setLogs().generalExercisesBetween(m.start.toString(), "9999-12-31").mapNotNull { slug(c, it) }.toSet()
            Current(m, if (clock.isNew) clock.copy(isNew = false) else clock, used, name)
        }
    }

    /** What a build takes from [cur] (D86 (3)); no mesocycle gives an empty selection. */
    fun selection(cur: Current?, addOn: Boolean): MesocycleSelection = Rotation.selection(cur?.m, cur?.usedSinceStart.orEmpty(), addOn)

    /** The family's eligible compound exercises for "Change" (D86 (5)), best catalog name first. */
    suspend fun choices(c: AppContainer, family: String): List<CatalogExercise> = withContext(Dispatchers.Default) {
        val f = SessionBuild.facts(c, mesocycle = false)
        Elig.pool(c.catalogModel, plain(f.elig), c.params.fork()).pool
            .filter { e -> e.mechanics == "compound" && Elig.primaryGroup(e, c.catalogModel)?.let { Groups.familyOf(it) } == family }
            .sortedBy { it.nameEn }
    }

    /** Replaces main lift [from] of [family] by [to]: the next revision of the current mesocycle (16.2 user overrides). */
    suspend fun change(c: AppContainer, cur: Current, family: String, from: String, to: String): Mesocycle {
        val m = Rotation.override(cur.m, family, from, to)
        val inputs = buildJsonObject {
            put("override", buildJsonObject { put("family", family); put("from_exercise_id", id(c, from)); put("to_exercise_id", id(c, to)) })
            put("previous_rev", JsonNumbers.int(cur.m.rev.toLong()))
        }
        c.planSnapshots.mesocycle(m.n, m.start, inputs, Rotation.json(m) { id(c, it) })
        return m
    }

    /** The newest stored state: the highest mesocycle, then the highest revision. */
    private suspend fun stored(c: AppContainer): Mesocycle? =
        c.planSnapshots.mesocycles().mapNotNull { runCatching { Rotation.parse(it) { id -> slug(c, id) } }.getOrNull() }
            .maxWithOrNull(compareBy<Mesocycle> { it.n }.thenBy { it.rev })

    /** The active challenge's block and its length (C31), with the challenge's name; nulls without one. */
    private suspend fun challengeBlock(c: AppContainer, today: LocalDate): Pair<ChallengeBlock?, String?> {
        val enrollment = c.challenges.open().firstOrNull() ?: return null to null
        val f = c.challenges.folded(enrollment.id)
        if (f.state.status != EnrollmentState.ACTIVE) return null to null
        val facts = c.challengeFacts.facts(f.def, f.state)
        val env = RuleEnv(f.def, c.params, Records(c.params), f.state, today, facts)
        val weeks = (f.def.blockWeeks.evaluate(env.roots(), env.host) as? QV)?.v?.toInt() ?: c.params.int("rotate.mesocycle_weeks")
        return ChallengeBlock(f.state.block.index, f.state.block.week, weeks) to dev.fitnessapp.app.ui.challenges.ChallengeTexts.title(f.def)
    }

    private suspend fun start(c: AppContainer, at: Instant, clock: MesocycleClock, previous: Mesocycle?): Mesocycle {
        val params = c.params.fork()
        val f = SessionBuild.facts(c, at, mesocycle = false)
        val elig = plain(f.elig)
        val budget = (c.profiles.get()?.default_session_min ?: 75).toDouble()
        val owned = f.week.challenge?.owns.orEmpty().toSet()
        val runs = TreeMap<String, SelectInput>()
        for (family in Rotation.FAMILIES) {
            // A family the challenge trains (it owns one of its groups: PULL during the OAP) gets no anchors (D86 (2)).
            if (Groups.FAMILIES.getValue(family).any { it in owned }) continue
            // SEL-1 for the family alone (D86 (2)): nothing done this week, two sessions of it per week, full readiness.
            val req = SessionBuild.request(f, SessionType.of(family), budget).copy(
                vBefore = Groups.CANONICAL.associateWith { 0.0 }, opp = Groups.CANONICAL.associateWith { 2 },
                readiness = Groups.CANONICAL.associateWith { 1.0 }, elig = elig, forbiddenGroups = owned,
                forbiddenSessionTags = emptySet(), tagRules = emptyList(), busyStations = emptySet(),
                anchors = emptyList(), history = emptySet(), rotatedOut = emptySet(),
            )
            Building.selectInput(req, c.catalogModel, params).second.getOrNull()?.let { runs[family] = it }
        }
        val previousFrom = previous?.start ?: clock.start.minusWeeks(params.int("rotate.mesocycle_weeks").toLong())
        val used = c.db.setLogs().generalExercisesBetween(previousFrom.toString(), clock.start.toString()).mapNotNull { slug(c, it) }.toSet()
        val history = history(c, c.progress.facts(), clock.start)
        val m = Rotation.start(
            RotateInput(
                clock = clock, previous = previous, familyRuns = runs, pool = Elig.pool(c.catalogModel, elig, params).pool,
                prefs = f.prefs, jointFlags = elig.jointFlags, used = used, history = history.filterKeys { it in used || previous?.anchors?.any { a -> a.slug == it } == true },
                previousFrom = previousFrom,
            ),
            c.catalogModel, params,
        )
        val inputs = buildJsonObject {
            put("clock", buildJsonObject {
                put("n", JsonNumbers.int(clock.n.toLong())); put("start", clock.start.toString()); put("week", JsonNumbers.int(clock.week.toLong()))
                put("weeks", JsonNumbers.int(clock.weeks.toLong())); put("source", clock.source)
                clock.blockIndex?.let { put("block_index", JsonNumbers.int(it.toLong())) }
            })
            previous?.let { p -> put("previous", buildJsonObject { put("n", JsonNumbers.int(p.n.toLong())); put("rev", JsonNumbers.int(p.rev.toLong())) }) }
            put("previous_from", previousFrom.toString())
            put("used", buildJsonArray { used.sorted().forEach { add(JsonPrimitive(id(c, it))) } })
            put("family_runs", buildJsonArray { runs.keys.forEach { add(JsonPrimitive(it)) } })
        }
        c.planSnapshots.mesocycle(m.n, m.start, inputs, Rotation.json(m) { id(c, it) })
        return m
    }

    /** The first day's ELIG-1 context without its day rules (D86 (2)): no gap day, windows, light-pull or add-on filter. */
    private fun plain(e: EligContext): EligContext =
        e.copy(gapDay = false, forbiddenSessionTags = emptyList(), hardWindows = emptyList(), lightPull = null, addOn = null)

    /**
     * The exposures of every exercise before [start] (D86 (2)): per finished session, the best engine-logged e1RM of its
     * working sets (C3) and the best reps at each effective load; the slope is SSE-1's last logged `g_per_week`
     * (the mean over sides).
     */
    fun history(c: AppContainer, facts: ProgressFacts, start: LocalDate): Map<String, ExerciseHistory> {
        val out = TreeMap<String, ExerciseHistory>()
        val working = facts.sets.filter { it.set.set_kind == "working" && LocalDate.parse(it.session_local_date).isBefore(start) }
        for ((exerciseId, rows) in working.groupBy { it.set.exercise_id }) {
            val slug = slug(c, exerciseId) ?: continue
            val exposures = rows.groupBy { it.set.session_id }.map { (sessionId, xs) ->
                val loads = TreeMap<Double, Int>()
                xs.forEach { r ->
                    val load = r.set.effective_load_kg?.let { Grid.kg(Scaled.toDouble(it, 2)) } ?: return@forEach
                    val reps = r.set.reps ?: return@forEach
                    if ((loads[load] ?: -1) < reps) loads[load] = reps
                }
                Exposure(
                    LocalDate.parse(xs.first().session_local_date), sessionId, xs.mapNotNull { facts.e1rm[it.set.id]?.e1rmKg }.maxOrNull(),
                    loads.map { (l, r) -> LoadReps(l, r) },
                )
            }
            val startMs = start.atStartOfDay(PlanningFacts.ZONE).toInstant().toEpochMilli()
            val last = facts.sse.filter { it.exerciseId == exerciseId && it.startedAtMs < startMs }
            val lastSession = last.maxByOrNull { it.startedAtMs }?.sessionId
            val g = last.filter { it.sessionId == lastSession }.mapNotNull { it.gPerWeek }.takeIf { it.isNotEmpty() }?.average()
            out[slug] = ExerciseHistory(slug, exposures, g)
        }
        return out
    }

    fun id(c: AppContainer, slug: String): String = c.catalog.bySlug(slug)?.let { c.catalog.idOf(it) } ?: slug

    fun slug(c: AppContainer, id: String): String? = c.catalog.byId(id)?.slug
}
