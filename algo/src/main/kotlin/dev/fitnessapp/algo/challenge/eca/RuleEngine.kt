package dev.fitnessapp.algo.challenge.eca

import dev.fitnessapp.algo.challenge.cds.Action
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.cds.CompiledRule
import dev.fitnessapp.algo.challenge.cds.LockKind
import dev.fitnessapp.algo.challenge.cds.Modes
import dev.fitnessapp.algo.challenge.cds.SemanticValidator
import dev.fitnessapp.algo.challenge.cds.Stage
import dev.fitnessapp.algo.challenge.enrollment.BlockPos
import dev.fitnessapp.algo.challenge.enrollment.ChallengeEvent
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.enrollment.PlannedTest
import dev.fitnessapp.algo.challenge.enrollment.StateJson
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.fitexpr.Dim
import dev.fitnessapp.algo.challenge.fitexpr.EvalError
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.RecordV
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.StrV
import dev.fitnessapp.algo.challenge.fitexpr.Type
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.fitexpr.ValueJson
import dev.fitnessapp.algo.challenge.fitexpr.Values
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.params.DeclaresParams
import dev.fitnessapp.algo.core.params.ParamSpec
import dev.fitnessapp.algo.core.params.ParamType
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.SortedMap
import java.util.TreeMap
import java.util.UUID

/**
 * `challenge.eca@1.0.0` (CHALLENGE_ENGINE 5): staged snapshot evaluation of a definition's rules for one
 * event. Conditions, `let` bindings and action values read the frozen pre-event state; rules run in the
 * total order (stage, prio desc, id); locks are collected before any write; the first writer of a variable
 * wins; a safety rule that enters injury suppresses every progression write of the evaluation; frozen modes
 * suppress progression writes to snapshot vars unless the rule allows it; derived events cascade within
 * `cascade_max_depth`. Every rule with a true or erroring condition yields one [AuditRow].
 */
object RuleEngine : DeclaresParams {
    val ref: ModuleRef = ModuleRegistry.ref("challenge.eca")

    override val paramSpecs: List<ParamSpec> = listOf(
        ParamSpec.integer("challenge.eca.cascade_max_depth", "levels", 3.0, 3.0),
        ParamSpec.integer("challenge.eca.max_conditional_nesting", "levels", 4.0, 4.0),
        ParamSpec("challenge.eca.notify_default_once", ParamType.ENUM, "enum", enumValues = listOf("perDay", "perBlock", "ever")),
        ParamSpec("challenge.eca.stage_order", ParamType.ENUM_ARRAY, "enum list (stages)", enumValues = Stage.entries.map { it.code }),
    )

    class Result(
        val state: EnrollmentState,
        val audits: List<AuditRow>,
        val notifications: List<ChallengeNotification>,
        val derivedEvents: List<String>,
        val warnings: List<String>,
    )

    /** Builds the frozen context of an event against a state (supplied by the fold). */
    fun interface EnvFactory {
        fun create(state: EnrollmentState, event: ChallengeEvent): RuleEnv
    }

    private class Proposal(val target: String, val value: Value, val notes: List<String> = emptyList())

    private class Fired(
        val rule: CompiledRule,
        val writes: List<Proposal>,
        val locks: List<Pair<List<String>, LockKind>>,
        val notifies: List<Action.Notify>,
        val lets: Map<String, Value>,
        val bindings: SortedMap<String, String>,
    )

    private class Arbitrated(val rule: Fired, val records: MutableList<WriteRecord> = ArrayList())

