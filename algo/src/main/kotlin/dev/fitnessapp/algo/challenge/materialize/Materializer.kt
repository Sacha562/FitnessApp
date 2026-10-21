package dev.fitnessapp.algo.challenge.materialize

import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.CompiledItem
import dev.fitnessapp.algo.challenge.cds.CompiledSteps
import dev.fitnessapp.algo.challenge.cds.ExerciseChoice
import dev.fitnessapp.algo.challenge.cds.FieldValue
import dev.fitnessapp.algo.challenge.cds.HabitScale
import dev.fitnessapp.algo.challenge.cds.I18n
import dev.fitnessapp.algo.challenge.cds.ModeInfo
import dev.fitnessapp.algo.challenge.cds.ModeSpec
import dev.fitnessapp.algo.challenge.cds.Modes
import dev.fitnessapp.algo.challenge.cds.ResolvedTemplate
import dev.fitnessapp.algo.challenge.cds.TemplateResolver
import dev.fitnessapp.algo.challenge.eca.Records
import dev.fitnessapp.algo.challenge.eca.RuleEnv
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.CompiledExpr
import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.fitexpr.Dim
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.EvalError
import dev.fitnessapp.algo.challenge.fitexpr.ListV
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.RecordV
import dev.fitnessapp.algo.challenge.fitexpr.StrV
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.Values
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.load.LoadScheme
import dev.fitnessapp.algo.core.load.PlateBreakdown
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/** Inputs of `challenge.materialize` (6.1) besides the definition and the engine params. */
data class MaterializeInput(
    val slot: String,
    val date: LocalDate,
    val state: EnrollmentState,
    val facts: EngineFacts,
    val catalog: CatalogDoc?,
    /** PREF-1 tiers by catalog slug (or definition key for local entries): never, dislike, neutral, like, favorite. */
    val preferences: Map<String, String> = emptyMap(),
    /** Reason codes logged on items in this session (live substitutions, 6.3 step 6). */
    val liveReasons: Map<String, String> = emptyMap(),
    /** The matched calendar event's minutes; else the template's `bookedMin` (6.9). */
    val bookedMin: Int? = null,
    /** TIME-1 estimate (M4); add-on capacity is computed only when it is known (D32). */
    val estMin: BigDecimal? = null,
    /** Profile `changeover_min` (C49, 5). */
    val changeoverMin: Int,
    /** The belt's plate inventory, for the plate breakdown (B2.8). */
    val beltScheme: LoadScheme.Belt? = null,
    /** RX-1 `w_last` per item key: the heaviest working system load of the last valid exposure (B9.14). */
    val lastExposureWsys: Map<String, BigDecimal> = emptyMap(),
)

/**
 * `challenge.materialize@1.0.0` (CHALLENGE_ENGINE 6): template x phase x mode x state -> [SessionPlan].
 * Items run through the 13 steps of 6.3; loads go through LOAD-1 only (6.6); RULE-mode loads are never
 * clamped by the generic caps, a larger jump only raises WE-S2 at WARN (D13, C45).
 */
object Materializer {
    val ref: ModuleRef = ModuleRegistry.ref("challenge.materialize")

    /** The template of [slot] in the current phase (6.2 step 1), honouring `templatesIfNotApproved`. */
    fun templateFor(def: CompiledDefinition, state: EnrollmentState, slot: String): ResolvedTemplate? {
        val phase = def.doc.phases.firstOrNull { it.id == state.phase } ?: return null
        val fallback = phase.templatesIfNotApproved
        val map = if (fallback != null && !state.approved(fallback.proposal)) fallback.templates else phase.templates
        return map[slot]?.let { def.templates[it] }
    }

    /** The mode's transform fields after inheritance (2.4.6): `test_pending` inherits deload only with its proposal. */
    class Transform(
        val sets: CompiledExpr?,
        val exemptRoles: List<String>,
        val loadFactor: Double?,
        val holdSecondsFactor: Double?,
        val excludeItemTags: List<String>,
        val prehabSetsFactor: Int?,
        val lightBilateral: Boolean,
        val lightSets: CompiledExpr?,
        val lightRirMin: Int?,
        val lightOverrides: Map<String, Map<String, FieldValue>>,
        val keepItLightNote: Boolean,
        /** Habit id -> sets and time factors (OAP deload: daily hang x0.5). */
        val habitScale: Map<String, HabitScale> = emptyMap(),
        /** Habits muted in this mode (OAP injury: daily hang). */
        val disableHabits: List<String> = emptyList(),
    )

