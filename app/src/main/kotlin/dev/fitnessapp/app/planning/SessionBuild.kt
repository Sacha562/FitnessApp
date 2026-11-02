package dev.fitnessapp.app.planning

import dev.fitnessapp.algo.accounting.readiness.Dose
import dev.fitnessapp.algo.accounting.readiness.Readiness
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.pipeline.BuildRequest
import dev.fitnessapp.algo.pipeline.BuildResult
import dev.fitnessapp.algo.pipeline.Building
import dev.fitnessapp.algo.planning.SessionTagRule
import dev.fitnessapp.algo.planning.allocator.AddOnSlot
import dev.fitnessapp.algo.planning.allocator.Allocation
import dev.fitnessapp.algo.planning.allocator.Allocator
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.algo.selection.elig.AddOnFilter
import dev.fitnessapp.algo.selection.elig.Elig
import dev.fitnessapp.algo.selection.elig.EligContext
import dev.fitnessapp.algo.selection.pref.PrefValue
import dev.fitnessapp.algo.selection.time.WarmupKind
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.live.ChallengeConstraints
import dev.fitnessapp.data.repo.BuiltItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Building a general block in the app (M4.4, D62; UX 22.6 `SessionBuilder`): the facts the L6 pipeline reads as the phone
 * holds them, gathered once per sheet ([Facts]), and the calls into the pure [Building]. A standalone session is built
 * for one of the eight types with a time budget; an add-on is built after a challenge session within the slot's
 * capacity and filters. The week plan (M4.5, D70) gives each group its opportunities: this session and the general
 * sessions it recommends later this week whose type trains the group (7.2 `opp_g`), and it picks the add-on family.
 */
object SessionBuild {
    /** The New session sheet's types (ALGORITHMS_PLANNING 0.3), in the fixed tie order. */
    val TYPES: List<SessionType> = SessionType.entries.toList()

    /** The families an add-on may train (D62): one family or legs and push together. */
    val ADD_ON_TYPES: List<SessionType> = listOf(SessionType.LEGS, SessionType.PUSH, SessionType.PUSH_LEGS, SessionType.CORE)

    fun label(t: SessionType): String = when (t) {
        SessionType.LEGS -> "Legs"
        SessionType.PUSH -> "Push"
        SessionType.PULL -> "Pull"
        SessionType.PUSH_LEGS -> "Legs + push"
        SessionType.PULL_LEGS -> "Pull + legs"
        SessionType.UPPER -> "Upper"
        SessionType.FULL -> "Full body"
        SessionType.CORE -> "Core"
    }

    /**
     * The allocation's direct groups that a kept exercise trains (coefficient at least 0.5). The label of 7.5 reads the
     * allocation, not every group an exercise touches (a back extension for the lower back does not make a push session
     * "Legs + push"), and a family whose exercises UI-02 cut is not named.
     */
    private fun keptDirect(r: BuildResult, c: AppContainer): Map<String, Double> {
        val trained = r.exercises.flatMap { x ->
            val e = c.catalogModel.exercise(x.slug) ?: return@flatMap emptyList()
            Groups.CANONICAL.filter { c.catalogModel.groupCoeff(e, it) >= 0.5 }
        }.toSet()
        return r.allocation.direct.filter { (g, v) -> v > 0.0 && g in trained }
    }

    /** The families the block trains (7.5): Legs, Push, Pull in that order; CORE only for a core-only block. */
    fun families(r: BuildResult, c: AppContainer): List<String> {
        val groups = keptDirect(r, c).keys
        val fs = listOf("LEGS", "PUSH", "PULL").filter { f -> Groups.FAMILIES.getValue(f).any { it in groups } }
        return if (fs.isEmpty() && groups.isNotEmpty()) listOf("CORE") else fs
    }

    /** The block's label (7.5, 9.5): "Legs + push", "Push + rear delts", "Core"; "Free session" when nothing is left. */
    fun title(r: BuildResult, c: AppContainer): String = Allocator.label(keptDirect(r, c), addon = false).ifEmpty { "Free session" }