    fun evaluate(
        def: CompiledDefinition,
        params: ParamView,
        factory: EnvFactory,
        event: ChallengeEvent,
        s0: EnrollmentState,
        depth: Int = 0,
        /** Derived event types already evaluated for this root event; shared down and across the cascade. */
        cascadeSeen: MutableSet<String> = sortedSetOf(),
    ): Result {
        val env = factory.create(s0, event)
        val hashBefore = StateJson.hash(s0)
        val terminal = s0.status == EnrollmentState.COMPLETED || s0.status == EnrollmentState.ABANDONED
        val pausedFilter = s0.status == EnrollmentState.PAUSED &&
            event.type !in setOf(EventTypes.PAIN, EventTypes.READINESS, EventTypes.RESUME)
        val candidates = if (terminal) emptyList() else def.rules.filter { r ->
            event.type in r.spec.on && gateOpen(r, env) && (!pausedFilter || r.stage == Stage.SAFETY)
        }
        val evaluationId = Ids.fitness("challenge_eval:${event.id}:$depth:${event.type}")
        val audits = ArrayList<AuditRow>()
        val fired = ArrayList<Fired>()
        val baseRoots = env.roots()

        fun errorRow(r: CompiledRule, code: String, cond: Boolean?) {
            audits += row(def, env, evaluationId, event, depth, r, Outcome.ERROR, cond, code, sortedMapOf(), emptyList(), null, null, hashBefore, hashBefore)
        }

        for (r in candidates) {
            val c = try {
                (r.condition.evaluate(baseRoots, env.host) as? BoolV)?.b ?: throw EvalError.type("condition is not a Bool")
            } catch (e: EvalError) {
                errorRow(r, e.code, null)
                continue
            }
            if (!c) continue
            val lets = LinkedHashMap<String, Value>()
            try {
                for ((name, expr) in r.lets) lets[name] = expr.evaluate(env.roots(lets), env.host)
            } catch (e: EvalError) {
                errorRow(r, e.code, true)
                continue
            }
            val roots = env.roots(lets)
            val writes = ArrayList<Proposal>()
            val locks = ArrayList<Pair<List<String>, LockKind>>()
            val notifies = ArrayList<Action.Notify>()
            try {
                expand(def, env, s0, r.actions, roots, writes, locks, notifies)
            } catch (e: EvalError) {
                errorRow(r, e.code, true)
                continue
            }
            fired += Fired(r, writes, locks, notifies, lets, bindings(r, roots, lets))
        }

        // Locks of every fired rule apply to the whole evaluation (5.5).
        val lockMap = sortedMapOf<String, MutableList<Pair<LockKind, String>>>()
        for (f in fired) for ((vars, kind) in f.locks) for (v in vars) lockMap.getOrPut(v) { ArrayList() } += kind to f.rule.id
        val injuryRule = fired.firstOrNull { f -> f.rule.stage == Stage.SAFETY && f.writes.any { it.target == MODE && (it.value as StrV).s == Modes.INJURY } }
        val frozen = Modes.frozen(s0.mode)
        val snapshotVars = def.snapshotVars.toSet()
        val written = sortedMapOf<String, String>()
        val applied = LinkedHashMap<String, Value>()
        val arbitrated = fired.map { Arbitrated(it) }

        for (a in arbitrated) {
            val r = a.rule.rule
            for (w in a.rule.writes) {
                val old = currentValue(s0, w.target)
                val oldText = old?.let(ValueJson::text)
                val newText = ValueJson.text(w.value)
                val varName = w.target.removePrefix("s.")
                val lock = lockMap[varName]?.firstOrNull { (k, _) -> violates(k, old, w.value) }
                val rec = when {
                    injuryRule != null && r.stage == Stage.PROGRESSION ->
                        WriteRecord(w.target, oldText, newText, Outcome.SUPPRESSED_INJURY, injuryRule.rule.id, w.notes)
                    // 4.8, 11.1: injury ends only through a safety-stage rule (the definition's exit rule).
                    w.target == MODE && s0.mode == Modes.INJURY && r.stage != Stage.SAFETY && (w.value as StrV).s != Modes.INJURY ->
                        WriteRecord(w.target, oldText, newText, Outcome.SUPPRESSED_LOCK, "mode:injury", w.notes)
                    frozen && r.stage == Stage.PROGRESSION && w.target.startsWith("s.") && varName in snapshotVars && !r.spec.allowInFrozen ->
                        WriteRecord(w.target, oldText, newText, Outcome.SUPPRESSED_LOCK, "mode:${s0.mode}", w.notes)
                    written.containsKey(w.target) -> WriteRecord(w.target, oldText, newText, Outcome.SUPPRESSED_FIRST_WRITER, written[w.target], w.notes)
                    w.target.startsWith("s.") && lock != null -> WriteRecord(w.target, oldText, newText, Outcome.SUPPRESSED_LOCK, lock.second, w.notes)
                    old != null && isNoEffect(w.target, old, w.value) -> WriteRecord(w.target, oldText, newText, Outcome.NO_EFFECT, null, w.notes)
                    else -> {
                        applied[w.target] = w.value
                        written[w.target] = r.id
                        // Starting or restarting a block also writes the mode (5.2): a later `$mode` write loses.
                        if (w.target == BLOCK && (w.value as StrV).s.let { it == "restartBlock" || it.startsWith("startNewBlock:") }) {
                            written.putIfAbsent(MODE, r.id)
                        }
                        WriteRecord(w.target, oldText, newText, Outcome.APPLIED, null, w.notes)
                    }
                }
                a.records += rec
            }
        }

        val warnings = ArrayList<String>()
        val derivedEvents = ArrayList<String>()
        val keepInjury = s0.mode == Modes.INJURY && written[BLOCK]?.let { id -> def.rules.first { it.id == id }.stage != Stage.SAFETY } == true
        val s1 = commit(def, params, env, event, s0, applied, derivedEvents, warnings, keepInjury)

        // Notifications (5.7), deduplicated against what this enrollment already sent.
        val notifications = ArrayList<ChallengeNotification>()
        val sent = sortedSetOf<String>()
        sent.addAll(s1.notificationsSent)
        val defaultOnce = params.string("challenge.eca.notify_default_once")
        for (a in arbitrated) {
            if (a.records.isNotEmpty() && a.records.all { it.outcome == Outcome.SUPPRESSED_INJURY }) continue
            for (n in a.rule.notifies) {
                val once = n.once.ifEmpty { defaultOnce }
                val scope = when (once) {
                    "perBlock" -> "block:${s1.block.index}"
                    "ever" -> "ever"
                    else -> env.today.toString()
                }
                val key = n.msg + "|" + scope
                if (!sent.add(key)) continue
                notifications += ChallengeNotification(n.kind, n.msg, a.rule.lets.mapValues { ValueJson.text(it.value) }.toSortedMap(), a.rule.rule.id, scope)
            }
        }
        var state = s1.copy(notificationsSent = sent)
        val hashAfter = StateJson.hash(state)
        for (a in arbitrated) {
            // A rule that only locks or notifies has an effect; one whose actions resolved to nothing has none.
            val outcome = if (a.records.isEmpty()) {
                if (a.rule.locks.isNotEmpty() || a.rule.notifies.isNotEmpty()) Outcome.APPLIED else Outcome.NO_EFFECT
            } else {
                summarize(a.records)
            }
            val suppressedBy = a.records.firstOrNull { it.suppressedBy != null }?.suppressedBy
            audits += row(
                def, env, evaluationId, event, depth, a.rule.rule, outcome, true, null, a.rule.bindings, a.records, suppressedBy,
                explain(a.rule, s0, state, env), hashBefore, hashAfter,
            )
        }
        val allAudits = ArrayList(audits.sortedWith(compareBy<AuditRow>({ Stage.of(it.stage)?.ordinal ?: 0 }, { -it.prio }, { it.ruleId })))
        val allNotifications = ArrayList(notifications)

        // Bounded cascade of derived events (5.6): each derived type at most once per root event.
        val maxDepth = params.int("challenge.eca.cascade_max_depth")
        for (d in derivedEvents) {
            if (!cascadeSeen.add(d)) continue
            val listening = def.rules.filter { d in it.spec.on }
            if (listening.isEmpty()) continue
            val derived = ChallengeEvent(event.id, d, event.occurredAt, event.localDate, EventPayload.Empty, event.engineFacts)
            if (depth >= maxDepth) {
                warnings += "cascade_limit:$d"
                val cutId = Ids.fitness("challenge_eval:${event.id}:${depth + 1}:$d")
                for (r in listening) {
                    allAudits += row(def, env, cutId, derived, depth + 1, r, Outcome.ERROR, null, "cascade_limit", sortedMapOf(), emptyList(), null, null, hashAfter, hashAfter)
                        .copy(derivedEventType = d)
                }
                continue
            }
            val sub = evaluate(def, params, factory, derived, state, depth + 1, cascadeSeen)
            state = sub.state
            allAudits += sub.audits.map { it.copy(derivedEventType = it.derivedEventType ?: d) }
            allNotifications += sub.notifications
            warnings += sub.warnings
        }
        return Result(state, allAudits, allNotifications, derivedEvents, warnings)
    }

