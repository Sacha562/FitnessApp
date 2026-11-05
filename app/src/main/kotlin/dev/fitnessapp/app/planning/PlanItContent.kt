package dev.fitnessapp.app.planning

import dev.fitnessapp.algo.challenge.calendar.CalendarLink
import dev.fitnessapp.algo.core.calendar.Marker
import dev.fitnessapp.algo.core.calendar.SlotKey
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.pipeline.BuildResult
import dev.fitnessapp.algo.pipeline.Building
import dev.fitnessapp.algo.planning.allocator.Allocation
import dev.fitnessapp.algo.planning.allocator.SessionType
import dev.fitnessapp.algo.planning.weekplan.FixedItem
import dev.fitnessapp.algo.planning.weekplan.PlanIt
import dev.fitnessapp.algo.planning.weekplan.PlanItem
import dev.fitnessapp.algo.planning.weekplan.WeekPlan
import dev.fitnessapp.algo.planning.weekplan.WeeklyPlanner
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.calendar.CalendarSync
import dev.fitnessapp.app.uidet.UiTrim
import java.time.Instant
import dev.fitnessapp.data.telemetry.EventDraft
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.util.TreeMap

/**
 * The content of a "Plan it" event (ALGORITHMS_PLANNING 8.9, D82): the planned type built for its own day at the
 * event's budget, with its label (9.5) and English exercise lines; and what the unedited planned events of a week
 * should show, handed to the calendar writer.
 */
object PlanItContent {
    /** The build of a planned or recommended general session: its label, exercise lines and readiness at its start. */
    class Content(val label: String, val lines: List<String>, val result: BuildResult, val readiness: Map<String, Double>)

    /** The budget an event of [item] gives (C49): its length on the 5-minute grid minus the changeover. */
    suspend fun budget(c: AppContainer, item: PlanItem): Double {
        val changeover = changeover(c)
        return PlanIt.minutes(item.estMinutes, changeover) - changeover
    }

    suspend fun changeover(c: AppContainer): Double = (c.profiles.get()?.changeover_min ?: AppContainer.CHANGEOVER_MIN).toDouble()

    /** [item] of [plan] built for its day at [budgetMin]; null when its type is unknown or nothing fits. */
    suspend fun build(c: AppContainer, plan: WeekPlan, item: PlanItem, budgetMin: Double): Content? {
        val type = item.type?.let { runCatching { SessionType.of(it) }.getOrNull() } ?: return null
        val f = SessionBuild.facts(c, item.start, weekPlan = plan.takeIf { !it.provisional })
        val r = SessionBuild.build(c, SessionBuild.request(f, type, budgetMin))
        if (r.exercises.isEmpty()) return null
        val lines = r.exercises.mapNotNull { x -> c.catalog.bySlug(x.slug)?.let { PlanIt.line(it.nameEn, x.sets) } }
        return Content(SessionBuild.title(r, c), lines, r, f.readiness)
    }

    /**
     * "Plan it" on [item] of [plan] (UX 10.4, D82): stores the plan's `weekplan.plan` snapshot (the marker's
     * recommendation), builds the session at the event's budget, logs `plan_it_tapped` and writes the event. Returns the
     * event's marker for Undo, or the code that stopped it.
     */
    suspend fun planIt(c: AppContainer, plan: WeekPlan, item: PlanItem): Pair<Marker?, String?> {
        val recId = runCatching { c.planSnapshots.week(plan, accepted = true) }.getOrNull() ?: return null to "no_snapshot"
        val content = runCatching { build(c, plan, item, budget(c, item)) }.getOrNull() ?: return null to "nothing_to_train"
        val date = Week.localDate(item.start, PlanningFacts.ZONE)
        c.sessionRepository.event(
            EventDraft(
                type = "plan_it_tapped", screen = "week", entityType = "recommendation", entityId = recId,
                payload = mapOf("date" to JsonPrimitive(date.toString()), "type" to JsonPrimitive(item.type ?: ""), "recommendation_id" to JsonPrimitive(recId)),
            ),
        )
        val err = c.calendar.planIt(recId, item, content.label, content.lines)
        return (if (err == null) Marker(recId, PlanIt.slotKey(date)) else null) to err
    }

    /**
     * The session types for a session at [start] (D84 (1)), each with its allocation, best first: usable ones by
     * `Building.value`, then the rest (disabled in the sheet), ties in the type order.
     */
    suspend fun rank(c: AppContainer, plan: WeekPlan, start: Instant, budgetMin: Double): List<Pair<SessionType, Allocation>> {
        val f = SessionBuild.facts(c, start, weekPlan = plan.takeIf { !it.provisional })
        return SessionBuild.TYPES.map { t -> t to SessionBuild.allocate(c, SessionBuild.request(f, t, budgetMin)) }
            .sortedWith(
                compareByDescending<Pair<SessionType, Allocation>> { UiTrim.usable(it.second) }
                    .thenByDescending { if (UiTrim.usable(it.second)) Building.value(it.second) else 0.0 }
                    .thenBy { it.first.ordinal },
            )
    }

    /** The item of a session of [type] placed at [start] for [budgetMin] (D84 (1)): the event is the budget plus the changeover. */
    fun placed(type: SessionType, start: Instant, budgetMin: Double): PlanItem =
        PlanItem(
            "gen:placed", WeeklyPlanner.KIND_RECOMMENDED, null, type.code, SessionBuild.label(type), start,
            start.plusSeconds(Math.round(budgetMin * 60.0)), budgetMin, writePolicy = "on_plan_it", source = "user",
        )