    fun transform(def: CompiledDefinition, state: EnrollmentState, mode: String): Transform {
        val chain = ArrayList<ModeSpec>()
        val infos = ArrayList<ModeInfo>()
        var m: String? = mode
        val seen = sortedSetOf<String>()
        while (m != null && seen.add(m)) {
            val info = def.modes[m] ?: break
            chain += info.spec
            infos += info
            m = if (info.spec.proposal != null && !state.approved(info.spec.proposal)) info.spec.inheritsIfDeclined else info.spec.inherits
        }
        fun <T> first(f: (ModeSpec) -> T?): T? = chain.firstNotNullOfOrNull(f)
        val sets = infos.firstNotNullOfOrNull { it.setsExpr }
        val lbInfo = infos.firstOrNull { it.spec.lightBilateral != null }
        val lb = lbInfo?.spec?.lightBilateral
        val lbOn = lb != null && state.approved(lb.proposal)
        return Transform(
            sets = sets,
            exemptRoles = first { it.exemptRoles.takeIf { r -> r.isNotEmpty() } } ?: emptyList(),
            loadFactor = first { it.loadFactor },
            holdSecondsFactor = first { it.holdSecondsFactor },
            excludeItemTags = first { it.excludeItemTags.takeIf { t -> t.isNotEmpty() } } ?: emptyList(),
            prehabSetsFactor = first { it.prehabSetsFactor },
            lightBilateral = lbOn,
            lightSets = if (lbOn) lbInfo.lightBilateralSets else null,
            lightRirMin = if (lbOn) lb.rirMin else null,
            lightOverrides = if (lbOn) lbInfo.lightBilateralOverrides else emptyMap(),
            keepItLightNote = lb != null && !lbOn,
            habitScale = first { it.habitScale.takeIf { h -> h.isNotEmpty() } } ?: emptyMap(),
            disableHabits = first { it.disableHabits.takeIf { h -> h.isNotEmpty() } } ?: emptyList(),
        )
    }

    fun materialize(def: CompiledDefinition, params: ParamView, input: MaterializeInput): SessionPlan {
        val s = input.state
        val tpl = templateFor(def, s, input.slot) ?: throw IllegalArgumentException("no template for slot ${input.slot} in phase ${s.phase}")
        val env = RuleEnv(def, params, Records(params), s, input.date, input.facts)
        val resolver = ExerciseResolver(def, input.catalog, env.effectiveParams)
        val tf = transform(def, s, s.mode)
        val built = tpl.items.map { item(def, params, env, resolver, tf, tpl, it, input) }
        val ordered = order(built)
        val booked = input.bookedMin ?: tpl.bookedMin
        val addOn = def.doc.integration.addOnSlots.firstOrNull { it.slot == input.slot && it.position == "end" }
        val capacity = if (addOn != null && input.estMin != null) {
            maxOf(0, BigDecimal(booked).subtract(input.estMin).subtract(BigDecimal(addOn.bufferMin ?: input.changeoverMin)).setScale(0, RoundingMode.FLOOR).toInt())
        } else {
            null
        }
        val offered = capacity != null && capacity >= params.int("time.min_addon_min")
        return SessionPlan(
            slot = input.slot, templateId = tpl.id, phase = s.phase, mode = s.mode, date = input.date, kind = tpl.kind,
            bookedMin = booked, budgetMin = booked - input.changeoverMin, items = ordered, estMin = input.estMin,
            addOnCapacityMin = capacity, addOnOffered = offered, warnings = built.flatMap { it.first.warnings },
            snapshot = PlanSnapshot(def.jcsSha256, EngineVersion.ENGINE_VERSION, params.hash, input.catalog?.catalogVersion, s.stateRev),
        )
    }

