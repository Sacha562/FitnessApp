package dev.fitnessapp.algo.core.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * One `CalendarContract.Instances` row as plain data (ANDROID 16.4). The app maps provider codes to the strings
 * below; all filtering happens in [CalendarClassifier], so the classification is replayable.
 */
data class CalendarInstance(
    val instanceKey: String,
    val seriesKey: String,
    val eventId: Long,
    val calendarId: Long,
    val calendarName: String,
    /** True when the calendar is the write target (the primary calendar, or the test calendar in sandbox mode). */
    val calendarWritable: Boolean,
    val beginUtc: Instant,
    val endUtc: Instant,
    val allDay: Boolean,
    val title: String?,
    val description: String?,
    val location: String?,
    /** `confirmed`, `tentative` or `cancelled`. */
    val status: String,
    /** True when the user declined the event as an attendee. */
    val declined: Boolean,
    /** True for `AVAILABILITY_FREE`. */
    val availabilityFree: Boolean,
) {
    companion object {
        const val CANCELLED = "cancelled"
    }
}

/** The five kinds of C20. */
enum class AnchorKind(val code: String) {
    CHALLENGE_SESSION("challenge_session"), PLANNED_GYM("planned_gym"), RUN("run"), BUSY("busy"), IGNORED("ignored");
}

/** E23 match methods, in the dedupe rank order of ALGORITHMS_PLANNING 1.4 stage C. */
enum class MatchMethod(val code: String) {
    MANUAL("manual"), MARKER("marker"), ADOPTED("adopted"), TITLE_PATTERN("title_pattern"), SCORE("score");
}

/**
 * A user correction (C20, DATA_MODEL `calendar_events.override_kind/override_slot`), keyed by instance or series key.
 * [kind] `challenge_session` carries [slot] and [enrollmentId]; `planned_gym` carries [gymType]; `run` is a run;
 * `ignored` means "not a gym session"; `adopt` marks an unmarked event the user adopted (it is still matched).
 */
data class CalendarOverride(
    val kind: String,
    val slot: String? = null,
    val enrollmentId: String? = null,
    val gymType: String? = null,
) {
    companion object {
        const val ADOPT = "adopt"
    }
}

/** Opening hours of the gym for one ISO weekday (1 = Monday), in minutes after local midnight. */
data class OpeningHours(val isoDow: Int, val openMin: Int, val closeMin: Int)

/** A preferred day and time of a slot (definition `schedule.preferred`). */
data class SlotPreference(val isoDow: Int, val start: LocalTime, val durMin: Int)

/**
 * What `challenge.calendar.matchSlots` needs of one active enrollment (CHALLENGE_ENGINE 9.3). Patterns are written
 * against normalised text and come from the definition (data, C20).
 */
data class EnrollmentView(
    val enrollmentId: String,
    val slug: String,
    /** The required slots per ISO week of the read window (`slotsPerWeek` of each week's mode, a postponed test shown as its fallback). */
    val slotsPerWeek: Map<String, List<String>>,
    /** Slot -> title regex, in definition order. */
    val patterns: List<Pair<String, String>>,
    val slotAliases: List<List<String>>,
    val preferred: Map<String, SlotPreference>,
    /** Slot -> the template's booked minutes. */
    val bookedMin: Map<String, Int>,
    val gymHours: List<OpeningHours>,
)

/** A classified event that may be a gym session: stage A's output and the matcher's input. */
data class GymCandidate(
    val instanceKey: String,
    val eventId: Long,
    val beginUtc: Instant,
    val endUtc: Instant,
    val localDate: LocalDate,
    val isoWeek: String,
    val normalizedTitle: String,
    /** The title has a gym keyword or the location matches the gym (the `+0.2` gym evidence of 9.3). */
    val gymEvidence: Boolean,
    val marker: Marker?,
    /** A manual override to a challenge slot of this enrollment. */
    val manualSlot: String?,
    val adopted: Boolean,
)

/** One accepted edge of the matching (CHALLENGE_ENGINE 9.3). */
data class SlotMatch(
    val instanceKey: String,
    val isoWeek: String,
    val slot: String,
    val weight: Double,
    val method: MatchMethod,
    /** `slot_mismatch` for alias edges (C36), else null. */
    val attention: String?,
)

/** Run intensity of F6 (E22 without `long`, which is a flag here). */
enum class RunIntensity(val code: String) { EASY("easy"), TEMPO("tempo"), INTERVALS("intervals"), RACE("race") }

/** One classified instance (ALGORITHMS_PLANNING 1.3). Kind-specific fields are null for other kinds. */
data class Anchor(
    val instanceKey: String,
    val seriesKey: String,
    val eventId: Long,
    val calendarId: Long,
    val beginUtc: Instant,
    val endUtc: Instant,
    val localDate: LocalDate,
    val startLocal: LocalTime,
    val endLocal: LocalTime,
    val kind: AnchorKind,
    val enrollmentId: String? = null,
    val slot: String? = null,
    val matchScore: Double? = null,
    val method: MatchMethod? = null,
    /** `slot_mismatch` (C36) or null. */
    val attention: String? = null,
    val gymType: String? = null,
    val marker: Marker? = null,
    val unmatchedChallengeCandidate: Boolean = false,
    val runIntensity: RunIntensity? = null,
    val runMinutes: Int? = null,
    val runLong: Boolean = false,
    /** `todoist`, `cancelled`, `declined`, `all_day`, `availability_free`, `duplicate_of`. */
    val ignoredReason: String? = null,
    val duplicateOf: String? = null,
    val score: Int = 0,
    val features: List<String> = emptyList(),
    /** `high` or `medium`. */
    val confidence: String = HIGH,
    val needsConfirm: Boolean = false,
    val classifierVersion: String,
) {
    companion object {
        const val HIGH = "high"
        const val MEDIUM = "medium"
    }
}

data class CalendarClassification(
    /** `ok`, `calendar_unavailable` or `permission_denied`. */
    val status: String,
    val anchors: List<Anchor>,
) {
    companion object {
        const val OK = "ok"
        const val UNAVAILABLE = "calendar_unavailable"
        const val PERMISSION_DENIED = "permission_denied"
    }
}
