package dev.fitnessapp.algo.challenge.fitexpr

import dev.fitnessapp.algo.testing.TestParams
import java.math.BigDecimal
import java.time.LocalDate

/** Shared scope, values and host for the FitExpr tests (the CHALLENGE_ENGINE 14.1 fixture where it applies). */
object FitExprTestSupport {
    val limits: FitExprLimits = FitExprLimits.from(TestParams.defaults())

    private fun exItem(): Type.RecordT {
        val fields = linkedMapOf<String, Type>(
            "reps" to Type.ListT(Type.INT), "rir" to Type.ListT(Type.NUM), "added" to Type.ListT(Type.MASS),
            "pin" to Type.ListT(Type.PIN), "hold_s" to Type.ListT(Type.DURATION), "ecc_s" to Type.ListT(Type.DURATION),
            "jerky" to Type.ListT(Type.BoolT), "grip_slip_before_last" to Type.ListT(Type.BoolT),
            "plannedRows" to Type.INT, "targetReps" to Type.INT,
        )
        return Type.RecordT("ex.item", fields)
    }

    val scope: TypeScope = TypeScope.of(
        mapOf(
            "p" to Type.RecordT(
                "p",
                mapOf(
                    "grip" to Type.EnumT(listOf("supinated", "neutral", "pronated")),
                    "thresholdRatioByGrip" to Type.MapT(Type.NUM),
                    "gate1GapKg" to Type.MASS,
                    "gate1HangS" to Type.DURATION,
                    "injuryDays" to Type.DAYS,
                    "injuryExtendDays" to Type.DAYS,
                    "pinDropLb" to Type.PIN,
                ),
            ),
            "s" to Type.RecordT(
                "s",
                mapOf(
                    "pinA" to Type.PIN, "wpuC" to Type.MASS, "injuryStart" to Type.DateT,
                    "bVariant" to Type.EnumT(listOf("6x2", "ladder123")),
                ),
            ),
            "t" to Type.RecordT(
                "t",
                mapOf(
                    "bw" to Type.MASS, "oa_hang_s" to Type.SideT(Type.DURATION), "wpu_3rm_added" to Type.MASS,
                    "oa_top_hold_s" to Type.SideT(Type.DURATION),
                ),
            ),
            "ex" to Type.RecordT("ex", mapOf("wpu" to exItem())),
            "mode" to Type.EnumT(listOf("normal", "deload", "test_pending", "extra_deload", "injury")),
            "today" to Type.DateT,
            "bw" to Type.MASS,
            "onboard" to Type.AnyT,
        ),
    )

    fun roots(today: LocalDate = LocalDate.parse("2026-10-21")): Map<String, Value> = mapOf(
        "p" to RecordV(
            "p",
            mapOf(
                "grip" to StrV("supinated"),
                "thresholdRatioByGrip" to MapV(sortedMapOf("supinated" to Values.num("0.65"), "neutral" to Values.num("0.70"), "pronated" to Values.num("0.75"))),
                "gate1GapKg" to Values.mass("2.0"),
                "gate1HangS" to Values.duration("20"),
                "injuryDays" to Values.days(14),
                "injuryExtendDays" to Values.days(7),
                "pinDropLb" to Values.pin("5"),
            ),
        ),
        "s" to RecordV("s", mapOf("pinA" to Values.pin("40"), "wpuC" to Values.mass("20"), "injuryStart" to NullV, "bVariant" to StrV("6x2"))),
        "t" to RecordV(
            "t",
            mapOf(
                "bw" to Values.mass("70.7"),
                "oa_hang_s" to SideV(Values.duration("15"), Values.duration("20")),
                "wpu_3rm_added" to Values.mass("32.5"),
            ),
        ),
        "ex" to RecordV(
            "ex",
            mapOf(
                "wpu" to RecordV(
                    "ex.wpu",
                    mapOf(
                        "reps" to ListV(List(6) { Values.int(2) }),
                        "rir" to ListV(listOf(2, 2, 2, 3, 2, 2).map { Values.int(it) }),
                        "added" to ListV(List(6) { Values.mass("27.5") }),
                        "pin" to ListV(emptyList()),
                        "hold_s" to ListV(emptyList()),
                        "ecc_s" to ListV(emptyList()),
                        "jerky" to ListV(List(6) { BoolV.FALSE }),
                        "grip_slip_before_last" to ListV(listOf(false, false, false, true, false, false).map { BoolV.of(it) }),
                        "plannedRows" to Values.int(6),
                        "targetReps" to Values.int(2),
                    ),
                ),
            ),
        ),
        "mode" to StrV("normal"),
        "today" to DateV(today),
        "bw" to Values.mass("70.7"),
        "onboard" to RecordV("onboard", mapOf("bw" to Values.mass("70.7"), "anchorLoad" to Values.mass("20"))),
    )

    /** The 14.1 fixture facts: machine {lb, 5, 0, null, 6.5, 1.0}, belt 0..60 kg in 2.5 kg steps, k 30 / sd 0.30. */
    val facts: EngineFacts = EngineFacts(
        bwKg = BigDecimal("70.70"),
        userK = sortedMapOf("weighted_pullup" to UserKFact("chinup_weighted", BigDecimal("30.0000"), BigDecimal("0.3000"), null, 0)),
        machines = sortedMapOf(
            "assisted_pullup_machine" to MachineCalibration("lb", BigDecimal(5), BigDecimal.ZERO, null, BigDecimal("6.5"), BigDecimal.ONE, 1),
        ),
        loadProfiles = sortedMapOf("belt" to LoadProfile((0..24).map { BigDecimal("2.5").multiply(BigDecimal(it)) }, 1)),
    )

    fun host(today: LocalDate = LocalDate.parse("2026-10-21")): EvalHost {
        val math = FactsMath(TestParams.defaults(), facts, "assisted_pullup_machine")
        return object : EvalHost {
            override fun today(): LocalDate = today
            override fun assistKg(pin: BigDecimal) = math.assistKg(pin)
            override fun oneArmLoad(pin: BigDecimal) = math.oneArmLoad(pin)
            override fun lbKg() = math.lbKg()
            override fun roundLoad(m: BigDecimal, profile: String, mode: String) = math.roundLoad(m, profile, mode)
            override fun roundPin(x: BigDecimal, mode: String) = math.roundPin(x, mode)
            override fun userK(exerciseKey: String) = math.userK(exerciseKey)
            override fun userKQuantile(exerciseKey: String, q: BigDecimal) = math.userKQuantile(exerciseKey, q)
        }
    }

    fun compile(src: String): CompiledExpr = FitExpr.compileOrThrow(src, scope, limits)

    fun eval(src: String): Value = compile(src).evaluate(roots(), host())

    fun compileError(src: String): CompileError = when (val r = FitExpr.compile(src, scope, limits)) {
        is CompileResult.Err -> r.error
        is CompileResult.Ok -> throw AssertionError("'$src' compiled to ${r.expr.type}")
    }

    fun evalError(src: String): EvalError = try {
        val v = eval(src)
        throw AssertionError("'$src' evaluated to $v")
    } catch (e: EvalError) {
        e
    }
}