    private const val MODE = "\$mode"
    private const val PHASE = "\$phase"
    private const val BLOCK = "\$block"
    private const val PLANNED_TEST = "\$plannedTest"

    /** Proposal gating of 5.4, enforced independently of the rule's own guards. */
    fun gateOpen(r: CompiledRule, env: RuleEnv): Boolean {
        if (r.spec.tag == "P") {
            val p = r.spec.proposal ?: return false
            return env.state.approved(p)
        }
        r.spec.supersededBy?.let { return !env.state.approved(it) }
        return true
    }

    private fun expand(
        def: CompiledDefinition,
        env: RuleEnv,
        s0: EnrollmentState,
        actions: List<Action>,
        roots: Map<String, Value>,
        writes: MutableList<Proposal>,
        locks: MutableList<Pair<List<String>, LockKind>>,
        notifies: MutableList<Action.Notify>,
    ) {
        for (a in actions) when (a) {
            is Action.Set -> writes += canonicalWrite(def, env, a.variable, a.to.evaluate(roots, env.host))
            is Action.Adjust -> {
                val old = s0.vars[a.variable] as? QV ?: throw EvalError.missing("s.${a.variable}")
                val by = a.by.evaluate(roots, env.host) as? QV ?: throw EvalError.type("adjust by a non-number")
                val sum = old.v.add(by.v)
                val rounded = when (old.dim) {
                    Dim.MASS -> env.host.roundLoad(sum, a.roundProfile ?: throw EvalError.missing("round.profile"), a.roundMode ?: "nearest")
                    Dim.PIN -> env.host.roundPin(sum, a.roundMode ?: "nearest")
                    else -> sum
                }
                writes += canonicalWrite(def, env, a.variable, QV(old.dim, rounded))
            }
            is Action.Lock -> locks += a.variables to a.kind
            is Action.SetMode -> writes += Proposal(MODE, StrV(a.mode))
            is Action.Notify -> notifies += a
            is Action.RestoreSnapshot -> {
                val n = (a.block.evaluate(roots, env.host) as? QV)?.v?.intValueExact() ?: throw EvalError.type("snapshot index")
                val snap = s0.snapshots[n] ?: s0.snapshots.headMap(n).let { if (it.isEmpty()) null else it[it.lastKey()] }
                    ?: s0.snapshots[0] ?: throw EvalError.missing("snapshots")
                for (v in def.snapshotVars) snap[v]?.let { writes += canonicalWrite(def, env, v, it, listOf("restore:$n")) }
            }
            Action.AdvancePhase -> writes += Proposal(PHASE, StrV("advance"))
            Action.AdvanceWeek -> writes += Proposal(BLOCK, StrV("advanceWeek"))
            Action.RepeatWeek -> writes += Proposal(BLOCK, StrV("repeatWeek"))
            is Action.StartNewBlock -> {
                val mode = a.firstWeekMode?.let { (it.evaluate(roots, env.host) as? StrV)?.s } ?: Modes.NORMAL
                if (mode !in Modes.ALL) throw EvalError.type("firstWeekMode $mode")
                writes += Proposal(BLOCK, StrV("startNewBlock:$mode"))
            }
            Action.RestartBlock -> writes += Proposal(BLOCK, StrV("restartBlock"))
            is Action.ScheduleTest -> writes += Proposal(PLANNED_TEST, StrV("schedule:${a.how}"))
            is Action.PostponeTest -> writes += Proposal(PLANNED_TEST, StrV("postpone"))
            is Action.If -> {
                val c = (a.condition.evaluate(roots, env.host) as? BoolV)?.b ?: throw EvalError.type("conditional action")
                expand(def, env, s0, if (c) a.then else a.otherwise, roots, writes, locks, notifies)
            }
        }
    }

