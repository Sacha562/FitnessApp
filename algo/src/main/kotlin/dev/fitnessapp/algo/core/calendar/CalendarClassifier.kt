package dev.fitnessapp.algo.core.calendar

import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamType
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/** Everything `calendar.classify` reads besides the instances (ALGORITHMS_PLANNING 1.2). */
data class ClassifyContext(
    val now: Instant,
    val zone: ZoneId,
    /** Calendar ids to read; others are skipped without a trace. */
    val allowlist: Set<Long>,
    /** `profiles.calendar_ignore_name_patterns`, default `(?i)todoist` (D11). */
    val ignoreCalendarNamePatterns: List<String>,
    val ignoreAllDay: Boolean,
    /** `gyms.location_keywords`, normalised by the classifier. */
    val gymLocationKeywords: List<String>,
    val enrollments: List<EnrollmentView>,
    /** Overrides keyed by instance key or series key (the instance key wins). */
    val overrides: Map<String, CalendarOverride>,
    /** Exercise names and aliases in English and Dutch (D12) for the description feature. */
    val catalogNames: List<String>,
)

/** `challenge.calendar.matchSlots`: the classifier calls it in stage B and never re-implements it. */
fun interface SlotMatching {
    fun matchSlots(view: EnrollmentView, candidates: List<GymCandidate>): List<SlotMatch>
}

/**
 * `calendar.classify` (CalendarClassifier.v1, C20, D11, D12, F6; ALGORITHMS_PLANNING 1): turns the phone's calendar
 * instances into typed anchors. Stage A filters and scores each instance (markers and overrides first), stage B lets
 * `challenge.calendar` assign gym candidates to challenge slots, stage C removes duplicates per local date and stage D
 * sets the confidence. Pure: the same instances in any order give the same output.
 */
