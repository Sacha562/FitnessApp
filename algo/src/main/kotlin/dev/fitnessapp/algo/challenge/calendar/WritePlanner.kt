package dev.fitnessapp.algo.challenge.calendar

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.plan.ChallengeWeekPlan
import dev.fitnessapp.algo.challenge.plan.PlacedSession
import dev.fitnessapp.algo.core.calendar.Anchor
import dev.fitnessapp.algo.core.calendar.AnchorKind
import dev.fitnessapp.algo.core.calendar.CalendarInstance
import dev.fitnessapp.algo.core.calendar.Marker
import dev.fitnessapp.algo.core.calendar.SlotKey
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Sha256Hex
import java.time.Instant
import java.time.ZoneId

/** One event the challenge wants in the calendar (CHALLENGE_ENGINE 9.4 "Desired events"). */
data class DesiredEvent(
    val isoWeek: String,
    val slot: String,
    val title: String,
    /** The English exercise lines; the planner appends the human line and the marker. */
    val body: String,
    val start: Instant,
    val end: Instant,
    val location: String?,
)

/** The local `calendar_link` row (ANDROID 16.6) as the planner sees it. */
data class CalendarLink(
    val markerUuid: String,
    val slotKey: String,
    /** `challenge`, `test` or `general`. */
    val kind: String,
    val eventId: Long?,
    val state: String,
    val lastTitle: String,
    val lastDescSha256: String,
    val lastStart: Instant,
    val lastEnd: Instant,
) {
    val marker: Marker get() = Marker(markerUuid, slotKey)

    companion object {
        const val ACTIVE = "active"
        const val USER_MOVED = "user_moved"
        const val USER_EDITED = "user_edited"
        const val USER_DELETED = "user_deleted"
        const val RELEASED = "released"
        const val DELETED_BY_APP = "deleted_by_app"
    }
}

/** One provider operation; the executor applies a plan in one batch and re-checks every guard (ANDROID 16.7). */
sealed interface WriteOp {
    val slotKey: String

    data class Insert(val event: DesiredEvent, val marker: Marker, val description: String) : WriteOp {
        override val slotKey: String get() = marker.slotKey
    }

    /** [guard] is the marker the event carries now (the atomic guard); [marker] the one it carries afterwards (C36 rewrite). */
    data class Update(
        val eventId: Long,
        val instanceKey: String,
        val guard: Marker,
        val marker: Marker,
        val start: Instant?,
        val end: Instant?,
        val title: String?,
        val description: String?,
        /** The event's start before the update, for the week guard. */
        val currentStart: Instant,
    ) : WriteOp {
        override val slotKey: String get() = marker.slotKey
    }

    data class Delete(val eventId: Long, val instanceKey: String, val guard: Marker, val start: Instant) : WriteOp {
        override val slotKey: String get() = guard.slotKey
    }
}

/** A one-tap question for the user (9.4-9.6); the app never acts on these by itself. */
sealed interface WritePrompt {
    /** An unmarked event matched to a slot: "Adopt". [mismatch] is a C36 conflict ("Adopt and rename"). */
    data class Adopt(val instanceKey: String, val eventId: Long, val isoWeek: String, val slot: String, val mismatch: Boolean) : WritePrompt

    /** A user-edited app event whose slot type changed: "Rename to session B?". */
    data class Rename(val instanceKey: String, val eventId: Long, val fromSlotKey: String, val toSlotKey: String, val title: String) : WritePrompt

    /** An app event that is no longer wanted but may not be deleted automatically. */
    data class Remove(val instanceKey: String, val eventId: Long, val slotKey: String) : WritePrompt

    /** The user deleted this slot's event; "Add to calendar" re-creates it. */
    data class AddToCalendar(val isoWeek: String, val slot: String, val slotKey: String) : WritePrompt
}

/** A link the reconciliation changed or rebuilt (no provider write): the row as it should be stored. */
data class LinkChange(val link: CalendarLink)

data class WritePlan(val ops: List<WriteOp>, val prompts: List<WritePrompt>, val linkChanges: List<LinkChange>)