    /**
     * A session the user placed in the Week grid (D84 (1)): builds it for its day at [budgetMin] and writes it under a
     * new device uuid. Returns the marker for Undo, or the code that stopped it.
     */
    suspend fun create(c: AppContainer, plan: WeekPlan, type: SessionType, start: Instant, budgetMin: Double): Pair<Marker?, String?> {
        val item = placed(type, start, budgetMin)
        val content = runCatching { build(c, plan, item, budgetMin) }.getOrNull() ?: return null to "nothing_to_train"
        val date = Week.localDate(start, PlanningFacts.ZONE)
        c.sessionRepository.event(
            EventDraft(
                type = "plan_it_tapped", screen = "week", entityType = "none",
                payload = mapOf("date" to JsonPrimitive(date.toString()), "type" to JsonPrimitive(type.code)),
            ),
        )
        val uuid = java.util.UUID.randomUUID().toString()
        val err = c.calendar.planIt(uuid, item, content.label, content.lines, cause = "user_create")
        return (if (err == null) Marker(uuid, PlanIt.slotKey(date)) else null) to err
    }

    /** A changed planned session (D92): the event as it was, for Undo, and the marker it carries now. */
    class Changed(val before: CalendarSync.GeneralState, val marker: Marker)

    /**
     * "Change" on a planned general session (D92): the app's event carrying [marker] becomes a session of [type] at
     * [budgetMin] with its start [start] kept: titled by the type, the exercise lines built for its day, the end at the
     * budget plus the changeover, under a new device uuid on the same day (so its type is read from the title, D82 (4)).
     * A `user_edited` link becomes `user_moved`. Returns the change for Undo, or the code that stopped it.
     */
    suspend fun change(c: AppContainer, plan: WeekPlan, marker: Marker, type: SessionType, start: Instant, budgetMin: Double): Pair<Changed?, String?> {
        val before = c.calendar.generalState(marker) ?: return null to "gone"
        val item = placed(type, start, budgetMin)
        val content = runCatching { build(c, plan, item, budgetMin) }.getOrNull() ?: return null to "nothing_to_train"
        val e = PlanIt.event(java.util.UUID.randomUUID().toString(), item.copy(label = content.label), PlanningFacts.ZONE, content.lines, changeover(c), CalendarSync.HUMAN_LINE)
        val state = if (before.link.state == CalendarLink.USER_EDITED) CalendarLink.USER_MOVED else before.link.state
        val to = CalendarSync.GeneralState(e.marker, e.title, e.description, e.end, PlanIt.link(e, before.link.eventId).copy(state = state))
        val err = c.calendar.rewriteGeneral(marker, to, "user_change")
        return (if (err == null) Changed(before, e.marker) else null) to err
    }

    /** Undo of a change (D92): writes the event back as it was, under its old marker and link. */
    suspend fun undoChange(c: AppContainer, changed: Changed): String? = c.calendar.rewriteGeneral(changed.marker, changed.before, "undo")

    /** Undo of "Plan it" (UX 10.4): deletes the still unmodified event and logs `action_undone`. */
    suspend fun undo(c: AppContainer, marker: Marker, tappedAtMs: Long): String? {
        val err = c.calendar.removeGeneral(marker, "undo")
        if (err == null) {
            c.sessionRepository.event(
                EventDraft(
                    type = "action_undone", screen = "week", entityType = "none",
                    payload = mapOf(
                        "undone_event_type" to JsonPrimitive("plan_it_tapped"), "undone_event_id" to JsonPrimitive(marker.slotKey),
                        "ms_since" to JsonPrimitive(System.currentTimeMillis() - tappedAtMs),
                    ),
                ),
            )
        }
        return err
    }

    /**
     * D82 (4): what the unedited, future "Plan it" events of [plan]'s week should show (the plan's session on that day,
     * built at the event's own budget), handed to the calendar writer, which updates them under the edit policy.
     */
    suspend fun offer(c: AppContainer, plan: WeekPlan) {
        val snap = c.calendar.snapshot.value
        val byKey = snap.anchors.associateBy { it.instanceKey }
        val now = c.clock.now()
        val changeover = changeover(c)
        val out = TreeMap<String, PlanIt.Desired>()
        for (i in plan.days.flatMap { it.items }.filter { it.kind == FixedItem.KIND_PLANNED_GYM && it.start.isAfter(now) }) {
            val a = byKey[i.key] ?: continue
            val m = a.marker?.takeIf { it.parsedKey() is SlotKey.General } ?: continue
            val link = snap.links.firstOrNull { it.markerUuid == m.uuid && it.slotKey == m.slotKey } ?: continue
            if (link.state != CalendarLink.ACTIVE && link.state != CalendarLink.USER_MOVED) continue
            val budget = Duration.between(a.beginUtc, a.endUtc).toMinutes() - changeover
            val content = runCatching { build(c, plan, i, budget) }.getOrNull() ?: continue
            // D84 (3): the title names the session type and stays; only the exercise lines follow the plan.
            out[m.render()] = PlanIt.Desired(PlanIt.title(PlanIt.typeName(i.type) ?: content.label), content.lines.joinToString("\n"))
        }
        c.calendar.offerGeneral(setOf(plan.isoWeek), out)
    }
}
