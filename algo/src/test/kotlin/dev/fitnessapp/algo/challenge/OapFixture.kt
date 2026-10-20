package dev.fitnessapp.algo.challenge

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.enrollment.ChallengeEvent
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentFold
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.enrollment.ImportBlock
import dev.fitnessapp.algo.challenge.enrollment.ItemLog
import dev.fitnessapp.algo.challenge.enrollment.RowLog
import dev.fitnessapp.algo.challenge.enrollment.SessionLog
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.LoadProfile
import dev.fitnessapp.algo.challenge.fitexpr.MachineCalibration
import dev.fitnessapp.algo.challenge.fitexpr.UserKFact
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.Values
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.testing.TestParams
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.SortedMap
import java.util.TreeMap
import java.util.UUID

/** The CHALLENGE_ENGINE 14.1 shared fixture as enrollment events. */
object OapFixture {
    val zone: ZoneId = ZoneId.of("Europe/Amsterdam")
    val enrollmentId: UUID = UUID.fromString("0199c3a0-5d2e-7b41-8f3a-1c2d3e4f5a6b")
    val def: CompiledDefinition get() = ChallengeTestSupport.oap
    fun params(): ParamView = TestParams.defaults()

    val facts = EngineFacts(
        bwKg = BigDecimal("70.70"),
        userK = sortedMapOf("weighted_pullup" to UserKFact("chinup_weighted", BigDecimal("30.0000"), BigDecimal("0.3000"), null, 0)),
        machines = sortedMapOf(
            "assisted_pullup_machine" to MachineCalibration("lb", BigDecimal(5), BigDecimal.ZERO, null, BigDecimal("6.5"), BigDecimal.ONE, 1),
        ),
        loadProfiles = sortedMapOf("belt" to LoadProfile((0..24).map { BigDecimal("2.5").multiply(BigDecimal(it)) }, 1)),
        has = listOf("pullup_bar", "fixed_neutral_handle", "assisted_pullup_machine", "dip_belt", "cable_station", "dumbbells", "dip_station", "rower_erg"),
    )

    fun at(date: String, time: String = "12:00"): Instant = Week.atLocal(LocalDate.parse(date), LocalTime.parse(time), zone)

    fun id(name: String): UUID = Ids.fitness("test-event:$name")

    fun event(type: String, date: String, time: String, payload: EventPayload, name: String = "$type@$date $time", facts: EngineFacts? = this.facts): ChallengeEvent =
        ChallengeEvent(id(name), type, at(date, time), LocalDate.parse(date), payload, facts)

    fun mass(x: String): Value = Values.mass(x)
    fun pin(x: String): Value = Values.pin(x)
    fun dur(x: String): Value = Values.duration(x)

    /** The 14.1 import: phase 1, block 1 week 5, test pending since 2026-10-04, loads of the artifact. */
    fun import(
        mode: String = "test_pending",
        blockWeek: Int = 5,
        plannedTest: String? = "2026-10-11",
        needsCleanSession: Boolean = true,
        extraVars: Map<String, Value> = emptyMap(),
    ): ImportBlock {
        val vars = TreeMap<String, Value>(
            mapOf(
                "e1rmAdded" to mass("39.38"), "e1rmAddedM110" to mass("42.82"),
                "wpuC" to mass("20"), "wpuA" to mass("22.5"), "wpuB" to mass("27.5"), "negB" to mass("47.5"), "negReset" to mass("45"),
                "pinA" to pin("40"), "pinC" to pin("50"), "pinHold90" to pin("35"), "sthS" to dur("6"), "plankS" to dur("20"),
                "plankLoadKg" to mass("0"), "oaHangS" to dur("10"), "kgPerRep" to mass("2.1667"),
                "needsCleanSession" to dev.fitnessapp.algo.challenge.fitexpr.BoolV.of(needsCleanSession),
            ),
        )
        vars.putAll(extraVars)
        return ImportBlock(
            phase = 1, blockIndex = 1, blockWeek = blockWeek, mode = mode, weekOrdinal = 6,
            plannedTestDate = plannedTest?.let(LocalDate::parse), vars = vars,
        )
    }

    val onboard: SortedMap<String, Value> = TreeMap(
        mapOf(
            "bw" to mass("70.7"), "wpu3rmAdded" to mass("32.5"), "bwMaxReps" to Values.int(18), "anchorLoad" to mass("20"),
            "pinA" to pin("40"), "pinC" to pin("50"), "pinHold90" to pin("35"),
        ),
    )