data class WritePlanInput(
    val enrollmentId: String,
    val slug: String,
    /** The definition's test slot: its links are of kind `test`. */
    val testSlot: String,
    val now: Instant,
    val zone: ZoneId,
    /** The read window; links whose last start lies outside it are not judged (their event was not read). */
    val windowStart: Instant,
    val windowEnd: Instant,
    /** The ISO weeks written automatically (current and next, `writeBack.horizonIsoWeeks`). */
    val horizonWeeks: List<String>,
    val desired: List<DesiredEvent>,
    /** `<isoWeek>/<slot>` of sessions already done: their events are kept. */
    val doneSlots: Set<String>,
    val instances: List<CalendarInstance>,
    val anchors: List<Anchor>,
    val links: List<CalendarLink>,
    val humanLine: String,
)

/**
 * `challenge.calendar` write-back (CHALLENGE_ENGINE 9.4-9.6, D11): compares the desired events of the current and next
 * ISO week with the app's links and the matches and returns the operations, the prompts and the observed link states.
 * The edit policy is ANDROID 16.7's (C37): a user time edit wins, a user-edited title or description stops app updates,
 * the app deletes only its own unmodified future events, and a user deletion is respected. Pure and idempotent: once
 * the executor applied a plan, the same inputs give no operations.
 */
object WritePlanner {
    fun description(body: String, humanLine: String, marker: Marker): String {
        val tail = humanLine + "\n" + marker.render()
        return if (body.isBlank()) tail else body.trimEnd() + "\n\n" + tail
    }

    fun slotKey(slug: String, slot: String, isoWeek: String): String = SlotKey.Challenge(slug, slot, isoWeek).render()

    /** The event title from the definition's templates (F25): `{slot}`, `{slotTitle}` and, for the test slot, `{testNo}`. */
    fun title(def: CompiledDefinition, slot: String, locale: String, testNo: Int): String {
        val wb = def.doc.calendar.writeBack
        val isTest = slot == def.doc.structure.block.testSlot
        val tpl = (if (isTest) wb.testTitleTemplate else wb.titleTemplate).let { it[locale] ?: it[EN] ?: it.values.first() }
        val slotTitle = def.doc.calendar.slotTitles[slot]?.let { it[locale] ?: it[EN] }.orEmpty()
        return tpl.replace("{slot}", slot).replace("{slotTitle}", slotTitle).replace("{testNo}", testNo.toString())
    }

    private const val EN = "en"

    /**
     * The desired events of one week plan (9.4): every placed, not-done session with its title in [locale] and the body
     * the caller renders from the materialized session (English exercise lines, F25). [testNo] is the block index.
     */
    fun desired(
        def: CompiledDefinition,
        plan: ChallengeWeekPlan,
        locale: String,
        testNo: Int,
        location: String?,
        body: (PlacedSession) -> String,
    ): List<DesiredEvent> = plan.sessions.filter { it.source != DONE }.map { p ->
        DesiredEvent(plan.isoWeek, p.slot, title(def, p.slot, locale, testNo), body(p), p.interval.start, p.interval.end, location)
    }

    private const val DONE = "done"