    private val PREF_ORDER = listOf("favorite", "like", "neutral", "dislike", "never")

    /** Decimal places between a ratio and a percentage. */
    private const val PERCENT = 2

    private class Ctx(val warnings: MutableList<PlanWarning> = ArrayList(), val notes: MutableList<String> = ArrayList())

    private fun eval(env: RuleEnv, expr: CompiledExpr, extra: Map<String, Value> = emptyMap()): Value =
        expr.evaluate(env.roots() + extra, env.host)

    /** A field's value; an evaluation error keeps the state value of a plain `s.<var>` and warns (3.5). */
    private fun value(env: RuleEnv, fv: FieldValue?, ctx: Ctx, key: String, field: String): Value? = when (fv) {
        null -> null
        is FieldValue.Lit -> fv.value
        is FieldValue.Range -> fv.lo
        is FieldValue.Keyword -> null
        is FieldValue.Expr -> try {
            eval(env, fv.expr)
        } catch (e: EvalError) {
            ctx.warnings += PlanWarning("could_not_compute", "WARN", "$key.$field: ${e.code}")
            fv.expr.source.takeIf { it.startsWith("s.") }?.let { env.state.vars[it.removePrefix("s.")] }
        }
    }

    private fun int(v: Value?): Int? = (v as? QV)?.v?.setScale(0, RoundingMode.HALF_EVEN)?.toInt()

    private fun dec(v: Value?): BigDecimal? = (v as? QV)?.v