    /** Rounds to the canonical grid of the var's type (C22, C42) and enforces its declared bounds. */
    private fun canonicalWrite(def: CompiledDefinition, env: RuleEnv, name: String, value: Value, notes: List<String> = emptyList()): Proposal {
        val info = def.stateVarByName[name] ?: throw EvalError.missing("s.$name")
        val v = canonical(info.type, value, env)
        val n = ArrayList(notes)
        var out = v
        if (v is QV) {
            (info.min as? QV)?.let { if (v.v < it.v) { out = QV(v.dim, it.v); n += "clamp" } }
            (info.max as? QV)?.let { if (v.v > it.v) { out = QV(v.dim, it.v); n += "clamp" } }
        }
        return Proposal("s.$name", out, n)
    }

    fun canonical(type: Type, value: Value, env: RuleEnv): Value {
        if (value !is QV || type !is Type.Q) return value
        val v = when (type.dim) {
            Dim.MASS -> Grid.decimal(value.v, GridKind.KG)
            Dim.DURATION -> Grid.decimal(value.v, GridKind.S)
            Dim.NUM -> Grid.decimal(value.v, GridKind.RATIO)
            Dim.INT, Dim.DAYS -> value.v.setScale(0, RoundingMode.HALF_EVEN)
            Dim.PIN -> if (env.def.machineId != null && env.facts.machines[env.def.machineId] != null) env.host.roundPin(value.v, "nearest") else value.v
        }
        return QV(type.dim, Dec.norm(v))
    }