    fun plan(input: WritePlanInput): WritePlan {
        val uuid = input.enrollmentId
        val zone = input.zone
        val instByEvent = input.instances.groupBy { it.eventId }
        val ours = input.instances.mapNotNull { inst ->
            val m = Marker.ofDescription(inst.description) ?: return@mapNotNull null
            val k = m.parsedKey() as? SlotKey.Challenge ?: return@mapNotNull null
            if (m.uuid != uuid || k.slug != input.slug) null else inst to m
        }.sortedWith(compareBy({ it.first.beginUtc }, { it.first.instanceKey }))
        val oursByKey = ours.groupBy { it.second.slotKey }.mapValues { it.value.first() }

        // 1. Reconcile the links with what the calendar shows now.
        val changes = ArrayList<LinkChange>()
        val links = java.util.TreeMap<String, CalendarLink>()
        for (l in input.links.filter { it.markerUuid == uuid }.sortedBy { it.slotKey }) {
            var s = l.state
            var eventId = l.eventId
            val live = oursByKey[l.slotKey]
            if (s == CalendarLink.ACTIVE || s == CalendarLink.USER_MOVED || s == CalendarLink.USER_EDITED) {
                if (live != null) {
                    val inst = live.first
                    eventId = inst.eventId
                    val edited = inst.title.orEmpty() != l.lastTitle || Sha256Hex.ofUtf8(inst.description.orEmpty()) != l.lastDescSha256
                    val moved = inst.beginUtc != l.lastStart || inst.endUtc != l.lastEnd
                    if (edited) s = CalendarLink.USER_EDITED else if (moved && s == CalendarLink.ACTIVE) s = CalendarLink.USER_MOVED
                } else if (inWindow(l.lastStart, input)) {
                    val same = l.eventId?.let { instByEvent[it] }.orEmpty()
                    s = if (same.isNotEmpty() && same.none { Marker.ofDescription(it.description) == l.marker }) CalendarLink.RELEASED else CalendarLink.USER_DELETED
                }
            }
            val now = l.copy(state = s, eventId = eventId)
            if (now != l) changes += LinkChange(now)
            links[l.slotKey] = now
        }
        // A marked event without a link (after a reinstall): rebuilt as unchanged (ANDROID 16.6).
        for ((key, pair) in oursByKey) {
            if (key in links) continue
            val inst = pair.first
            val rebuilt = CalendarLink(
                uuid, key, kindOf(key, input), inst.eventId, CalendarLink.ACTIVE, inst.title.orEmpty(),
                Sha256Hex.ofUtf8(inst.description.orEmpty()), inst.beginUtc, inst.endUtc,
            )
            links[key] = rebuilt
            changes += LinkChange(rebuilt)
        }

        val ops = ArrayList<WriteOp>()
        val prompts = ArrayList<WritePrompt>()
        val consumed = sortedSetOf<String>()
        val matched = input.anchors.filter { it.kind == AnchorKind.CHALLENGE_SESSION && it.enrollmentId == uuid && it.slot != null }

        // 2. Each desired event against its match and link.
        for (d in input.desired.sortedWith(compareBy({ it.isoWeek }, { it.start }, { it.slot }))) {
            if (d.isoWeek !in input.horizonWeeks) continue
            val key = slotKey(input.slug, d.slot, d.isoWeek)
            val marker = Marker(uuid, key)
            val anchor = matched.firstOrNull { it.slot == d.slot && Week.isoWeek(it.localDate) == d.isoWeek }
            val anchorMarker = anchor?.let { a -> ours.firstOrNull { it.first.instanceKey == a.instanceKey }?.second }
            val desc = description(d.body, input.humanLine, marker)
            when {
                anchor == null -> {
                    val link = links[key]
                    when {
                        link?.state == CalendarLink.USER_DELETED -> prompts += WritePrompt.AddToCalendar(d.isoWeek, d.slot, key)
                        link != null && link.state != CalendarLink.DELETED_BY_APP -> Unit
                        d.start.isAfter(input.now) -> ops += WriteOp.Insert(d, marker, desc)
                    }
                }
                anchorMarker != null -> {
                    consumed += anchorMarker.slotKey
                    val inst = ours.first { it.first.instanceKey == anchor.instanceKey }.first
                    val link = links.getValue(anchorMarker.slotKey)
                    if (!inst.beginUtc.isAfter(input.now)) continue
                    when (link.state) {
                        CalendarLink.ACTIVE, CalendarLink.USER_MOVED -> {
                            val moveOk = link.state == CalendarLink.ACTIVE && inst.beginUtc == link.lastStart && inst.endUtc == link.lastEnd
                            val start = if (moveOk && d.start != inst.beginUtc) d.start else null
                            val end = if (moveOk && d.end != inst.endUtc) d.end else null
                            val title = if (d.title != inst.title.orEmpty()) d.title else null
                            val description = if (desc != inst.description.orEmpty()) desc else null
                            if (start != null || end != null || title != null || description != null) {
                                ops += WriteOp.Update(inst.eventId, inst.instanceKey, anchorMarker, marker, start, end, title, description, inst.beginUtc)
                            }
                        }
                        CalendarLink.USER_EDITED ->
                            if (anchorMarker.slotKey != key) prompts += WritePrompt.Rename(inst.instanceKey, inst.eventId, anchorMarker.slotKey, key, d.title)
                        else -> Unit
                    }
                }
                else -> {
                    val inst = instByEvent[anchor.eventId].orEmpty().firstOrNull { it.instanceKey == anchor.instanceKey }
                    // An event whose marker the user removed (released) is never offered again (ANDROID 16.7).
                    val released = links.values.any { it.state == CalendarLink.RELEASED && it.eventId == anchor.eventId }
                    if (inst != null && inst.calendarWritable && inst.beginUtc.isAfter(input.now) && !released) {
                        prompts += WritePrompt.Adopt(anchor.instanceKey, anchor.eventId, d.isoWeek, d.slot, anchor.attention != null)
                    }
                }
            }
        }

        // 3. The app's events that no desired slot claims any more (pause, abandon, a structural change).
        for ((inst, m) in ours) {
            if (m.slotKey in consumed) continue
            val k = m.parsedKey() as SlotKey.Challenge
            if (k.isoWeek !in input.horizonWeeks && Week.isoWeek(Week.localDate(inst.beginUtc, zone)) !in input.horizonWeeks) continue
            if ("${k.isoWeek}/${k.slot}" in input.doneSlots) continue
            if (!inst.beginUtc.isAfter(input.now)) continue
            val link = links[m.slotKey]
            if (link?.state == CalendarLink.ACTIVE) {
                ops += WriteOp.Delete(inst.eventId, inst.instanceKey, m, inst.beginUtc)
            } else if (link?.state != CalendarLink.RELEASED) {
                prompts += WritePrompt.Remove(inst.instanceKey, inst.eventId, m.slotKey)
            }
        }
        return WritePlan(ops, prompts, changes)
    }