    /**
     * What one sheet's builds share: this week's targets and accounting, readiness at [at], PREF-1, the stations and
     * the busy ones, and the challenge's word on the day ([day] null without an active challenge).
     */
    class Facts(
        val at: Instant,
        val week: WeekVolume,
        val readiness: Map<String, Double>,
        val prefs: Map<String, PrefValue>,
        val stations: Set<String>,
        val busy: Set<String>,
        val day: ChallengeConstraints.Day?,
        val elig: EligContext,
        /** The types of the general sessions the week plan recommends after this one (D70 (4)). */
        val laterTypes: List<SessionType> = emptyList(),
        /** The add-on family the week plan chose for today's challenge session, null for none. */
        val planAddOn: SessionType? = null,
    )

    /** The build facts at [at]; [weekPlan] is this week's current plan when the caller has one (D73), else it is planned here. */
    suspend fun facts(c: AppContainer, at: Instant = c.clock.now(), slot: String? = null, weekPlan: WeekPlan? = null): Facts = withContext(Dispatchers.Default) {
        val zone = PlanningFacts.ZONE
        val date = Week.localDate(at, zone)
        val week = WeekVolume.compute(c, at)
        val doses = PlanningFacts.credited(c, at).filter { !it.set.ts.isAfter(at) }.map { Dose.of(it.credit, it.set.ts) }
        val readiness = Readiness.evaluate(doses, at, null, null, c.params.fork()).r
        val prefs = c.selection.prefs(date)
        val day = ChallengeConstraints.day(c, at, date, slot)
        val goals = week.targets.groups
        val base = ChallengeConstraints.eligContext(c, day, prefs)
        val elig = base.copy(
            skipGroups = goals.filterValues { it.status == "skip" }.keys,
            goalGroups = goals.filterValues { (it.status == "improve" || it.status == "maintain") && !it.coveredByChallenge }.keys,
        )
        val plan = weekPlan ?: runCatching { WeekPlans.thisWeek(c, at).plan }.getOrNull()
        val later = plan?.days.orEmpty().filter { it.date.isAfter(date) }.flatMap { d -> d.items.filter { it.kind == "recommended_general" } }
            .mapNotNull { it.type?.let { t -> runCatching { SessionType.of(t) }.getOrNull() } }
        val addOn = plan?.days?.firstOrNull { it.date == date }?.items?.firstOrNull { it.kind == "addon" && (slot == null || it.slot == slot) }?.type
            ?.let { runCatching { SessionType.of(it) }.getOrNull() }
        Facts(
            at, week, readiness, prefs, c.equipment.types.filter { it.category == "station" }.map { it.key }.toSortedSet(),
            c.selection.busyStations(at, zone), day, elig, later, addOn,
        )
    }

    /** The request for a standalone session of [type] with [budgetMin] (the "90+" chip as `today.max_budget_min`). */
    fun request(f: Facts, type: SessionType, budgetMin: Double): BuildRequest = base(f, type, budgetMin, WarmupKind.GENERAL, f.elig, null)

    /** The request for an add-on of [type] (one of [ADD_ON_TYPES]) within [capacityMin] after the day's challenge session. */
    fun addOnRequest(c: AppContainer, f: Facts, type: SessionType, capacityMin: Double): BuildRequest? {
        val slot = f.day?.addOnSlot ?: return null
        val familyGroups = type.families.flatMap { Groups.FAMILIES.getValue(it) }
        val elig = f.elig.copy(addOn = AddOnFilter(slot.allowPatterns, slot.maxGripDemand, slot.forbidGroups, familyGroups))
        val allowed = Elig.groupAvailability(c.catalogModel, elig, c.params.fork()).filterValues { it }.keys
        val addOn = AddOnSlot(type.families.joinToString("+"), allowed, slot.forbidGroups.toSet(), slot.maxSets?.toDouble(), slot.minRir)
        val req = base(f, type, capacityMin, WarmupKind.ADDON, elig, addOn)
        return req.copy(forbiddenSessionTags = req.forbiddenSessionTags + slot.forbidSessionTags)
    }