    fun created(imp: ImportBlock = import(), date: String = "2026-10-07", time: String = "12:00", definition: CompiledDefinition = def): ChallengeEvent =
        event(
            EventTypes.CREATED, date, time,
            EventPayload.Created(enrollmentId, sortedMapOf(), onboard, imp, definition.version, definition.jcsSha256),
            name = "created",
        )

    /** One `rules.approval_changed` per proposal, right after creation (10.2). */
    fun decisions(approve: Boolean, date: String = "2026-10-07", definition: CompiledDefinition = def, except: Map<String, Boolean> = emptyMap()): List<ChallengeEvent> =
        definition.doc.proposals.mapIndexed { i, p ->
            val ok = except[p.id] ?: approve
            ChallengeEvent(
                id("decision:${p.id}"), EventTypes.APPROVAL, at(date, "12:00").plusMillis(1L + i), LocalDate.parse(date),
                EventPayload.ApprovalChanged(p.id, if (ok) "approved" else "declined"), facts,
            )
        }

    fun start(imp: ImportBlock = import(), approve: Boolean = true, except: Map<String, Boolean> = emptyMap(), definition: CompiledDefinition = def): EnrollmentState {
        val events = listOf(created(imp, definition = definition)) + decisions(approve, definition = definition, except = except)
        return EnrollmentFold.fold(definition, params(), events).state
    }

    fun row(
        side: String,
        index: Int,
        reps: Int? = null,
        rir: String? = null,
        added: String? = null,
        pin: String? = null,
        hold: String? = null,
        reserve: String? = null,
        ecc: String? = null,
        flags: List<String> = emptyList(),
        reason: String? = null,
        kind: String = "working",
    ) = RowLog(
        side = side, setKind = kind, setIndex = index, reps = reps, rirReported = rir?.let(::BigDecimal), addedKg = added?.let(::BigDecimal),
        assistPinNative = pin?.let(::BigDecimal), durationS = hold?.let(::BigDecimal), holdReserveS = reserve?.let(::BigDecimal),
        eccSActual = ecc?.let(::BigDecimal), flags = flags, deviationReasonCode = reason,
    )

    fun item(rows: List<RowLog>, plannedRows: Int = rows.size, targetReps: Int? = null, status: String = "done", progressionExcluded: Boolean = false, reason: String? = null) =
        ItemLog(status, plannedRows, targetReps, null, rows, statusReasonCode = reason, progressionExcluded = progressionExcluded)

    fun session(
        slot: String,
        date: String,
        items: Map<String, ItemLog>,
        templateId: String = "${slot}1",
        ratio: String = "1",
        pain: Boolean = false,
        time: String = "21:15",
        name: String = "session:$slot@$date",
    ): ChallengeEvent = event(
        EventTypes.SESSION_COMPLETED, date, time,
        EventPayload.Session(
            SessionLog(id("sid:$name"), slot, templateId, if (slot == "TEST") "test" else "gym", LocalDate.parse(date), BigDecimal(ratio), pain, null, TreeMap(items)),
        ),
        name = name,
    )

    /** B: weighted pull-ups 6 x 2 at [added] with the given RIRs and a grip slip on [slipSet] (1-based). */
    fun bWpu(added: String, rirs: List<Int>, slipSet: Int? = null, reps: Int = 2): ItemLog = item(
        rirs.mapIndexed { i, r ->
            row("both", i + 1, reps = reps, rir = r.toString(), added = added, flags = if (slipSet == i + 1) listOf("grip_slip_before_last") else emptyList())
        },
        plannedRows = 6, targetReps = 2,
    )

    /** Per-side assisted rows (`oaa`): one pair per entry of [pairs] as L/R reps. */
    fun oaa(pin: String, pairs: List<Pair<Int, Int>>, targetReps: Int, rir: String = "2", plannedPairs: Int = pairs.size): ItemLog = item(
        pairs.flatMapIndexed { i, (l, r) ->
            listOf(row("L", i + 1, reps = l, rir = rir, pin = pin), row("R", i + 1, reps = r, rir = rir, pin = pin))
        },
        plannedRows = plannedPairs * 2, targetReps = targetReps,
    )
}