    private fun item(
        def: CompiledDefinition,
        params: ParamView,
        env: RuleEnv,
        resolver: ExerciseResolver,
        tf: Transform,
        tpl: ResolvedTemplate,
        item: CompiledItem,
        input: MaterializeInput,
    ): Pair<PlannedItem, String?> {
        val spec = item.spec
        val ctx = Ctx()
        val positionValue = item.position?.let { p ->
            when (p) {
                is FieldValue.Lit -> (p.value as? StrV)?.s
                is FieldValue.Expr -> try {
                    (eval(env, p.expr) as? StrV)?.s
                } catch (_: EvalError) {
                    null
                }
                else -> null
            }
        }
        fun planned(status: String, reason: String?, exercise: ResolvedExercise?): Pair<PlannedItem, String?> =
            PlannedItem(
                key = spec.key, exerciseKey = exercise?.key, catalogSlug = exercise?.catalogSlug, name = exercise?.name ?: emptyMap(),
                role = spec.role, status = status, statusReason = reason, rows = emptyList(), rirTarget = spec.rirTarget,
                progressionMode = spec.progression ?: if (spec.role == "test" || spec.role == "warmup") "none" else "rule",
                metric = spec.metric ?: spec.records, record = spec.record, warnings = ctx.warnings.toList(), noteCodes = ctx.notes.toList(),
            ) to positionValue

        // Step 1: enabledIf.
        item.enabledIf?.let { c ->
            val on = try {
                (eval(env, c) as? BoolV)?.b == true
            } catch (_: EvalError) {
                false
            }
            if (!on) return planned("excluded", "enabledIf: ${c.source}", null)
        }
        // Step 5 (resolved first: steps 2 and 3 need the exercise): the exercise choice.
        var exKey: String? = when (val e = item.exercise) {
            is ExerciseChoice.Fixed -> e.key
            is ExerciseChoice.AnyOf -> pick(e.keys, resolver, input, ctx)
            is ExerciseChoice.Expr -> try {
                (eval(env, e.expr) as? StrV)?.s
            } catch (_: EvalError) {
                null
            }
            null -> null
        }
        var ex = exKey?.let { resolver.resolve(it) }
        if (item.exercise is ExerciseChoice.Fixed && ex != null && prefOf(ex, input) == "never") {
            ctx.warnings += PlanWarning("challenge_never_conflict", "WARN", "${ex.key} is rated never; the challenge prescribes it")
        }
        // Step 2: required stations.
        if (ex != null) {
            val missing = ex.stations.filter { it !in input.facts.has }
            if (missing.isNotEmpty()) return planned("excluded", "needs: ${missing.joinToString(", ")}", ex)
        }
        // Step 3: tags excluded by the mode.
        if (ex != null && ex.tags.any { it in tf.excludeItemTags }) return planned("excluded", "mode:${env.state.mode}", ex)
        // Step 4: skipIf.
        item.skipIf?.let { c ->
            val skip = try {
                (eval(env, c) as? BoolV)?.b == true
            } catch (_: EvalError) {
                false
            }
            if (skip) return planned("skipped", "skipIf: ${c.source}", ex)
        }
        // Step 6: live substitution.
        var substitutedFrom: String? = null
        var progressionExcluded = false
        val fields = LinkedHashMap(item.fields)
        val reason = input.liveReasons[spec.key]
        val sub = def.doc.substitutions.firstOrNull { it.from == exKey && reason != null && it.whenReason == reason }
        if (sub != null) {
            substitutedFrom = exKey
            exKey = sub.to
            ex = resolver.resolve(sub.to)
            progressionExcluded = sub.progressionData == "excluded"
            sub.fingers?.let { fields["fingers"] = FieldValue.Lit(Values.int(it)) }
            fields.remove("assistPin")
        }
        // Step 7: variant.
        var variantRaw: JsonObject? = null
        val variant = spec.variantFixed ?: spec.variantVar?.let { (env.state.vars[it.removePrefix("s.")] as? StrV)?.s }
        if (variant != null) {
            item.variants[variant]?.let { fields.putAll(it) }
            variantRaw = spec.variants[variant]
            val vp = (variantRaw?.get("proposal") as? JsonPrimitive)?.content
            if (vp != null && !env.state.approved(vp)) ctx.notes += "ask_ladder_count"
        }
        // Light bilateral overrides (injury, P27) replace fields of the named exercises.
        if (tf.lightBilateral && exKey != null) tf.lightOverrides[exKey]?.let { fields.putAll(it) }
        if (!tf.lightBilateral && tf.keepItLightNote && env.state.mode == Modes.INJURY && spec.role != "warmup") ctx.notes += "keep_it_light"

        // Step 8: numeric fields.
        var sets = int(value(env, fields["sets"], ctx, spec.key, "sets"))
        val repsField = fields["reps"]
        // A `repsRange` prescribes its lower end as the target and its upper end as `targetRepsMax`.
        val reps = if (repsField is FieldValue.Keyword) null else int(value(env, repsField, ctx, spec.key, "reps")) ?: spec.repsRange.getOrNull(0)
        if (repsField is FieldValue.Keyword) ctx.notes += repsField.word
        val repsMax = spec.repsRange.getOrNull(1)
        val holdField = fields["hold_s"]
        var hold = dec(value(env, holdField, ctx, spec.key, "hold_s"))
        var holdMax = (holdField as? FieldValue.Range)?.hi?.let(::dec)
        val ecc = dec(value(env, fields["ecc_s_target"], ctx, spec.key, "ecc_s_target"))
        val duration = dec(value(env, fields["duration_s"], ctx, spec.key, "duration_s"))
        var added = dec(value(env, fields["added"], ctx, spec.key, "added"))
        var pin = dec(value(env, fields["assistPin"], ctx, spec.key, "assistPin"))
        val fingers = int(value(env, fields["fingers"], ctx, spec.key, "fingers"))
        var ladders = int(value(env, fields["ladders"], ctx, spec.key, "ladders"))
        var rounds = spec.rounds
        var rirTarget = spec.rirTarget

        // Step 9: mode transform.
        fun transformed(n: Int?, expr: CompiledExpr?): Int? {
            if (n == null || expr == null) return n
            return try {
                int(eval(env, expr, mapOf("sets" to Values.int(n), "item" to RecordV("item", mapOf("sets" to Values.int(n), "reps" to Values.int(reps ?: 0), "rounds" to Values.int(rounds ?: 0))))))
            } catch (e: EvalError) {
                ctx.warnings += PlanWarning("could_not_compute", "WARN", "${spec.key} mode transform: ${e.code}")
                n
            }
        }
        val exempt = spec.role == "test" || spec.role in tf.exemptRoles
        if (!exempt) {
            sets = transformed(sets, tf.sets)
            rounds = transformed(rounds, tf.sets)
            ladders = transformed(ladders, tf.sets)
            if (tf.lightBilateral && spec.role == "main") {
                sets = transformed(sets, tf.lightSets)
                rirTarget = maxOf(rirTarget ?: 0, tf.lightRirMin ?: 0)
            }
            if (spec.role == "prehab") tf.prehabSetsFactor?.let { f -> sets = sets?.let { it * f } }
            tf.holdSecondsFactor?.let { f ->
                val k = Dec.ofParam(f)
                hold = hold?.multiply(k)?.setScale(1, RoundingMode.HALF_EVEN)
                holdMax = holdMax?.multiply(k)?.setScale(1, RoundingMode.HALF_EVEN)
            }
            tf.loadFactor?.let { f ->
                if (added != null && f != 1.0) added = env.host.roundLoad(added.multiply(Dec.ofParam(f)), "belt", "down")
            }
        }
        // WE-S1: a RULE-mode target of 0 RIR on a high-risk lift becomes 1.
        if (rirTarget == 0 && ex?.highRisk == true) {
            rirTarget = 1
            ctx.warnings += PlanWarning("WE-S1", "ENFORCED", "${spec.key}: target RIR 0 raised to 1 on a high-risk lift")
        }

        // Step 11: load resolution (LOAD-1 only).
        var systemLoad: BigDecimal? = null
        var assistKg: BigDecimal? = null
        var oneArm: BigDecimal? = null
        var breakdown: List<BigDecimal>? = null
        val bw = input.facts.bwKg
        if (added != null && ex != null && ex.loadMode == "bodyweight_plus_external") {
            val profile = input.facts.loadProfiles["belt"]
            if (profile != null && profile.achievableKg.none { it.compareTo(added) == 0 }) {
                val down = env.host.roundLoad(added, "belt", "down")
                ctx.warnings += PlanWarning("not_loadable", "WARN", "${spec.key}: +${added.toPlainString()} kg is not loadable; using +${down.toPlainString()} kg")
                added = down
            }
            input.beltScheme?.let { b ->
                if (added.signum() > 0) {
                    breakdown = PlateBreakdown.solve(Dec.toDouble(added), b.plates, b.maxItems)?.map { Dec.kg(it) }
                }
            }
            if (bw != null) systemLoad = Grid.decimal(ex.fBw.multiply(bw).add(added), GridKind.KG)
        }
        if (pin != null && def.machineId != null) {
            val cal = input.facts.machines[def.machineId]
            if (cal != null) {
                val max = cal.max ?: Dec.ofParam(params.double("load.pin_max_unknown_native"))
                pin = pin.max(cal.min).min(max)
                assistKg = env.host.assistKg(pin!!)
                if (bw != null) {
                    oneArm = env.host.oneArmLoad(pin)
                    systemLoad = oneArm
                }
            }
        }
        // WE-S2: compare a RULE load with the last exposure (B9.11, B9.14); holds are not evaluated (C70).
        val prev = input.lastExposureWsys[spec.key]
        if (systemLoad != null && prev != null && prev.signum() > 0 && (spec.progression ?: "rule") == "rule" && ex?.logAs != "hold") {
            val capUp = Dec.ofParam(params.double(if (ex?.compound == true) "rx.cap_up_compound" else "rx.cap_up_isolation"))
            val capDown = Dec.ofParam(params.double("rx.cap_down"))
            val ratio = systemLoad.divide(prev, MathContext.DECIMAL64)
            val pct = Grid.decimal(ratio.subtract(BigDecimal.ONE).movePointRight(PERCENT), GridKind.KG)
            if (ratio > BigDecimal.ONE.add(capUp) || ratio < BigDecimal.ONE.subtract(capDown)) {
                ctx.warnings += PlanWarning("WE-S2", "WARN", "${spec.key}: ${pct.toPlainString()} % system load against a ${Dec.norm(capUp.movePointRight(PERCENT)).toPlainString()} % cap (rule load, not clamped)")
            }
        }

        // Steps 10 and 12: rows, rest and log mode.
        val logMode = when {
            spec.role == "warmup" -> LogMode.DONE
            spec.role == "test" -> LogMode.TEST_RECORD
            spec.role == "attempt" || ex?.logAs == "attempt" -> LogMode.ATTEMPT_CHECKLIST
            ex?.logAs == "hold" -> LogMode.HOLD_CHIPS
            ex?.logAs == "eccentric" -> LogMode.ECC_SECONDS
            else -> LogMode.RIR_CHIPS
        }
        val sides = if (spec.perSide) spec.sideOrder.ifEmpty { listOf("L", "R") } else listOf("both")
        val setKind = when (spec.role) {
            "warmup" -> "warmup"
            "test" -> "test"
            "attempt" -> "attempt"
            else -> "working"
        }
        fun row(side: String, index: Int, pair: Int?, targetReps: Int? = reps, rest: Int? = spec.restS) = PlannedRow(
            side = side, setKind = setKind, setIndex = index, pairIndex = pair, targetReps = targetReps, targetRepsMax = repsMax,
            targetHoldS = hold, targetHoldMaxS = holdMax, targetEccS = ecc, durationS = duration?.let { Dec.norm(it) },
            addedKg = added?.let(Dec::norm), plateBreakdown = breakdown, systemLoadKg = systemLoad, pin = pin?.let(Dec::norm),
            assistKg = assistKg, oneArmLoadKg = oneArm, fingers = fingers, attempts = spec.attemptsPerRound ?: spec.attempts,
            restS = rest, logMode = logMode,
        )
        val rows = ArrayList<PlannedRow>()
        val rungs = (variantRaw?.get("rungs") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: spec.rungs
        when {
            ladders != null && rungs.isNotEmpty() -> {
                val betweenRungs = TemplateResolver.int(variantRaw?.get("rest_s_between_rungs")) ?: spec.restSBetweenRungs
                val betweenLadders = TemplateResolver.int(variantRaw?.get("rest_s_between_ladders")) ?: spec.restSBetweenLadders
                var idx = 1
                for (l in 1..ladders) for ((ri, rung) in rungs.withIndex()) {
                    rows += row(sides.first(), idx++, l, targetReps = rung, rest = if (ri == rungs.lastIndex) betweenLadders else betweenRungs)
                }
            }
            spec.role == "test" -> sides.forEachIndexed { i, side -> rows += row(side, i + 1, if (spec.perSide) 1 else null) }
            rounds != null -> for (r in 1..rounds) for (side in sides) rows += row(side, r, if (spec.perSide) r else null)
            duration != null && sets == null -> rows += row(sides.first(), 1, null)
            else -> for (i in 1..(sets ?: 1)) for (side in sides) rows += row(side, i, if (spec.perSide) i else null)
        }
        val notes = ArrayList<I18n>()
        spec.note?.let { notes += it }
        val approvedProtocol = spec.protocolProposal?.let { env.state.approved(it) } == true
        (if (approvedProtocol) spec.protocolIfApproved ?: spec.protocol else spec.protocol)?.let { notes += it }
        val steps = (if (approvedProtocol) item.stepsIfApproved ?: item.steps else item.steps)?.let { testSteps(env, it, input, ctx, spec.key) }
        val ruleIds = def.inSessionRules.filter { r ->
            (r.spec.item == spec.key || r.spec.item == "*") && (r.spec.slots.isEmpty() || tpl.slot in r.spec.slots) &&
                (r.spec.tag != "P" || r.spec.proposal?.let { env.state.approved(it) } == true)
        }.map { it.id }
        return PlannedItem(
            key = spec.key, exerciseKey = ex?.key, catalogSlug = ex?.catalogSlug, name = ex?.name ?: emptyMap(), role = spec.role,
            status = "planned", rows = rows, rirTarget = rirTarget, flagsOffered = ex?.flagsOffered.orEmpty(), stopSetOn = spec.stopSetOn,
            notes = notes, progressionMode = spec.progression ?: if (spec.role == "test" || spec.role == "warmup") "none" else "rule",
            inSessionRuleIds = ruleIds, calibrationFirstSet = spec.calibrationFirstSet, metric = spec.metric ?: spec.records, record = spec.record,
            substitutedFrom = substitutedFrom, progressionExcluded = progressionExcluded, warnings = ctx.warnings.toList(), noteCodes = ctx.notes.toList(),
            steps = steps,
        ) to positionValue
    }

    /**
     * D36: the test protocol's numbers. Ramp loads are `fraction * rampOf - bw` on the belt grid (nearest, at least
     * bodyweight), without duplicates and below the first attempt. A field that cannot be computed is absent and warns.
     */
    private fun testSteps(env: RuleEnv, c: CompiledSteps, input: MaterializeInput, ctx: Ctx, key: String): TestSteps {
        fun ev(e: CompiledExpr?, field: String): Value? = e?.let {
            try {
                eval(env, it)
            } catch (x: EvalError) {
                ctx.warnings += PlanWarning("could_not_compute", "WARN", "$key.steps.$field: ${x.code}")
                null
            }
        }
        val start = dec(ev(c.start, "start"))?.let(Dec::norm)
        val total = dec(ev(c.rampOf, "rampOf"))
        val fractions = (ev(c.rampFractions, "rampFractions") as? ListV)?.items?.mapNotNull(::dec).orEmpty()
        val bw = input.facts.bwKg
        val ramp = if (total == null || bw == null || !(c.rampReps.size == 2)) {
            emptyList()
        } else {
            try {
                fractions.map { f -> Dec.norm(env.host.roundLoad(f.multiply(total).subtract(bw).max(BigDecimal.ZERO), "belt", "nearest")) }
                    .distinct().filter { start == null || it < start }.map { RampSet(it, c.rampReps[0], c.rampReps[1]) }
            } catch (x: EvalError) {
                ctx.warnings += PlanWarning("could_not_compute", "WARN", "$key.steps.ramp: ${x.code}")
                emptyList()
            }
        }
        return TestSteps(ramp, start, dec(ev(c.step, "step"))?.let(Dec::norm), int(ev(c.maxAttempts, "maxAttempts")))
    }

    private fun prefOf(ex: ResolvedExercise, input: MaterializeInput): String =
        input.preferences[ex.catalogSlug ?: ex.key] ?: input.preferences[ex.key] ?: "neutral"

    /** 6.7: the available option with the highest PREF-1 tier; ties keep list order; `never` only as a last resort. */
    private fun pick(keys: List<String>, resolver: ExerciseResolver, input: MaterializeInput, ctx: Ctx): String? {
        val available = keys.mapNotNull { k -> resolver.resolve(k)?.takeIf { e -> e.stations.all { it in input.facts.has } } }
        if (available.isEmpty()) return keys.firstOrNull()
        val usable = available.filter { prefOf(it, input) != "never" }
        if (usable.isEmpty()) {
            ctx.warnings += PlanWarning("challenge_never_conflict", "WARN", "every option is rated never; the challenge prescribes ${available.first().key}")
            return available.first().key
        }
        return usable.minByOrNull { PREF_ORDER.indexOf(prefOf(it, input)).let { i -> if (i < 0) PREF_ORDER.indexOf("neutral") else i } }?.key
    }

    /** Step 13: stable reorder by `position` (first = after the leading warm-ups, C65). */
    private fun order(items: List<Pair<PlannedItem, String?>>): List<PlannedItem> {
        val list = items.map { it.first }.toMutableList()
        for ((item, pos) in items) {
            if (pos == null || pos == "inPlace") continue
            val from = list.indexOf(item)
            list.removeAt(from)
            val to = when {
                pos == "first" -> list.indexOfFirst { it.role != "warmup" }.let { if (it < 0) list.size else it }
                pos == "last" -> list.size
                pos == "lastMain" -> list.indexOfLast { it.role == "main" || it.role == "test" || it.role == "attempt" }.let { it + 1 }
                pos.startsWith("after:") -> list.indexOfFirst { it.key == pos.removePrefix("after:") }.let { if (it < 0) from else it + 1 }
                else -> from
            }
            list.add(to.coerceIn(0, list.size), item)
        }
        return list
    }
}