    private fun currentValue(s: EnrollmentState, target: String): Value? = when (target) {
        MODE -> StrV(s.mode)
        PHASE -> Values.int(s.phase)
        BLOCK -> StrV("${s.block.index}/${s.block.week}")
        PLANNED_TEST -> StrV("${s.plannedTest.basis}:${s.plannedTest.date}")
        else -> s.vars[target.removePrefix("s.")]
    }

    private fun isNoEffect(target: String, old: Value, new: Value): Boolean = when (target) {
        PHASE, BLOCK, PLANNED_TEST -> false
        else -> Values.same(old, new) || (old == new)
    }

    /** Lock direction is numeric (for a pin, "increase" means more assistance). */
    private fun violates(kind: LockKind, old: Value?, new: Value): Boolean {
        if (old == null) return false
        if (kind == LockKind.FREEZE) return !(Values.same(old, new) || old == new)
        val a = old as? QV ?: return false
        val b = new as? QV ?: return false
        return when (kind) {
            LockKind.NO_INCREASE -> b.v > a.v
            LockKind.NO_DECREASE -> b.v < a.v
            LockKind.FREEZE -> false
        }
    }

    private fun summarize(records: List<WriteRecord>): Outcome = when {
        records.any { it.outcome == Outcome.APPLIED } -> Outcome.APPLIED
        else -> records.firstOrNull { it.outcome != Outcome.NO_EFFECT }?.outcome ?: Outcome.NO_EFFECT
    }