object CalendarClassifier : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("calendar.classify")

    private val LEXICONS = listOf(
        "gym_title", "neg", "run", "run_intervals", "run_long", "run_race", "run_tempo",
        "type.core", "type.full", "type.legs", "type.lower", "type.pull", "type.push", "type.upper",
    )

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.number("calendar.classify.confirm_score", "match weight", 0.6, 1.0),
        ParamSpec.integer("calendar.classify.dedupe_minutes", "min", 30.0, 240.0),
        ParamSpec.integer("calendar.classify.dur_max", "min", 90.0, 300.0),
        ParamSpec.integer("calendar.classify.dur_min", "min", 10.0, 60.0),
        ParamSpec.integer("calendar.classify.high_score", "score", 3.0, 10.0),
        ParamSpec.integer("calendar.classify.link_window_h", "h", 1.0, 6.0),
        ParamSpec.integer("calendar.classify.run_long_minutes", "min", 60.0, 120.0),
        ParamSpec.integer("calendar.classify.run_max_minutes", "min", 120.0, 480.0),
        ParamSpec.integer("calendar.classify.run_min_minutes", "min", 5.0, 30.0),
        ParamSpec.integer("calendar.classify.thresh", "score", 1.0, 6.0),
        ParamSpec.integer("calendar.classify.w_allday", "score", -10.0, 0.0),
        ParamSpec.integer("calendar.classify.w_challenge_title", "score", 0.0, 6.0),
        ParamSpec.integer("calendar.classify.w_desc", "score", 0.0, 3.0),
        ParamSpec.integer("calendar.classify.w_dur_bad", "score", -3.0, 0.0),
        ParamSpec.integer("calendar.classify.w_dur_ok", "score", 0.0, 3.0),
        ParamSpec.integer("calendar.classify.w_loc", "score", 0.0, 6.0),
        ParamSpec.integer("calendar.classify.w_neg", "score", -10.0, 0.0),
        ParamSpec.integer("calendar.classify.w_title", "score", 0.0, 6.0),
    ) + LEXICONS.map { ParamSpec("calendar.classify.lex.$it", ParamType.REGEX, "regex (Java syntax, applied to normalised text N(s))") }

    /** Feature codes of 1.4 (logged in `calendar_classified.features`). */
    const val F_TITLE = "title_kw"
    const val F_LOCATION = "location"
    const val F_CHALLENGE_TITLE = "challenge_title"
    const val F_DESC = "desc_exercises"
    const val F_ALL_DAY = "all_day"
    const val F_NEG = "neg_kw"

    /** The days after today in which a medium-confidence anchor asks "Is this ...?" (1.4 stage D). */
    private const val CONFIRM_DAYS = 7L

    private class Consts(p: ParamView) {
        val wTitle = p.int("calendar.classify.w_title")
        val wLoc = p.int("calendar.classify.w_loc")
        val wChallengeTitle = p.int("calendar.classify.w_challenge_title")
        val wDesc = p.int("calendar.classify.w_desc")
        val wAllDay = p.int("calendar.classify.w_allday")
        val wDurOk = p.int("calendar.classify.w_dur_ok")
        val wDurBad = p.int("calendar.classify.w_dur_bad")
        val wNeg = p.int("calendar.classify.w_neg")
        val thresh = p.int("calendar.classify.thresh")
        val durMin = p.int("calendar.classify.dur_min")
        val durMax = p.int("calendar.classify.dur_max")
        val highScore = p.int("calendar.classify.high_score")
        val confirmScore = p.double("calendar.classify.confirm_score")
        val dedupeMinutes = p.int("calendar.classify.dedupe_minutes")
        val runMin = p.int("calendar.classify.run_min_minutes")
        val runMax = p.int("calendar.classify.run_max_minutes")
        val runLong = p.int("calendar.classify.run_long_minutes")
        val lex: Map<String, Regex> = LEXICONS.associateWith { Regex(p.string("calendar.classify.lex.$it")) }
        fun lex(name: String): Regex = lex.getValue(name)
    }

    /** A gym candidate of stage A before stage B (kept internal; the matcher sees [GymCandidate]). */
    private class Pending(val inst: CalendarInstance, val anchor: Anchor, val candidate: GymCandidate?)

    fun classify(instances: List<CalendarInstance>, ctx: ClassifyContext, params: ParamView, matcher: SlotMatching): CalendarClassification {
        val c = Consts(params)
        val version = ref.version.toString()
        val ignoreNames = ctx.ignoreCalendarNamePatterns.map { Regex(it) }
        val locKeys = ctx.gymLocationKeywords.map { CalendarText.normalize(it) }.filter { it.isNotEmpty() }.distinct()
        val catalog = ctx.catalogNames.map { CalendarText.normalize(it) }.filter { it.isNotEmpty() }.distinct().sorted()
        val challengePatterns = ctx.enrollments.sortedBy { it.enrollmentId }.flatMap { e -> e.patterns.map { Regex(it.second) } }
        val activeIds = ctx.enrollments.associateBy { it.enrollmentId }

        val sorted = instances.filter { it.calendarId in ctx.allowlist }
            .sortedWith(compareBy<CalendarInstance>({ it.beginUtc }, { it.eventId }, { it.calendarId }, { it.instanceKey }))
        val out = ArrayList<Pending>()
        for (inst in sorted) {
            val base = baseAnchor(inst, ctx.zone, version)
            fun plain(a: Anchor) { out += Pending(inst, a, null) }
            if (ignoreNames.any { it.containsMatchIn(CalendarText.normalize(inst.calendarName)) || it.containsMatchIn(inst.calendarName) }) {
                plain(base.copy(kind = AnchorKind.IGNORED, ignoredReason = "todoist")); continue
            }
            if (inst.status == CalendarInstance.CANCELLED) { plain(base.copy(kind = AnchorKind.IGNORED, ignoredReason = "cancelled")); continue }
            if (inst.declined) { plain(base.copy(kind = AnchorKind.IGNORED, ignoredReason = "declined")); continue }
            if (inst.allDay && ctx.ignoreAllDay) { plain(base.copy(kind = AnchorKind.IGNORED, ignoredReason = "all_day")); continue }
            val t = CalendarText.normalize(inst.title)
            val d = CalendarText.normalize(inst.description)
            val l = CalendarText.normalize(inst.location)
            val dur = Duration.between(inst.beginUtc, inst.endUtc).toMinutes().toInt()
            val ov = ctx.overrides[inst.instanceKey] ?: ctx.overrides[inst.seriesKey]
            val titleKw = c.lex("gym_title").containsMatchIn(t)
            val locMatch = locKeys.any { l.contains(it) }
            if (ov != null && ov.kind != CalendarOverride.ADOPT) {
                when (ov.kind) {
                    AnchorKind.CHALLENGE_SESSION.code -> {
                        val a = base.copy(kind = AnchorKind.CHALLENGE_SESSION, enrollmentId = ov.enrollmentId, slot = ov.slot, method = MatchMethod.MANUAL)
                        out += Pending(inst, a, candidate(inst, ctx.zone, t, titleKw || locMatch, null, ov.slot, false))
                    }
                    AnchorKind.PLANNED_GYM.code ->
                        plain(base.copy(kind = AnchorKind.PLANNED_GYM, gymType = ov.gymType ?: AUTO, method = MatchMethod.MANUAL))
                    AnchorKind.RUN.code -> plain(run(base, c, t, d, dur).copy(method = MatchMethod.MANUAL))
                    else -> plain(base.copy(kind = AnchorKind.BUSY, method = MatchMethod.MANUAL))
                }
                continue
            }
            val m = Marker.ofDescription(inst.description)
            val key = m?.parsedKey()
            if (m != null && key is SlotKey.General) {
                plain(base.copy(kind = AnchorKind.PLANNED_GYM, gymType = typeFrom(t, c) ?: AUTO, marker = m, method = MatchMethod.MARKER))
                continue
            }
            if (m != null && key is SlotKey.Challenge && activeIds[m.uuid]?.slug == key.slug) {
                val a = base.copy(kind = AnchorKind.CHALLENGE_SESSION, enrollmentId = m.uuid, slot = key.slot, marker = m, method = MatchMethod.MARKER)
                out += Pending(inst, a, candidate(inst, ctx.zone, t, titleKw || locMatch, m, null, false))
                continue
            }
            var s = 0
            val f = ArrayList<String>()
            if (titleKw) { s += c.wTitle; f += F_TITLE }
            if (locMatch) { s += c.wLoc; f += F_LOCATION }
            if (challengePatterns.any { it.containsMatchIn(t) }) { s += c.wChallengeTitle; f += F_CHALLENGE_TITLE }
            if (catalog.count { d.contains(it) } >= 2) { s += c.wDesc; f += F_DESC }
            if (inst.allDay) { s += c.wAllDay; f += F_ALL_DAY }
            s += if (dur in c.durMin..c.durMax) c.wDurOk else c.wDurBad
            if (c.lex("neg").containsMatchIn(t)) { s += c.wNeg; f += F_NEG }
            if (c.lex("run").containsMatchIn(t) && !titleKw) {
                plain(run(base, c, t, d, dur).copy(score = s, features = f))
                continue
            }
            val scored = base.copy(score = s, features = f)
            if (s >= c.thresh) {
                val adopted = ov?.kind == CalendarOverride.ADOPT
                val a = scored.copy(kind = AnchorKind.PLANNED_GYM, gymType = typeFrom(t, c) ?: typeFrom(d, c) ?: AUTO, method = if (adopted) MatchMethod.ADOPTED else MatchMethod.SCORE)
                out += Pending(inst, a, candidate(inst, ctx.zone, t, titleKw || locMatch, null, null, adopted))
            } else if (inst.availabilityFree) {
                plain(scored.copy(kind = AnchorKind.IGNORED, ignoredReason = "availability_free"))
            } else {
                plain(scored.copy(kind = AnchorKind.BUSY))
            }
        }

        // Stage B: challenge slots, enrollment by enrollment (the matcher owns capacity and aliases).
        val assigned = TreeMap<String, Pair<String, SlotMatch>>()
        for (enr in ctx.enrollments.sortedBy { it.enrollmentId }) {
            val cands = out.mapNotNull { p ->
                val cand = p.candidate ?: return@mapNotNull null
                if (cand.instanceKey in assigned.keys) return@mapNotNull null
                if (cand.isoWeek !in enr.slotsPerWeek) return@mapNotNull null
                if (cand.marker != null && cand.marker.uuid != enr.enrollmentId) return@mapNotNull null
                if (cand.manualSlot != null && p.anchor.enrollmentId != null && p.anchor.enrollmentId != enr.enrollmentId) return@mapNotNull null
                cand
            }
            for (sm in matcher.matchSlots(enr, cands)) assigned[sm.instanceKey] = enr.enrollmentId to sm
        }
        val staged = out.map { p ->
            val hit = assigned[p.anchor.instanceKey]
            when {
                hit != null -> p.anchor.copy(
                    kind = AnchorKind.CHALLENGE_SESSION, enrollmentId = hit.first, slot = hit.second.slot, matchScore = hit.second.weight,
                    method = hit.second.method, attention = hit.second.attention,
                )
                p.anchor.kind == AnchorKind.CHALLENGE_SESSION -> p.anchor // a marked or manual event outside the required slots keeps its own slot
                p.candidate != null -> p.anchor.copy(kind = AnchorKind.PLANNED_GYM, unmatchedChallengeCandidate = F_CHALLENGE_TITLE in p.anchor.features)
                else -> p.anchor
            }
        }

        // Stage C: one gym anchor per cluster and date; overlapping runs keep the longest.
        val deduped = dedupe(staged, c)

        // Stage D: confidence.
        val today = Week.localDate(ctx.now, ctx.zone)
        val final = deduped.map { a ->
            val conf = when (a.kind) {
                AnchorKind.CHALLENGE_SESSION ->
                    if (a.method != MatchMethod.TITLE_PATTERN || (a.matchScore ?: 0.0) >= c.confirmScore) Anchor.HIGH else Anchor.MEDIUM
                AnchorKind.PLANNED_GYM ->
                    if (a.marker != null || a.method == MatchMethod.MANUAL || a.score >= c.highScore) Anchor.HIGH else Anchor.MEDIUM
                else -> Anchor.HIGH
            }
            a.copy(confidence = conf, needsConfirm = conf == Anchor.MEDIUM && !a.localDate.isBefore(today) && !a.localDate.isAfter(today.plusDays(CONFIRM_DAYS)))
        }
        return CalendarClassification(CalendarClassification.OK, final)
    }

    /** Degraded mode (1.4): missing permission or a provider error gives no anchors. */
    fun unavailable(permission: Boolean): CalendarClassification =
        CalendarClassification(if (permission) CalendarClassification.UNAVAILABLE else CalendarClassification.PERMISSION_DENIED, emptyList())

    const val AUTO = "AUTO"

    private fun baseAnchor(inst: CalendarInstance, zone: ZoneId, version: String): Anchor = Anchor(
        instanceKey = inst.instanceKey, seriesKey = inst.seriesKey, eventId = inst.eventId, calendarId = inst.calendarId,
        beginUtc = inst.beginUtc, endUtc = inst.endUtc, localDate = Week.localDate(inst.beginUtc, zone),
        startLocal = Week.localTime(inst.beginUtc, zone), endLocal = Week.localTime(inst.endUtc, zone),
        kind = AnchorKind.BUSY, classifierVersion = version,
    )

    private fun candidate(inst: CalendarInstance, zone: ZoneId, t: String, gymEvidence: Boolean, marker: Marker?, manualSlot: String?, adopted: Boolean): GymCandidate {
        val date = Week.localDate(inst.beginUtc, zone)
        return GymCandidate(inst.instanceKey, inst.eventId, inst.beginUtc, inst.endUtc, date, Week.isoWeek(date), t, gymEvidence, marker, manualSlot, adopted)
    }

    private fun run(base: Anchor, c: Consts, t: String, d: String, dur: Int): Anchor {
        val x = "$t $d"
        val intensity = when {
            c.lex("run_race").containsMatchIn(x) -> RunIntensity.RACE
            c.lex("run_intervals").containsMatchIn(x) -> RunIntensity.INTERVALS
            c.lex("run_tempo").containsMatchIn(x) -> RunIntensity.TEMPO
            else -> RunIntensity.EASY
        }
        return base.copy(
            kind = AnchorKind.RUN, runIntensity = intensity, runMinutes = dur.coerceIn(c.runMin, c.runMax),
            runLong = dur > c.runLong || c.lex("run_long").containsMatchIn(t),
        )
    }

    /**
     * `typeFrom` (1.4): the families named in the text, with upper / lower / full expanded; two or more families map
     * to the nearest session type of ALGORITHMS_PLANNING 0.3, one family to itself, none to null.
     */
    private fun typeFrom(x: String, c: Consts): String? {
        if (x.isEmpty()) return null
        val fams = sortedSetOf<String>()
        if (c.lex("type.push").containsMatchIn(x)) fams += PUSH
        if (c.lex("type.pull").containsMatchIn(x)) fams += PULL
        if (c.lex("type.legs").containsMatchIn(x)) fams += LEGS
        if (c.lex("type.core").containsMatchIn(x)) fams += CORE
        val upper = c.lex("type.upper").containsMatchIn(x)
        val lower = c.lex("type.lower").containsMatchIn(x)
        val full = c.lex("type.full").containsMatchIn(x)
        if (fams.isEmpty()) {
            return when {
                full -> FULL
                upper && lower -> FULL
                upper -> UPPER
                lower -> LEGS_CORE
                else -> null
            }
        }
        if (upper) { fams += PUSH; fams += PULL }
        if (lower) { fams += LEGS; fams += CORE }
        if (full) { fams += PUSH; fams += PULL; fams += LEGS }
        val push = PUSH in fams
        val pull = PULL in fams
        val legs = LEGS in fams
        return when {
            push && pull && legs -> FULL
            push && pull -> UPPER
            push && legs -> "$PUSH+$LEGS"
            pull && legs -> "$PULL+$LEGS"
            legs && CORE in fams -> LEGS_CORE
            push -> PUSH
            pull -> PULL
            legs -> LEGS
            else -> CORE
        }
    }

    private const val PUSH = "PUSH"
    private const val PULL = "PULL"
    private const val LEGS = "LEGS"
    private const val CORE = "CORE"
    private const val UPPER = "UPPER"
    private const val FULL = "FULL"
    private const val LEGS_CORE = "LEGS+CORE"

    private fun rank(a: Anchor): Int = (a.method ?: MatchMethod.SCORE).ordinal

    private fun dedupe(anchors: List<Anchor>, c: Consts): List<Anchor> {
        val replaced = TreeMap<String, String>()
        val dedupe = Duration.ofMinutes(c.dedupeMinutes.toLong())
        val byDate = anchors.groupBy { it.localDate }.toSortedMap()
        for ((_, list) in byDate) {
            val gym = list.filter { it.kind == AnchorKind.CHALLENGE_SESSION || it.kind == AnchorKind.PLANNED_GYM }
                .sortedWith(compareBy<Anchor>({ it.beginUtc }, { it.eventId }, { it.instanceKey }))
            for (cluster in clusters(gym, dedupe)) {
                if (cluster.size < 2) continue
                val keeper = cluster.sortedWith(
                    compareBy<Anchor>({ rank(it) }, { -it.score }, { it.beginUtc }, { it.eventId }, { it.instanceKey }),
                ).first()
                for (a in cluster) if (a !== keeper) replaced[a.instanceKey] = keeper.instanceKey
            }
            val runs = list.filter { it.kind == AnchorKind.RUN }
                .sortedWith(compareBy<Anchor>({ -Duration.between(it.beginUtc, it.endUtc).toMinutes() }, { it.beginUtc }, { it.eventId }, { it.instanceKey }))
            val kept = ArrayList<Anchor>()
            for (r in runs) {
                val over = kept.firstOrNull { k -> r.beginUtc.isBefore(k.endUtc) && k.beginUtc.isBefore(r.endUtc) }
                if (over != null) replaced[r.instanceKey] = over.instanceKey else kept += r
            }
        }
        return anchors.map { a ->
            val keeper = replaced[a.instanceKey] ?: return@map a
            a.copy(
                kind = AnchorKind.IGNORED, ignoredReason = "duplicate_of", duplicateOf = keeper,
                enrollmentId = null, slot = null, matchScore = null, attention = null, gymType = null, runIntensity = null, runMinutes = null,
                runLong = false, unmatchedChallengeCandidate = false,
            )
        }
    }

    /** Chains anchors whose intervals overlap or whose starts are at most [window] apart. */
    private fun clusters(sorted: List<Anchor>, window: Duration): List<List<Anchor>> {
        val out = ArrayList<MutableList<Anchor>>()
        for (a in sorted) {
            val cur = out.lastOrNull()
            val last = cur?.last()
            val joins = last != null && (cur.any { it.endUtc.isAfter(a.beginUtc) } || Duration.between(last.beginUtc, a.beginUtc) <= window)
            if (joins) cur!!.add(a) else out += mutableListOf(a)
        }
        return out
    }

    /**
     * Post-hoc session link (C35): the gym anchor on the session's local date whose start is within
     * `link_window_h` of the session start, preferring the same challenge slot, then the smaller time difference,
     * then the lower event id.
     */
    fun linkSession(sessionStart: Instant, sessionSlot: String?, anchors: List<Anchor>, zone: ZoneId, params: ParamView): Anchor? {
        val window = Duration.ofHours(params.int("calendar.classify.link_window_h").toLong())
        val date: LocalDate = Week.localDate(sessionStart, zone)
        return anchors.filter { (it.kind == AnchorKind.CHALLENGE_SESSION || it.kind == AnchorKind.PLANNED_GYM) && it.localDate == date }
            .filter { Duration.between(it.beginUtc, sessionStart).abs() <= window }
            .sortedWith(
                compareBy<Anchor>(
                    { if (sessionSlot != null && it.slot == sessionSlot) 0 else 1 },
                    { Duration.between(it.beginUtc, sessionStart).abs() },
                    { it.eventId },
                    { it.instanceKey },
                ),
            ).firstOrNull()
    }
}