    /**
     * The links after the executor applied [plan] in one successful batch (ANDROID 16.6): the reconciled rows, a new
     * `active` row per insert (with the provider id from [insertedIds], keyed by slot key), the written values per update
     * (re-keyed on a C36 rewrite; a user's moved time stays the user's), and `deleted_by_app` per delete.
     */
    fun linksAfter(links: List<CalendarLink>, plan: WritePlan, enrollmentId: String, testSlot: String, insertedIds: Map<String, Long>): List<CalendarLink> {
        val out = java.util.TreeMap<String, CalendarLink>()
        for (l in links) out[l.markerUuid + " " + l.slotKey] = l
        for (c in plan.linkChanges) out[c.link.markerUuid + " " + c.link.slotKey] = c.link
        for (op in plan.ops) {
            when (op) {
                is WriteOp.Insert -> {
                    val kind = if ((op.marker.parsedKey() as? SlotKey.Challenge)?.slot == testSlot) TEST_KIND else CHALLENGE
                    out[enrollmentId + " " + op.marker.slotKey] = CalendarLink(
                        enrollmentId, op.marker.slotKey, kind, insertedIds[op.marker.slotKey], CalendarLink.ACTIVE, op.event.title,
                        Sha256Hex.ofUtf8(op.description), op.event.start, op.event.end,
                    )
                }
                is WriteOp.Update -> {
                    val oldKey = op.guard.uuid + " " + op.guard.slotKey
                    val old = out.remove(oldKey) ?: continue
                    val kind = if ((op.marker.parsedKey() as? SlotKey.Challenge)?.slot == testSlot) TEST_KIND else CHALLENGE
                    out[op.marker.uuid + " " + op.marker.slotKey] = old.copy(
                        slotKey = op.marker.slotKey, kind = kind, eventId = op.eventId,
                        lastTitle = op.title ?: old.lastTitle,
                        lastDescSha256 = op.description?.let { Sha256Hex.ofUtf8(it) } ?: old.lastDescSha256,
                        lastStart = op.start ?: old.lastStart, lastEnd = op.end ?: old.lastEnd,
                    )
                }
                is WriteOp.Delete -> {
                    val key = op.guard.uuid + " " + op.guard.slotKey
                    out[key]?.let { out[key] = it.copy(state = CalendarLink.DELETED_BY_APP) }
                }
            }
        }
        return out.values.toList()
    }

    private fun inWindow(t: Instant, input: WritePlanInput): Boolean = !t.isBefore(input.windowStart) && t.isBefore(input.windowEnd)

    private fun kindOf(key: String, input: WritePlanInput): String {
        val k = SlotKey.parse(key) as? SlotKey.Challenge ?: return GENERAL
        return if (k.slot == input.testSlot) TEST_KIND else CHALLENGE
    }

    private const val CHALLENGE = "challenge"
    private const val TEST_KIND = "test"
    private const val GENERAL = "general"
}