    private fun countsAsBlockWeek(def: CompiledDefinition, env: RuleEnv, mode: String): Boolean {
        var m: String? = mode
        val visited = sortedSetOf<String>()
        while (m != null && visited.add(m)) {
            val spec = def.doc.structure.modes[m] ?: break
            spec.countsAsBlockWeek?.let { return it }
            m = if (spec.proposal != null && !env.state.approved(spec.proposal)) spec.inheritsIfDeclined else spec.inherits
        }
        return mode == Modes.NORMAL
    }

    private fun commit(
        def: CompiledDefinition,
        params: ParamView,
        env: RuleEnv,
        event: ChallengeEvent,
        s0: EnrollmentState,
        applied: Map<String, Value>,
        derived: MutableList<String>,
        warnings: MutableList<String>,
        /** A non-safety block change during injury keeps the injury mode (4.8). */
        keepInjury: Boolean,
    ): EnrollmentState {
        val vars = TreeMap(s0.vars)
        for ((t, v) in applied) if (t.startsWith("s.")) vars[t.removePrefix("s.")] = v
        var s = s0.copy(vars = vars)
        val today = env.today
        applied[PLANNED_TEST]?.let { v ->
            s = s.copy(
                plannedTest = when (val how = (v as StrV).s) {
                    "postpone" -> PlannedTest(null, "postponed", s0.plannedTest.date ?: today)
                    "schedule:fixedDate" -> env.nextFixedTestDate(today)?.let { PlannedTest(it, "fixedList") } ?: PlannedTest.NONE
                    else -> {
                        check(how == "schedule:testWeekday") { how }
                        PlannedTest(testDate(def, today), "blockRelative")
                    }
                },
            )
        }
        applied[BLOCK]?.let { v ->
            val how = (v as StrV).s
            when {
                how == "advanceWeek" -> if (countsAsBlockWeek(def, env, s0.mode)) {
                    val next = s.block.week + 1
                    if (next > params.int("challenge.enrollment.max_block_weeks")) {
                        warnings += "block_too_long"
                    } else {
                        s = s.copy(block = s.block.copy(week = next))
                    }
                }
                how == "repeatWeek" -> Unit
                how == "restartBlock" -> s = s.copy(block = s.block.copy(week = 1), plannedTest = PlannedTest.NONE)
                    .withMode(if (keepInjury) Modes.INJURY else Modes.NORMAL, today, derived)
                how.startsWith("startNewBlock:") -> {
                    val first = how.removePrefix("startNewBlock:")
                    val start = if (event.type == EventTypes.WEEK_CLOSED) event.localDate else nextMonday(today)
                    val idx = s.block.index + 1
                    val snap = java.util.TreeMap<String, Value>()
                    for (name in def.snapshotVars) s.vars[name]?.let { snap[name] = it }
                    val snaps = TreeMap(s.snapshots)
                    snaps[idx] = snap
                    s = s.copy(block = BlockPos(idx, 1, start), plannedTest = PlannedTest.NONE, blockValid = sortedMapOf(), snapshots = snaps)
                        .withMode(if (keepInjury) Modes.INJURY else first, today, derived)
                    if (keepInjury && first != Modes.INJURY) warnings += "block_started_in_injury"
                    derived += EventTypes.BLOCK_STARTED
                }
            }
        }
        applied[MODE]?.let { s = s.withMode((it as StrV).s, today, derived) }
        if (applied.containsKey(PHASE)) {
            val phase = def.doc.phases.firstOrNull { it.id == s0.phase }
            s = if (phase?.onExit == "complete") {
                s.copy(status = EnrollmentState.COMPLETED)
            } else {
                derived += EventTypes.PHASE_CHANGED
                s.copy(phase = s0.phase + 1, phaseSince = today, phaseObservations = sortedMapOf())
            }
        }
        return s
    }

