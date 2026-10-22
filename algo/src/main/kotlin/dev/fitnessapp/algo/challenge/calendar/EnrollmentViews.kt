package dev.fitnessapp.algo.challenge.calendar

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.SemanticValidator
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.materialize.Materializer
import dev.fitnessapp.algo.challenge.plan.WeekPlanner
import dev.fitnessapp.algo.core.calendar.EnrollmentView
import dev.fitnessapp.algo.core.calendar.OpeningHours
import dev.fitnessapp.algo.core.calendar.SlotPreference
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.params.ParamView
import java.time.LocalDate
import java.time.LocalTime

/** Builds the matcher's [EnrollmentView] from a definition and the enrollment's state (CHALLENGE_ENGINE 9.3). */
object EnrollmentViews {
    /** [weekStarts] are the Mondays of the read window; each week's required slots follow its mode (7.2, C36). */
    fun of(
        enrollmentId: String,
        def: CompiledDefinition,
        params: ParamView,
        state: EnrollmentState,
        facts: EngineFacts,
        weekStarts: List<LocalDate>,
        gymHours: List<OpeningHours>,
    ): EnrollmentView {
        val slotsPerWeek = weekStarts.sorted().associate { Week.isoWeek(it) to WeekPlanner.requiredSlots(def, params, state, facts, it) }
        val slots = (def.doc.schedule.slotsPerWeek.values.flatten() + def.doc.calendar.patterns.map { it.slot }).distinct().sorted()
        return EnrollmentView(
            enrollmentId = enrollmentId,
            slug = def.slug,
            slotsPerWeek = slotsPerWeek.toSortedMap(),
            patterns = def.doc.calendar.patterns.map { it.slot to it.title },
            slotAliases = def.doc.calendar.slotAliases,
            preferred = def.doc.schedule.preferred.associate {
                it.slot to SlotPreference(SemanticValidator.WEEKDAYS.indexOf(it.weekday) + 1, LocalTime.parse(it.start), it.durMin)
            }.toSortedMap(),
            bookedMin = slots.mapNotNull { s -> Materializer.templateFor(def, state, s)?.bookedMin?.let { s to it } }.toMap().toSortedMap(),
            gymHours = gymHours.sortedBy { it.isoDow },
        )
    }
}
