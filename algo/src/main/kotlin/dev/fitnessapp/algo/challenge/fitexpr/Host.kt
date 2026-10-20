package dev.fitnessapp.algo.challenge.fitexpr

import java.math.BigDecimal
import java.time.LocalDate

/**
 * The engine-exposed functions of section 3.8 (LOAD-1 rounding, RCM-1 `k`, equipment, validity, pain
 * facts). They read only the frozen engine-fact snapshot and the folded state (section 3.9), never another
 * module live. A function that the current evaluation context cannot answer raises `missing:fn:<name>`.
 */
interface EvalHost {
    fun today(): LocalDate = unavailable("today")
    fun assistKg(pin: BigDecimal): BigDecimal = unavailable("assistKg")
    fun oneArmLoad(pin: BigDecimal): BigDecimal = unavailable("oneArmLoad")
    fun lbKg(): BigDecimal = unavailable("lbToKg")
    fun roundLoad(m: BigDecimal, profile: String, mode: String): BigDecimal = unavailable("roundLoad")
    fun roundPin(x: BigDecimal, mode: String): BigDecimal = unavailable("roundPin")
    fun userK(exerciseKey: String): BigDecimal = unavailable("userK")
    fun userKQuantile(exerciseKey: String, q: BigDecimal): BigDecimal = unavailable("userKQuantile")
    fun has(equipmentId: String): Boolean = unavailable("has")
    fun todayHas(slot: String): Boolean = unavailable("todayHas")
    fun valid(itemKey: String): Boolean = unavailable("valid")
    fun reasonOn(itemKey: String): String? = unavailable("reasonOn")
    fun validInBlock(itemKey: String): Int = unavailable("validInBlock")
    fun sessionsDone(minRatio: BigDecimal): Int = unavailable("sessionsDone")
    fun daysSincePain(location: String): Long = unavailable("daysSincePain")
    fun nextFixedTestDate(from: LocalDate): LocalDate? = unavailable("nextFixedTestDate")
    fun phaseGatePasses(phase: Int): Boolean = unavailable("phaseGatePasses")

    companion object {
        fun unavailable(name: String): Nothing = throw EvalError.missing("fn:$name")

        /** A host that answers nothing (pure expressions only). */
        val NONE: EvalHost = object : EvalHost {}
    }
}