    private fun base(f: Facts, type: SessionType, budgetMin: Double, warmup: WarmupKind, elig: EligContext, addOn: AddOnSlot?): BuildRequest {
        val d = f.day
        val owned = f.week.challenge?.owns.orEmpty()
        val windowGroups = d?.windows?.filter { !it.allGym }?.flatMap { it.groups }.orEmpty()
        return BuildRequest(
            type = type, start = f.at, budgetMin = budgetMin, warmup = warmup, targets = f.week.targets,
            vBefore = Groups.CANONICAL.associateWith { (f.week.account.done[it]?.vhyp ?: 0.0) + (f.week.account.committed[it] ?: 0.0) },
            opp = opp(type, addOn != null, f.laterTypes), readiness = f.readiness, elig = elig,
            forbiddenGroups = (owned + windowGroups + addOn?.forbidGroups.orEmpty()).toSet(),
            forbiddenSessionTags = d?.gapTags.orEmpty().toSet(), tagRules = d?.contract?.sessionTagRules?.let { SessionTagRule.parse(it) }.orEmpty(),
            addOn = addOn, prefM = f.prefs.mapValues { it.value.m }, stations = f.stations, busyStations = f.busy,
        )
    }

    /**
     * `opp_g` (7.2): the standalone general sessions from this one on whose type trains `g` (every type but CORE also
     * carries the core groups, as the allocator does); an add-on is not one of them. At least 1.
     */
    private fun opp(type: SessionType, addOn: Boolean, later: List<SessionType>): Map<String, Int> {
        fun members(t: SessionType): Set<String> =
            (t.families.flatMap { Groups.FAMILIES.getValue(it) } + if (t != SessionType.CORE) Groups.FAMILIES.getValue("CORE") else emptyList()).toSet()
        val sessions = (if (addOn) emptyList() else listOf(type)) + later
        return Groups.CANONICAL.associateWith { g -> sessions.count { g in members(it) }.coerceAtLeast(1) }
    }

    suspend fun build(c: AppContainer, req: BuildRequest): BuildResult = withContext(Dispatchers.Default) {
        Building.build(req, c.catalogModel, c.params.fork())
    }

    suspend fun allocate(c: AppContainer, req: BuildRequest): Allocation = withContext(Dispatchers.Default) {
        Building.allocate(req, c.catalogModel, c.params.fork())
    }

    /**
     * The add-on families the slot can serve today, each with its allocation, best first (D62: the engine's pick is the
     * family whose allocation adds the most of this week's remaining Vhyp; ties in the type order). Empty without a slot.
     */
    suspend fun addOnChoices(c: AppContainer, f: Facts, capacityMin: Double): List<Pair<SessionType, Allocation>> =
        ADD_ON_TYPES.mapNotNull { t -> addOnRequest(c, f, t, capacityMin)?.let { t to allocate(c, it) } }
            .filter { it.second.feasible && it.second.credit.values.any { v -> v > 0.0 } }
            .sortedWith(compareByDescending<Pair<SessionType, Allocation>> { Building.value(it.second) }.thenBy { it.first.ordinal })

    /**
     * The add-on of a challenge session that just started (C34, D62): when its plan offers one (the capacity of
     * CHALLENGE_ENGINE 6.9 is at least `time.min_addon_min`), the engine's family is built and stored as the second
     * block with its `session.plan` snapshot. Returns the block id, or null when no add-on is built.
     */
    suspend fun planAddOn(c: AppContainer, sessionId: String): String? {
        val plan = c.challengeSessions.live(sessionId)?.plan ?: return null
        if (!plan.addOnOffered) return null
        val cap = plan.addOnCapacityMin ?: return null
        val f = facts(c, c.clock.now(), plan.slot)
        // D70 (4): the family picked on the Today card, else the week plan's, else the best by remaining volume (D62).
        val pick = c.settings.addOnPick(f.at.atZone(PlanningFacts.ZONE).toLocalDate().toString(), plan.slot)
        if (pick == "none") return null
        val type = pick?.let { runCatching { SessionType.of(it) }.getOrNull() } ?: f.planAddOn
            ?: addOnChoices(c, f, cap.toDouble()).firstOrNull()?.first ?: return null
        val req = addOnRequest(c, f, type, cap.toDouble()) ?: return null
        val r = build(c, req)
        if (r.exercises.isEmpty()) return null
        val blockId = c.sessionRepository.addAddOnBlock(sessionId, items(c, r), cap)
        c.sessionPlans.record(sessionId, blockId, r, cap)
        return blockId
    }

    /** The built exercises as rows to store, in ORD-1 order. */
    fun items(c: AppContainer, r: BuildResult): List<BuiltItem> =
        r.exercises.mapNotNull { x -> c.catalog.bySlug(x.slug)?.let { BuiltItem(it, x.sets) } }
}
