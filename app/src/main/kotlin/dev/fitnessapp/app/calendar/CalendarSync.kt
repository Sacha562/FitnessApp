package dev.fitnessapp.app.calendar

import android.Manifest
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import dev.fitnessapp.algo.challenge.calendar.CalendarLink
import dev.fitnessapp.algo.challenge.calendar.EnrollmentViews
import dev.fitnessapp.algo.challenge.calendar.SlotMatcher
import dev.fitnessapp.algo.challenge.calendar.WriteOp
import dev.fitnessapp.algo.challenge.calendar.WritePlan
import dev.fitnessapp.algo.challenge.calendar.WritePlanInput
import dev.fitnessapp.algo.challenge.calendar.WritePlanner
import dev.fitnessapp.algo.challenge.calendar.WritePrompt
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentFold
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.materialize.MaterializeInput
import dev.fitnessapp.algo.challenge.materialize.Materializer
import dev.fitnessapp.algo.challenge.plan.WeekPlanner
import dev.fitnessapp.algo.core.calendar.Anchor
import dev.fitnessapp.algo.core.calendar.AnchorKind
import dev.fitnessapp.algo.core.calendar.CalendarClassification
import dev.fitnessapp.algo.core.calendar.CalendarClassifier
import dev.fitnessapp.algo.core.calendar.CalendarInstance
import dev.fitnessapp.algo.core.calendar.CalendarKeys
import dev.fitnessapp.algo.core.calendar.ClassifyContext
import dev.fitnessapp.algo.core.calendar.Marker
import dev.fitnessapp.algo.core.calendar.OpeningHours
import dev.fitnessapp.algo.core.calendar.SlotKey
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.BuildConfig
import dev.fitnessapp.app.ui.challenges.ChallengeLines
import dev.fitnessapp.app.ui.challenges.ChallengeWeek
import dev.fitnessapp.data.calendar.CalendarRepository
import dev.fitnessapp.data.challenge.FoldedEnrollment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** A prompt with the enrollment it belongs to. */
data class CalendarPrompt(val enrollmentId: String, val prompt: WritePrompt)

/** What the calendar screens show (UX 15.6, 16.11, the dashboard's This week row; D39). */
data class CalendarSnapshot(
    /** `ok`, `permission_denied`, `calendar_unavailable` or `off` (reading switched off in the profile). */
    val status: String = "off",
    val canWrite: Boolean = false,
    val calendars: List<ProviderCalendar> = emptyList(),
    val primary: ProviderCalendar? = null,
    /** More than one candidate and none chosen: the user must pick (16.3). */
    val needsPick: Boolean = false,
    val testCalendar: ProviderCalendar? = null,
    val readIds: List<Long> = emptyList(),
    val target: WriteTarget? = null,
    /** Why the plan is shown but not executed: `no_permission`, `no_target`, `off`, `sandbox`, or null. */
    val writeOff: String? = null,
    val anchors: List<Anchor> = emptyList(),
    val instances: Map<String, CalendarInstance> = emptyMap(),
    val prompts: List<CalendarPrompt> = emptyList(),
    /** Ops computed but not executed (writes off or refused). */
    val pending: List<WriteOp> = emptyList(),
    val links: List<CalendarLink> = emptyList(),
    val refreshedAt: Instant? = null,
    val error: String? = null,
)

/**
 * `CalendarSyncController` (ANDROID 16): reads the selected calendars, classifies the window with `calendar.classify`,
 * stores the result, builds each enrollment's write plan with `challenge.calendar` and lets [CalendarWriteExecutor] apply
 * it. Runs at app start, on a debounced provider change, in the daily maintenance and after challenge changes. Writes go
 * to the device-only test calendar until the user allows real writes (D39, ANDROID 16.10); the sandbox build never
 * writes to a real calendar (F27).
 */
class CalendarSync(private val c: AppContainer) {
    private val gateway = CalendarProviderGateway(c.appContext)
    private val executor = CalendarWriteExecutor(gateway) { BuildConfig.USER_CALENDAR_WRITES_ENABLED && c.settings.calendarRealWrites }
    val repo: CalendarRepository by lazy { CalendarRepository(c.db, c.outbox, c.telemetry, c.writeContext, c.syncScheduler) }
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(CalendarSnapshot())
    val snapshot: StateFlow<CalendarSnapshot> = _snapshot
    private var observer: ContentObserver? = null
    private var pendingRefresh: Job? = null

