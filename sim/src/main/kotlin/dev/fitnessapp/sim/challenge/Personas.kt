package dev.fitnessapp.sim.challenge

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Something a persona does on a day besides its sessions. */
sealed class DayEvent {
    data class Pain(val region: String, val symptom: String, val score: Int) : DayEvent()
    data class Pause(val reason: String) : DayEvent()
    data object Resume : DayEvent()
}

/** Test-day results of the simulated user (CHALLENGE_ENGINE 2.6: tests improve 1-2 % per block). */
data class TestResult(
    val rm3Added: BigDecimal,
    val maxReps: Int,
    val hangL: BigDecimal,
    val hangR: BigDecimal,
    val topHold: BigDecimal,
    val leastAssistPin: BigDecimal,
    val neg5: Boolean,
    val oneFinger: Boolean = false,
    val rep: Boolean = false,
)

/**
 * A scripted simulated user (CHALLENGE_ENGINE 2.6). Every decision is a pure function of the week index,
 * the date and counters, so two runs are byte-identical; there is no randomness anywhere.
 */
abstract class Persona(val id: String) {
    /** Starts like the setup's "Start with a test week" (D35): no numbers, a test-pending first week (D37). */
    open val testWeekStart: Boolean = false

    open fun appOpen(date: LocalDate): Boolean = true

    /** Whether the user does the planned session (the n-th of this week, 0-based). */
    open fun trains(weekIndex: Int, slot: String, nthThisWeek: Int): Boolean = true

    open fun dayEvents(weekIndex: Int, dayIndex: Int, date: LocalDate, enrollmentDay: Int): List<DayEvent> = emptyList()

    /** The readiness elbow score (F15, 0-3) before a session. */
    open fun elbowScore(date: LocalDate, enrollmentDay: Int): Int = 0

    open fun machineBusy(aSessionNo: Int): Boolean = false

    open fun gripSlip(wpuSessionNo: Int, setIndex: Int): Boolean = false

    open fun homePlank(date: LocalDate): Boolean = date.dayOfWeek.value == 2 || date.dayOfWeek.value == 4

    /** Reported RIR for a working row of [itemKey] in [slot]. */
    open fun rir(itemKey: String, slot: String, role: String): Int = when {
        role == "prehab" -> 3
        itemKey == "oaa" && slot == "C" -> 3
        itemKey == "oaa" && slot == "A" -> 1
        else -> 2
    }

    open fun eccSeconds(itemKey: String): BigDecimal = if (itemKey == "neg") BigDecimal(8) else BigDecimal(5)

    /** Results of the n-th in-app test (1-based): 3RM total +2 % per test, holds and assistance improve. */
    open fun test(n: Int): TestResult = improving(n)

    protected fun improving(k: Int): TestResult {
        val bw = BigDecimal("70.7")
        val total = bw.add(BigDecimal("32.5")).multiply(BigDecimal("1.02").pow(k)).subtract(bw)
        val rm3 = total.divide(BigDecimal("2.5"), 0, RoundingMode.FLOOR).multiply(BigDecimal("2.5"))
        return TestResult(
            rm3Added = rm3,
            maxReps = 18 + k,
            hangL = BigDecimal(15 + 3 * k),
            hangR = BigDecimal(17 + 3 * k),
            topHold = BigDecimal(6).add(BigDecimal("1.5").multiply(BigDecimal(k))),
            leastAssistPin = BigDecimal(maxOf(0, 35 - 5 * k)),
            neg5 = k >= 2,
        )
    }

    companion object {
        fun all(): List<Persona> = listOf(AllSuccess, Stall, GripSlips, ElbowPainWeek7, ElbowPainWeek7Long, MissedWeeks, MachineBusy, DstAndLateTicks, TestWeekStart)

        fun byId(id: String): Persona = all().firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown persona $id")
    }
}

object AllSuccess : Persona("all_success")

/** All sessions, but started with a test week and no numbers: the first test sets every load (D37). */
object TestWeekStart : Persona("test_week_start") {
    override val testWeekStart: Boolean = true
}

/** Sessions complete but the test metrics stay flat for four tests (2.6 `stall`). */
object Stall : Persona("stall") {
    override fun test(n: Int): TestResult = if (n <= 4) improving(0) else improving(n - 4)
}

/** The hand slips before the last rep on weighted sets in 30 % of sessions (2.6 `grip_slips`). */
object GripSlips : Persona("grip_slips") {
    override fun gripSlip(wpuSessionNo: Int, setIndex: Int): Boolean = wpuSessionNo % 10 in setOf(1, 4, 7) && setIndex == 2
}

/** Medial elbow ache reported from week 7 on; pain-free from day 10 (2.6 `elbow_pain_wk7`). */
open class ElbowPain(id: String, private val lastPainDay: Int) : Persona(id) {
    private val firstPainDay = 6 * 7 + 2

    override fun dayEvents(weekIndex: Int, dayIndex: Int, date: LocalDate, enrollmentDay: Int): List<DayEvent> {
        val d = enrollmentDay - firstPainDay
        return if (d in 0..lastPainDay) listOf(DayEvent.Pain("elbow_medial", "medial_elbow_ache_after", 3)) else emptyList()
    }

    override fun elbowScore(date: LocalDate, enrollmentDay: Int): Int {
        val d = enrollmentDay - firstPainDay
        return if (d in 0..lastPainDay) 2 else 0
    }
}

object ElbowPainWeek7 : ElbowPain("elbow_pain_wk7", 9)

/** The second elbow scenario: pain until day 16, so P14 extends the protocol by 7 days. */
object ElbowPainWeek7Long : ElbowPain("elbow_pain_wk7_long", 16)

/** Weeks 3, 9 and 10 with 0-1 sessions; a 9-day and a 30-day pause (2.6 `missed_weeks`). */
object MissedWeeks : Persona("missed_weeks") {
    override fun trains(weekIndex: Int, slot: String, nthThisWeek: Int): Boolean = when (weekIndex) {
        2, 9 -> false
        8 -> nthThisWeek == 0
        else -> true
    }

    override fun dayEvents(weekIndex: Int, dayIndex: Int, date: LocalDate, enrollmentDay: Int): List<DayEvent> = when {
        weekIndex == 13 && dayIndex == 2 -> listOf(DayEvent.Pause("other"))
        weekIndex == 14 && dayIndex == 4 -> listOf(DayEvent.Resume)
        weekIndex == 18 && dayIndex == 0 -> listOf(DayEvent.Pause("other"))
        weekIndex == 22 && dayIndex == 2 -> listOf(DayEvent.Resume)
        else -> emptyList()
    }
}

/** The assisted machine is occupied in 25 % of A sessions (2.6 `machine_busy`). */
object MachineBusy : Persona("machine_busy") {
    override fun machineBusy(aSessionNo: Int): Boolean = aSessionNo % 4 == 1
}

/** The app is not opened for 12 days spanning the DST change of 2026-10-25 (2.6 `dst_and_late_ticks`). */
object DstAndLateTicks : Persona("dst_and_late_ticks") {
    private val away = LocalDate.parse("2026-10-19")..LocalDate.parse("2026-10-30")

    override fun appOpen(date: LocalDate): Boolean = date !in away
}