    private fun EnrollmentState.withMode(mode: String, today: LocalDate, derived: MutableList<String>): EnrollmentState {
        if (mode == this.mode) return this
        if (EventTypes.MODE_CHANGED !in derived) derived += EventTypes.MODE_CHANGED
        return copy(mode = mode, modeSince = today)
    }

    private fun nextMonday(d: LocalDate): LocalDate = Week.isoWeekStart(d).plusWeeks(1)

    /** The test slot's weekday (`slotWeekday`, else `testDay.weekday`) in the ISO week of [today]. */
    fun testDate(def: CompiledDefinition, today: LocalDate): LocalDate {
        val slot = def.doc.structure.block.testSlot
        val wd = def.doc.schedule.hard.firstOrNull { it.type == "slotWeekday" && it.slot == slot }?.weekday ?: def.doc.schedule.testDay.weekday
        val dow = DayOfWeek.of(SemanticValidator.WEEKDAYS.indexOf(wd) + 1)
        return Week.isoWeekStart(today).plusDays((dow.value - 1).toLong())
    }

    private fun lookup(roots: Map<String, Value>, path: String): Value? {
        val parts = path.split('.')
        var v: Value = roots[parts[0]] ?: return null
        for (p in parts.drop(1)) {
            v = when (v) {
                is RecordV -> v.fields[p] ?: return null
                is SideV -> (if (p == "L") v.l else if (p == "R") v.r else null) ?: return null
                else -> return null
            }
        }
        return v
    }

    private fun bindings(r: CompiledRule, roots: Map<String, Value>, lets: Map<String, Value>): SortedMap<String, String> {
        val out = sortedMapOf<String, String>()
        val reads = (r.condition.reads + r.lets.flatMap { it.second.reads } + actionReads(r.actions)).toSortedSet()
        for (p in reads) {
            if (p.substringBefore('.') in lets.keys) continue
            out[p] = lookup(roots, p)?.let(ValueJson::text) ?: "<missing>"
        }
        for ((k, v) in lets) out[k] = ValueJson.text(v)
        return out
    }

    private fun actionReads(a: List<Action>): List<String> = a.flatMap { x ->
        when (x) {
            is Action.Set -> x.to.reads
            is Action.Adjust -> x.by.reads
            is Action.RestoreSnapshot -> x.block.reads
            is Action.StartNewBlock -> x.firstWeekMode?.reads.orEmpty()
            is Action.If -> x.condition.reads + actionReads(x.then) + actionReads(x.otherwise)
            else -> emptyList()
        }
    }

    /** Plain-language explanation from the rule's template and its bindings (5.9). */
    private fun explain(f: Fired, s0: EnrollmentState, s1: EnrollmentState, env: RuleEnv): String? {
        val text = f.rule.spec.explain["en"] ?: return null
        return Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}").replace(text) { m ->
            val k = m.groupValues[1]
            val v = f.lets[k] ?: s1.vars[k] ?: s0.vars[k] ?: env.effectiveParams[k]
            v?.let(ValueJson::text) ?: m.value
        }
    }

    private fun row(
        def: CompiledDefinition,
        env: RuleEnv,
        evaluationId: UUID,
        event: ChallengeEvent,
        depth: Int,
        r: CompiledRule,
        outcome: Outcome,
        cond: Boolean?,
        error: String?,
        bindings: SortedMap<String, String>,
        writes: List<WriteRecord>,
        suppressedBy: String?,
        explanation: String?,
        before: String,
        after: String,
    ): AuditRow {
        val pid = r.proposalId
        val decision = when {
            pid == null -> "not_required"
            env.state.proposalDecisions[pid]?.decision == "approved" -> "approved"
            else -> "declined"
        }
        return AuditRow(
            evaluationId, event.id, event.type, null, depth, r.id, r.auditTag, pid, decision, r.stage.code, r.spec.prio, outcome, cond,
            error, bindings, writes, suppressedBy, explanation, before, after,
        )
    }
}