    fun canRead(): Boolean = granted(Manifest.permission.READ_CALENDAR)
    fun canWrite(): Boolean = granted(Manifest.permission.WRITE_CALENDAR)
    private fun granted(p: String) = ContextCompat.checkSelfPermission(c.appContext, p) == PackageManager.PERMISSION_GRANTED

    /** Registers the provider observer (debounced, ANDROID 16.4) while the process lives. */
    fun start() {
        if (observer != null || !canRead()) return
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = requestRefresh("observer")
        }
        runCatching { c.appContext.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, o) }
            .onSuccess { observer = o }
    }

    fun requestRefresh(cause: String, delayMs: Long = DEBOUNCE_MS) {
        pendingRefresh?.cancel()
        // Only the wait is cancelled by a newer request; a refresh that started runs to the end (its writes and links
        // belong together), because it runs in its own job.
        pendingRefresh = c.appScope.launch {
            delay(delayMs)
            c.appScope.launch { refresh(cause) }
        }
    }

    /** One full refresh. Never throws; a provider failure becomes degraded mode (16.2). */
    suspend fun refresh(cause: String) = mutex.withLock {
        try {
            refreshLocked(cause)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SecurityException) {
            degraded("permission")
        } catch (e: Exception) {
            Log.w(TAG, "calendar refresh failed", e)
            degraded("provider_error", e.message)
        }
    }

    private suspend fun degraded(reason: String, message: String? = null) {
        _snapshot.value = CalendarSnapshot(status = if (reason == "permission") CalendarClassification.PERMISSION_DENIED else CalendarClassification.UNAVAILABLE, error = message)
        if (c.settings.calendarUnavailableReason != reason) {
            c.settings.calendarUnavailableReason = reason
            // Logging must not turn degraded mode into a crash (no account yet, storage not ready).
            runCatching { repo.log("calendar_unavailable", mapOf("reason" to reason)) }
        }
    }

    private suspend fun refreshLocked(cause: String) {
        val profile = c.profiles.get()
        if (profile?.calendar_read_enabled == false) {
            _snapshot.value = CalendarSnapshot(status = "off")
            return
        }
        if (!canRead()) return degraded("permission")
        start()
        c.settings.calendarUnavailableReason = null
        val now = c.clock.now()
        val zone = ChallengeWeek.ZONE
        val monday = Week.isoWeekStart(Week.localDate(now, zone))
        val from = Week.atLocal(monday.minusDays(WINDOW_BACK_DAYS), LocalTime.MIDNIGHT, zone)
        val to = Week.atLocal(monday.plusDays(WINDOW_AHEAD_DAYS), LocalTime.MIDNIGHT, zone)

        val calendars = gateway.calendars()
        val (primary, needsPick) = primaryOf(calendars)
        val test = calendars.firstOrNull { it.isTestCalendar }
        val extras = c.settings.calendarExtraIdentities
        val read = calendars.filter { cal ->
            cal == primary || cal == test || (cal.identity in extras && !TODOIST.containsMatchIn(cal.displayName))
        }
        val readIds = read.map { it.id }
        val writeToTest = !BuildConfig.USER_CALENDAR_WRITES_ENABLED || !c.settings.calendarRealWrites
        val targetCal = if (writeToTest) test else primary
        val target = targetCal?.let { WriteTarget(it.id, it.accessLevel, it.isTestCalendar, identityHash(it)) }
        val writeOff = when {
            !canWrite() -> "no_permission"
            profile?.calendar_write_enabled == false -> "off"
            target == null -> if (writeToTest) "sandbox" else "no_target"
            else -> null
        }

        val raw = gateway.instances(readIds, from.toEpochMilli(), to.toEpochMilli())
        val byId = calendars.associateBy { it.id }
        val instances = raw.map { r ->
            val cal = byId.getValue(r.calendarId)
            val eventKey = r.eventSyncId ?: r.eventId.toString()
            val calKey = cal.syncId ?: cal.id.toString()
            CalendarInstance(
                instanceKey = CalendarKeys.instanceKey(cal.accountType, cal.accountName, calKey, eventKey, r.begin),
                seriesKey = CalendarKeys.seriesKey(cal.accountType, cal.accountName, calKey, eventKey),
                eventId = r.eventId, calendarId = r.calendarId, calendarName = cal.displayName,
                calendarWritable = target != null && cal.id == target.calendarId,
                beginUtc = Instant.ofEpochMilli(r.begin), endUtc = Instant.ofEpochMilli(maxOf(r.end, r.begin)), allDay = r.allDay,
                title = r.title, description = r.description, location = r.location, status = CalendarProviderGateway.status(r.status),
                declined = r.selfAttendeeStatus == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
                availabilityFree = r.availability == CalendarContract.Events.AVAILABILITY_FREE,
            )
        }

        val gym = c.equipment.defaultGym()
        val hours = gym?.opening_hours?.let(::openingHours).orEmpty()
        val enrollments = c.challenges.open().mapNotNull { e -> runCatching { c.challenges.folded(e.id) }.getOrNull() }
        val active = enrollments.filter { it.state.status == EnrollmentState.ACTIVE }
        val views = active.map { f ->
            val facts = c.challengeFacts.facts(f.def, f.state)
            EnrollmentViews.of(f.enrollment.id, f.def, c.params, f.state, facts, listOf(monday.minusWeeks(1), monday, monday.plusWeeks(1)), hours)
        }
        val ctx = ClassifyContext(
            now = now, zone = zone, allowlist = readIds.toSet(),
            ignoreCalendarNamePatterns = profile?.calendar_ignore_name_patterns ?: listOf("(?i)todoist"),
            ignoreAllDay = profile?.calendar_ignore_all_day ?: true,
            gymLocationKeywords = gym?.location_keywords.orEmpty().ifEmpty { DEFAULT_GYM_KEYWORDS },
            enrollments = views, overrides = repo.overrides(c.settings.calendarSeriesOverrides),
            catalogNames = c.catalog.doc.exercises.flatMap { listOfNotNull(it.nameEn, it.nameNl) + it.aliases },
        )
        val cls = CalendarClassifier.classify(instances, ctx, c.params, SlotMatcher.matching(c.params, zone))
        val identities = read.associate { it.id to identityHash(it) }
        repo.storeRefresh(instances, identities, cls.anchors, from.toEpochMilli(), to.toEpochMilli())
        // C35: finished sessions of last week and this week link to the event on their day (within 3 h).
        val finished = (c.db.sessions().finishedInIsoWeek(Week.isoWeek(monday.minusWeeks(1))) + c.db.sessions().finishedInIsoWeek(Week.isoWeek(monday)))
            .filter { it.habit_id == null && it.started_at != null && it.deleted_at == null }
        for (s in finished) {
            val a = CalendarClassifier.linkSession(Instant.ofEpochMilli(s.started_at!!), s.challenge_slot, cls.anchors, zone, c.params) ?: continue
            repo.linkSession(a.instanceKey, s.id)
        }
        // The planner of every screen reads these anchors from now on.
        _snapshot.value = _snapshot.value.copy(status = CalendarClassification.OK, anchors = cls.anchors)

        // Write-back per enrollment that has events or links (an abandoned one still removes its future events).
        val links = repo.links()
        val ids = (enrollments.map { it.enrollment.id } + links.map { it.markerUuid }).distinct().sorted()
        val prompts = ArrayList<CalendarPrompt>()
        val pending = ArrayList<WriteOp>()
        var anyWritten = false
        val updatedKeys = sortedSetOf<String>()
        val eventCalendar = instances.associate { it.eventId to it.calendarId }
        for (id in ids) {
            val f = enrollments.firstOrNull { it.enrollment.id == id }
            val slug = f?.def?.slug ?: links.firstNotNullOfOrNull { l -> (SlotKey.parse(l.slotKey) as? SlotKey.Challenge)?.slug?.takeIf { l.markerUuid == id } } ?: continue
            val wb = f?.def?.doc?.calendar?.writeBack
            if (wb != null && wb.mode != "auto") continue
            val desired = if (f != null && f.state.status == EnrollmentState.ACTIVE) desiredOf(f, now, gym?.name) else emptyList()
            val horizon = (0 until (wb?.horizonIsoWeeks ?: HORIZON)).map { Week.isoWeek(monday.plusWeeks(it.toLong())) }
            val done = c.db.sessions().finishedInIsoWeek(Week.isoWeek(monday)).filter { it.enrollment_id == id && it.challenge_slot != null }
                .map { "${Week.isoWeek(LocalDate.parse(it.local_date))}/${it.challenge_slot}" }.toSet()
            val input = WritePlanInput(
                id, slug, f?.def?.doc?.structure?.block?.testSlot ?: "TEST", now, zone, from, to, horizon, desired, done,
                instances, cls.anchors, links, wb?.humanLine?.get("en") ?: HUMAN_LINE,
            )
            val plan = WritePlanner.plan(input)
            prompts += plan.prompts.map { CalendarPrompt(id, it) }
            var applied = WritePlan(emptyList(), emptyList(), plan.linkChanges)
            var inserted: Map<String, Long> = emptyMap()
            if (writeOff == null && target != null && plan.ops.isNotEmpty()) {
                val r = executor.execute(plan.ops, target, eventCalendar, now, zone, gym?.name)
                logResult(r, plan, cause)
                pending += r.refused.map { it.first }
                if (r.failure != null) {
                    pending += plan.ops
                } else {
                    anyWritten = anyWritten || r.applied.isNotEmpty()
                    applied = WritePlan(r.applied, emptyList(), plan.linkChanges + r.markerMissing.mapNotNull { releasedLink(it, links) })
                    updatedKeys += r.applied.filterIsInstance<WriteOp.Update>().map { it.marker.slotKey }
                    inserted = r.insertedIds
                }
            } else {
                pending += plan.ops
            }
            if (applied.ops.isNotEmpty() || applied.linkChanges.isNotEmpty()) saveLinks(id, links, applied, inserted, target, instances, input.testSlot)
        }
        val finalLinks = repo.links()
        val byMarker = instances.mapNotNull { i -> Marker.ofDescription(i.description)?.let { it.render() to i.instanceKey } }.toMap()
        repo.applyLinkStates(finalLinks, byMarker, instances.associate { it.eventId to it.instanceKey }, updatedKeys)
        _snapshot.value = CalendarSnapshot(
            status = CalendarClassification.OK, canWrite = canWrite(), calendars = calendars, primary = primary, needsPick = needsPick,
            testCalendar = test, readIds = readIds, target = target, writeOff = writeOff, anchors = cls.anchors,
            instances = instances.associateBy { it.instanceKey }, prompts = prompts, pending = pending, links = finalLinks, refreshedAt = now,
        )
        // Our own inserts and updates come back through the observer; refresh once more so the new events are classified.
        if (anyWritten) requestRefresh("after_write", AFTER_WRITE_MS)
    }

    private suspend fun saveLinks(
        id: String,
        links: List<CalendarLink>,
        plan: WritePlan,
        inserted: Map<String, Long>,
        target: WriteTarget?,
        instances: List<CalendarInstance>,
        testSlot: String,
    ) {
        val next = WritePlanner.linksAfter(links, plan, id, testSlot, inserted)
        repo.saveLinks(id, next, target?.identity.orEmpty())
    }

    private fun releasedLink(op: WriteOp, links: List<CalendarLink>): dev.fitnessapp.algo.challenge.calendar.LinkChange? {
        val guard = when (op) {
            is WriteOp.Update -> op.guard
            is WriteOp.Delete -> op.guard
            is WriteOp.Insert -> return null
        }
        val l = links.firstOrNull { it.markerUuid == guard.uuid && it.slotKey == guard.slotKey } ?: return null
        return dev.fitnessapp.algo.challenge.calendar.LinkChange(l.copy(state = CalendarLink.RELEASED))
    }

    private suspend fun logResult(r: ExecResult, plan: WritePlan, cause: String) {
        for (op in r.applied) {
            val kind = "challenge"
            repo.log(
                "calendar_event_written",
                mapOf(
                    "op" to opCode(op), "kind" to kind, "instance_key" to ((op as? WriteOp.Update)?.instanceKey ?: (op as? WriteOp.Delete)?.instanceKey),
                    "marker_key" to op.slotKey, "cause" to cause, "provider_rows" to 1,
                ),
            )
        }
        for (op in r.markerMissing) repo.log("calendar_write_failed", mapOf("op" to opCode(op), "error_class" to "marker_missing"))
        for ((op, code) in r.refused) repo.log("calendar_write_failed", mapOf("op" to opCode(op), "error_class" to code))
        if (r.failure != null) for (op in plan.ops) repo.log("calendar_write_failed", mapOf("op" to opCode(op), "error_class" to r.failure))
    }

    private fun opCode(op: WriteOp): String = when (op) {
        is WriteOp.Insert -> "insert"
        is WriteOp.Update -> "update"
        is WriteOp.Delete -> "delete"
    }

    /** The desired events of the current and next ISO week (9.4), next week from a dry-run catch-up to its Monday. */
    private suspend fun desiredOf(f: FoldedEnrollment, now: Instant, location: String?): List<dev.fitnessapp.algo.challenge.calendar.DesiredEvent> {
        val zone = ChallengeWeek.ZONE
        val out = ArrayList<dev.fitnessapp.algo.challenge.calendar.DesiredEvent>()
        val wb = f.def.doc.calendar.writeBack
        val locale = wb.titleLocaleDefault
        for (w in 0 until wb.horizonIsoWeeks) {
            val folded = if (w == 0) f else project(f, now, w)
            val facts = c.challengeFacts.facts(folded.def, folded.state)
            val (_, plan) = ChallengeWeek.plan(c, folded, facts, now, weekOffset = w)
            out += WritePlanner.desired(folded.def, plan, locale, folded.state.block.index, location) { p ->
                runCatching {
                    val sp = Materializer.materialize(
                        folded.def, c.params,
                        MaterializeInput(p.slot, p.date, folded.state, facts, c.catalog.doc, changeoverMin = AppContainer.CHANGEOVER_MIN),
                    )
                    ChallengeLines.descriptionLines(sp).joinToString("\n")
                }.getOrDefault("")
            }
        }
        return out
    }

    /** The enrollment as it will stand on the Monday [weeks] ahead: the due ticks folded in a dry run (nothing stored). */
    private fun project(f: FoldedEnrollment, now: Instant, weeks: Int): FoldedEnrollment {
        val zone = ChallengeWeek.ZONE
        val monday = Week.isoWeekStart(Week.localDate(now, zone)).plusWeeks(weeks.toLong())
        val at = Week.atLocal(monday, LocalTime.of(PROJECT_HOUR, 0), zone)
        val state = runCatching { EnrollmentFold.catchUp(f.def, c.params, f.state, at, zone).second.lastOrNull()?.state }.getOrNull() ?: f.state
        return FoldedEnrollment.projected(f, state)
    }

    /** 16.3: the stored identity, else the single Google primary candidate; several candidates need the user's pick. */
    private fun primaryOf(calendars: List<ProviderCalendar>): Pair<ProviderCalendar?, Boolean> {
        c.settings.calendarPrimaryIdentity?.let { id -> calendars.firstOrNull { it.identity == id }?.let { return it to false } }
        val candidates = calendars.filter {
            it.accountType == GOOGLE && (it.isPrimary == true || (it.isPrimary == null && it.ownerAccount == it.accountName)) &&
                it.accessLevel >= ACCESS_OWNER && it.syncEvents && !TODOIST.containsMatchIn(it.displayName)
        }.sortedWith(compareBy({ it.accountName }, { it.id }))
        return when (candidates.size) {
            1 -> candidates.single().also { c.settings.calendarPrimaryIdentity = it.identity } to false
            0 -> null to false
            else -> null to true
        }
    }

    fun identityHash(cal: ProviderCalendar): String = CalendarKeys.calendarIdentity(cal.accountName, cal.accountType, cal.ownerAccount)

    // ------------------------------------------------------------------------------------------ user actions

    /** Creates the device-only test calendar (ANDROID 16.10, debug and sandbox builds). */
    suspend fun createTestCalendar() {
        if (!canWrite()) return
        mutex.withLock {
            if (gateway.calendars().none { it.isTestCalendar }) gateway.createTestCalendar(ChallengeWeek.ZONE.id)
        }
        refresh("test_calendar")
    }

    suspend fun selectPrimary(identity: String) {
        c.settings.calendarPrimaryIdentity = identity
        val s = _snapshot.value
        repo.log("calendar_selection_saved", mapOf("count_selected" to 1 + c.settings.calendarExtraIdentities.size, "primary_selected" to true))
        refresh("selection")
        if (s.needsPick) Unit
    }

    suspend fun toggleExtra(identity: String) {
        val cur = c.settings.calendarExtraIdentities
        c.settings.calendarExtraIdentities = if (identity in cur) cur - identity else cur + identity
        repo.log("calendar_selection_saved", mapOf("count_selected" to 1 + c.settings.calendarExtraIdentities.size, "primary_selected" to (c.settings.calendarPrimaryIdentity != null)))
        refresh("selection")
    }

    /** Adopt (9.5): appends the marker of the matched slot to an unmarked event in the write target. */
    suspend fun adopt(p: CalendarPrompt): String? {
        val a = p.prompt as? WritePrompt.Adopt ?: return "not_adoptable"
        val s = _snapshot.value
        val target = s.target ?: return "no_target"
        val inst = s.instances[a.instanceKey] ?: return "gone"
        val f = runCatching { c.challenges.folded(p.enrollmentId) }.getOrNull() ?: return "no_enrollment"
        val marker = Marker(p.enrollmentId, WritePlanner.slotKey(f.def.slug, a.slot, a.isoWeek))
        val rows = try {
            executor.adopt(inst.eventId, inst.calendarId, target, inst.description, marker)
        } catch (e: CalendarWriteExecutor.GuardException) {
            repo.log("calendar_write_failed", mapOf("op" to "update", "error_class" to e.code))
            return e.code
        } catch (e: Exception) {
            Log.w(TAG, "adopt failed", e)
            repo.log("calendar_write_failed", mapOf("op" to "update", "error_class" to "provider_error"))
            return "provider_error"
        }
        if (rows == 0) return "already_marked"
        val anchor = s.anchors.firstOrNull { it.instanceKey == a.instanceKey }
        val cached = c.db.calendarInstances().get(a.instanceKey)
        if (anchor != null && cached != null) repo.markAdopted(anchor, cached, a.slot)
        mutex.withLock {
            val links = repo.links()
            val desc = Marker.withMarker(inst.description, marker)
            val link = CalendarLink(
                p.enrollmentId, marker.slotKey, if (a.slot == TEST_SLOT) "test" else "challenge", inst.eventId, CalendarLink.ACTIVE,
                inst.title.orEmpty(), dev.fitnessapp.algo.core.det.Sha256Hex.ofUtf8(desc), inst.beginUtc, inst.endUtc,
            )
            repo.saveLinks(p.enrollmentId, links.filter { it.slotKey != marker.slotKey } + link, target.identity)
        }
        refresh("adopt")
        return null
    }

    /** "Rename to session B?" (C36) on a user-edited app event: the app writes the new title, description and marker once. */
    suspend fun rename(p: CalendarPrompt): String? {
        val r = p.prompt as? WritePrompt.Rename ?: return "not_renamable"
        val s = _snapshot.value
        val target = s.target ?: return "no_target"
        val inst = s.instances[r.instanceKey] ?: return "gone"
        val old = Marker.ofDescription(inst.description) ?: return "marker_missing"
        val marker = Marker(p.enrollmentId, r.toSlotKey)
        val op = WriteOp.Update(inst.eventId, inst.instanceKey, old, marker, null, null, r.title, Marker.withMarker(inst.description, marker), inst.beginUtc)
        val res = executor.userConfirmed(op, target, s.instances.values.associate { it.eventId to it.calendarId }, c.clock.now())
        repo.log("calendar_conflict_resolved", mapOf("instance_key" to r.instanceKey, "choice" to "rename"))
        if (res.applied.isEmpty()) return res.refused.firstOrNull()?.second ?: res.failure ?: "marker_missing"
        mutex.withLock {
            val links = repo.links()
            val l = links.firstOrNull { it.markerUuid == p.enrollmentId && it.slotKey == old.slotKey }
            if (l != null) {
                val next = l.copy(slotKey = marker.slotKey, state = CalendarLink.ACTIVE, lastTitle = r.title, lastDescSha256 = dev.fitnessapp.algo.core.det.Sha256Hex.ofUtf8(op.description!!), lastStart = inst.beginUtc, lastEnd = inst.endUtc)
                repo.saveLinks(p.enrollmentId, links.filter { it !== l } + next, target.identity)
            }
        }
        refresh("rename")
        return null
    }

    /** "Remove ... from your calendar?" on an app event the app may not delete on its own. */
    suspend fun remove(p: CalendarPrompt): String? {
        val r = p.prompt as? WritePrompt.Remove ?: return "not_removable"
        val s = _snapshot.value
        val target = s.target ?: return "no_target"
        val inst = s.instances[r.instanceKey] ?: return "gone"
        val marker = Marker.ofDescription(inst.description) ?: return "marker_missing"
        val res = executor.userConfirmed(WriteOp.Delete(inst.eventId, inst.instanceKey, marker, inst.beginUtc), target, s.instances.values.associate { it.eventId to it.calendarId }, c.clock.now())
        if (res.applied.isEmpty()) return res.refused.firstOrNull()?.second ?: res.failure ?: "marker_missing"
        repo.log("calendar_event_written", mapOf("op" to "delete", "kind" to "challenge", "instance_key" to r.instanceKey, "marker_key" to r.slotKey, "cause" to "user_remove", "provider_rows" to 1))
        mutex.withLock {
            val links = repo.links()
            repo.saveLinks(p.enrollmentId, links.map { if (it.markerUuid == p.enrollmentId && it.slotKey == r.slotKey) it.copy(state = CalendarLink.DELETED_BY_APP) else it }, target.identity)
        }
        refresh("remove")
        return null
    }

    /** "Add to calendar" after the user deleted a slot's event: the link no longer blocks the insert. */
    suspend fun addToCalendar(p: CalendarPrompt) {
        val a = p.prompt as? WritePrompt.AddToCalendar ?: return
        mutex.withLock {
            val links = repo.links()
            repo.saveLinks(p.enrollmentId, links.map { if (it.markerUuid == p.enrollmentId && it.slotKey == a.slotKey) it.copy(state = CalendarLink.DELETED_BY_APP) else it }, _snapshot.value.target?.identity.orEmpty())
        }
        refresh("add_to_calendar")
    }

    /** A correction from the event sheet (UX 16.11): [kind] null removes the override; [series] applies it to the whole series. */
    suspend fun correct(instanceKey: String, kind: String?, slot: String?, enrollmentId: String?, gymType: String?, series: Boolean) {
        val s = _snapshot.value
        val anchor = s.anchors.firstOrNull { it.instanceKey == instanceKey } ?: return
        val cached = c.db.calendarInstances().get(instanceKey) ?: return
        val cur = c.settings.calendarSeriesOverrides
        c.settings.calendarSeriesOverrides = if (series && kind != null) cur + anchor.seriesKey else cur - anchor.seriesKey
        val from = anchor.slot?.let { "${anchor.kind.code}:$it" } ?: anchor.gymType?.let { "${anchor.kind.code}:$it" } ?: anchor.kind.code
        repo.setOverride(anchor, cached, kind, slot, enrollmentId, gymType, if (series) "series" else "event", from)
        refresh("correction")
    }

    /** Debug and checks: switches the write target to the user's calendar (D39, after T17 and the user's OK). */
    fun setRealWrites(on: Boolean) {
        c.settings.calendarRealWrites = on
        requestRefresh("real_writes", 0)
    }

    private fun openingHours(json: String): List<OpeningHours> = runCatching {
        val o = Json.parseToJsonElement(json).jsonObject
        DAYS.mapIndexedNotNull { i, d ->
            val first = o[d]?.jsonArray?.firstOrNull()?.jsonArray ?: return@mapIndexedNotNull null
            val open = LocalTime.parse(first[0].jsonPrimitive.content)
            val close = LocalTime.parse(first[1].jsonPrimitive.content)
            OpeningHours(i + 1, open.toSecondOfDay() / SECONDS_PER_MIN, close.toSecondOfDay() / SECONDS_PER_MIN)
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "CalendarSync"
        /** ANDROID 16.4 `calendar.observerDebounceMs`. */
        const val DEBOUNCE_MS = 2000L
        private const val AFTER_WRITE_MS = 1500L
        /** The read window `[Monday - 7 days, Monday + 14 days)` (16.4). */
        private const val WINDOW_BACK_DAYS = 7L
        private const val WINDOW_AHEAD_DAYS = 14L
        private const val HORIZON = 2
        private const val PROJECT_HOUR = 5
        private const val SECONDS_PER_MIN = 60
        private const val ACCESS_OWNER = 700
        private const val GOOGLE = "com.google"
        private const val TEST_SLOT = "TEST"
        private const val HUMAN_LINE = "Planned by FitnessApp. If you move this event, the app keeps your new time."
        private val TODOIST = Regex("(?i)todoist")
        private val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        private val DEFAULT_GYM_KEYWORDS = listOf("basic-fit", "basicfit", "basic fit", "ierse pond")
    }
}
