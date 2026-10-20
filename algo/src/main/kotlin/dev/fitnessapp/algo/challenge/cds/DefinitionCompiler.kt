package dev.fitnessapp.algo.challenge.cds

import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.CompileResult
import dev.fitnessapp.algo.challenge.fitexpr.CompiledExpr
import dev.fitnessapp.algo.challenge.fitexpr.DateV
import dev.fitnessapp.algo.challenge.fitexpr.Dim
import dev.fitnessapp.algo.challenge.fitexpr.FitExpr
import dev.fitnessapp.algo.challenge.fitexpr.ListV
import dev.fitnessapp.algo.challenge.fitexpr.MapV
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.StrV
import dev.fitnessapp.algo.challenge.fitexpr.Type
import dev.fitnessapp.algo.challenge.fitexpr.TypeScope
import dev.fitnessapp.algo.challenge.fitexpr.Value
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Compiles a structurally valid [DefinitionDoc] (section 3.10): every FitExpr string is parsed and type
 * checked in the scope of its field (V01-V03), assignments are checked against the declared state (V04),
 * templates are resolved (V05) and rules are put in their total order (5.3).
 */
internal class DefinitionCompiler(private val doc: DefinitionDoc, private val ctx: ValidationContext, private val sha: String) {
    private val errors = ArrayList<ValidationError>()
    private val strict = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun errors(): List<ValidationError> = errors.toList()

    private fun err(id: String, ptr: String, msg: String) {
        errors += ValidationError(id, ptr, msg)
    }

    // ---- expressions ----

    fun expr(src: String, scope: TypeScope, ptr: String, expect: Type? = null, bool: Boolean = false): CompiledExpr? {
        return when (val r = FitExpr.compile(src, scope, ctx.limits)) {
            is CompileResult.Err -> {
                val id = when (r.error.code) {
                    "unknown_identifier" -> "V02"
                    "unknown_function", "arity" -> "V03"
                    "limit" -> "V16"
                    else -> "V01"
                }
                err(id, ptr, "'$src': ${r.error.message}")
                null
            }
            is CompileResult.Ok -> {
                val c = r.expr
                if (bool) {
                    FitExpr.requireBoolean(c)?.let {
                        err("V01", ptr, "'$src': ${it.message}")
                        return null
                    }
                }
                if (expect != null && !assignable(c.type, expect, nullable = false)) {
                    err("V01", ptr, "'$src' has type ${c.type}, expected $expect")
                    return null
                }
                c
            }
        }
    }

    /** V04: may a value of type [v] be stored where [target] is declared? */
    fun assignable(v: Type, target: Type, nullable: Boolean): Boolean = when {
        v is Type.AnyT || target is Type.AnyT -> true
        v is Type.NullT -> nullable
        target is Type.Q && v is Type.Q -> v.dim == target.dim || (target.dim == Dim.NUM && v.dim == Dim.INT)
        target is Type.EnumT && v is Type.EnumT -> target.values.containsAll(v.values)
        target is Type.EnumT && v is Type.StrT -> v.literals != null && target.values.containsAll(v.literals)
        target is Type.StrT && (v is Type.StrT || v is Type.EnumT) -> true
        target is Type.ListT && v is Type.ListT -> assignable(v.elem, target.elem, nullable = false)
        else -> v == target
    }

    // ---- declared types and literal values ----

    private fun scalarType(name: String?, values: List<String>, ptr: String): Type? = when (name) {
        "bool" -> Type.BoolT
        "int" -> Type.INT
        "number" -> Type.NUM
        "mass" -> Type.MASS
        "pin" -> Type.PIN
        "duration" -> Type.DURATION
        "days" -> Type.DAYS
        "date" -> Type.DateT
        "enum" -> if (values.isEmpty()) {
            err("S1", ptr, "an enum needs values")
            null
        } else {
            Type.EnumT(values)
        }
        "string" -> Type.STR
        else -> {
            err("S1", ptr, "unknown type $name")
            null
        }
    }

    private fun paramType(d: ParamDecl, ptr: String): Type? = when (d.type) {
        "map" -> scalarType(d.valueType, emptyList(), "$ptr/valueType")?.let { Type.MapT(it) }
        "list" -> scalarType(d.valueType, emptyList(), "$ptr/valueType")?.let { Type.ListT(it) }
        else -> scalarType(d.type, d.values, "$ptr/type")
    }

    /** A JSON literal of a declared type as a FitExpr value (exact decimals from the JSON text). */
    fun literal(type: Type, e: JsonElement, ptr: String): Value? {
        fun bad(): Value? {
            err("S1", ptr, "value $e does not match type $type")
            return null
        }
        return when (type) {
            Type.BoolT -> (e as? JsonPrimitive)?.booleanOrNull?.let { BoolV.of(it) } ?: bad()
            is Type.Q -> {
                val p = e as? JsonPrimitive ?: return bad()
                if (p.isString || p.booleanOrNull != null) return bad()
                val v = BigDecimal(p.content)
                if (type.dim == Dim.INT || type.dim == Dim.DAYS) {
                    if (v.stripTrailingZeros().scale() > 0) return bad()
                }
                QV(type.dim, v)
            }
            is Type.EnumT -> {
                val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return bad()
                if (s !in type.values) bad() else StrV(s)
            }
            is Type.StrT -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { StrV(it) } ?: bad()
            Type.DateT -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { DateV(it) } ?: bad()
            is Type.MapT -> {
                val o = e as? JsonObject ?: return bad()
                val m = sortedMapOf<String, Value>()
                for ((k, v) in o) m[k] = literal(type.value, v, "$ptr/$k") ?: return null
                MapV(m)
            }
            is Type.ListT -> {
                val a = e as? JsonArray ?: return bad()
                ListV(a.mapIndexed { i, x -> literal(type.elem, x, "$ptr/$i") ?: return null })
            }
            else -> bad()
        }
    }

    private fun bound(type: Type, p: JsonPrimitive?, ptr: String): Value? {
        p ?: return null
        val dimType = when (type) {
            is Type.Q -> type
            is Type.MapT -> type.value as? Type.Q
            is Type.ListT -> type.elem as? Type.Q
            else -> null
        } ?: run {
            err("S1", ptr, "min/max only for numeric types")
            return null
        }
        return QV(dimType.dim, BigDecimal(p.content))
    }

    // ---- the compile pass ----

    fun compile(): CompiledDefinition? {
        val params = compileParams()
        val resolver = TemplateResolver(doc)
        val resolved = resolver.resolveAll()
        errors += resolver.errors()
        val decodedItems = resolved.mapValues { (id, r) -> decodeItems(id, r) }
        val itemKeys = (decodedItems.values.flatten().map { it.second.key } +
            doc.habits.mapNotNull { TemplateResolver.str(it.prescription["key"]) }).distinct().sorted()
        val slots = (doc.schedule.slotsPerWeek.values.flatten() + resolved.values.mapNotNull { TemplateResolver.str(it.header["slot"]) })
            .distinct().sorted()

        val metricTypes = LinkedHashMap<String, Type>()
        for ((i, m) in doc.metrics.withIndex()) {
            val t = metricType(m, "/metrics/$i") ?: continue
            metricTypes[m.id] = t
        }
        val paramTypes = params.mapValues { it.value.type }
        val varTypes = LinkedHashMap<String, Type>()
        val varSpecs = ArrayList<Triple<StateVarSpec, Type, String>>()
        for ((i, v) in doc.state.vars.withIndex()) {
            val ptr = "/state/vars/$i"
            val t = scalarType(v.type, v.values, "$ptr/type") ?: continue
            if (varTypes.containsKey(v.name)) err("V04", ptr, "duplicate state var ${v.name}")
            varTypes[v.name] = t
            varSpecs += Triple(v, t, ptr)
        }
        val derivedTypes = LinkedHashMap<String, Pair<Type, List<String>>>()
        for ((i, d) in doc.derived.withIndex()) {
            val t = scalarType(d.type, emptyList(), "/derived/$i/type") ?: continue
            derivedTypes[d.name] = t to d.events
        }
        val inputs = Scopes.Inputs(paramTypes, varTypes, metricTypes, derivedTypes, itemKeys, slots)
        val noDerived = Scopes.Inputs(paramTypes, varTypes, metricTypes, emptyMap(), itemKeys, slots)

        // State vars: init in declaration order; each may read only earlier vars.
        val stateVars = ArrayList<StateVarInfo>()
        val earlier = LinkedHashMap<String, Type>()
        for ((v, t, ptr) in varSpecs) {
            val init = expr(v.init, Scopes.forInit(inputs, earlier), "$ptr/init")
            if (init != null && !assignable(init.type, t, v.nullable)) err("V04", "$ptr/init", "init has type ${init.type}, var ${v.name} is $t")
            stateVars += StateVarInfo(v, t, init, bound(t, v.min, "$ptr/min"), bound(t, v.max, "$ptr/max"))
            earlier[v.name] = t
        }

        val metrics = LinkedHashMap<String, MetricInfo>()
        for ((i, m) in doc.metrics.withIndex()) {
            val t = metricTypes[m.id] ?: continue
            val derived = m.derived?.let { expr(it, Scopes.forTest(inputs), "/metrics/$i/derived", expect = t) }
            metrics[m.id] = MetricInfo(m, t, derived)
        }

        val derived = doc.derived.mapIndexedNotNull { i, d ->
            val t = derivedTypes[d.name]?.first ?: return@mapIndexedNotNull null
            for (e in d.events) if (e !in Scopes.INPUT_EVENTS) err("V02", "/derived/$i/events", "unknown event type $e")
            val c = expr(d.expr, Scopes.forEvents(noDerived, d.events), "/derived/$i/expr", expect = t) ?: return@mapIndexedNotNull null
            DerivedInfo(d, t, c)
        }

        val templates = LinkedHashMap<String, ResolvedTemplate>()
        for ((id, r) in resolved) {
            templates[id] = compileTemplate(id, r, decodedItems.getValue(id), inputs) ?: continue
        }

        val modes = LinkedHashMap<String, ModeInfo>()
        for ((name, m) in doc.structure.modes) {
            val ptr = "/structure/modes/$name"
            if (name !in Modes.ALL) err("V14", ptr, "unknown mode $name (v1 modes: ${Modes.ALL})")
            val sets = m.sets?.let { expr(it, Scopes.forModeTransform(inputs), "$ptr/sets") }
            val lb = m.lightBilateral?.let { expr(it.sets, Scopes.forModeTransform(inputs), "$ptr/lightBilateral/sets") }
            val overrides = m.lightBilateral?.overrides.orEmpty().mapValues { (k, o) ->
                if (doc.exercises.none { it.key == k }) err("V02", "$ptr/lightBilateral/overrides/$k", "unknown exercise $k")
                fieldsOf(o, "$ptr/lightBilateral/overrides/$k", Scopes.forState(inputs))
            }
            modes[name] = ModeInfo(name, m, sets, lb, overrides)
        }

        val rules = doc.rules.mapIndexedNotNull { i, r -> compileRule(r, "/rules/$i", inputs) }
            .sortedWith(compareBy<CompiledRule>({ it.stage.ordinal }, { -it.spec.prio }, { it.id }))
        val ruleIds = sortedSetOf<String>()
        doc.rules.forEachIndexed { i, r -> if (!ruleIds.add(r.id)) err("V02", "/rules/$i/id", "duplicate rule id ${r.id}") }
        val inSession = doc.inSessionRules.mapIndexedNotNull { i, r -> compileInSession(r, "/inSessionRules/$i", inputs, itemKeys, slots) }

        val gates = LinkedHashMap<Int, GateInfo>()
        for ((i, ph) in doc.phases.withIndex()) {
            val obs = ph.exitGate.source == "anyValidObservation"
            if (ph.exitGate.source != "sameValidTestDay" && !obs) err("S1", "/phases/$i/exitGate/source", "unknown gate source ${ph.exitGate.source}")
            val crit = ph.exitGate.all.mapIndexedNotNull { j, s ->
                expr(s, Scopes.forTest(inputs, observations = obs), "/phases/$i/exitGate/all/$j", bool = true)
            }
            gates[ph.id] = GateInfo(ph.id, ph.exitGate.source, crit, ph.exitGate.labels)
        }

        val state = Scopes.forState(inputs)
        val blockWeeks = expr(doc.structure.block.weeks, state, "/structure/block/weeks", expect = Type.INT)
        val weekCounts = expr(doc.structure.advance.weekCounts, Scopes.forWeekClose(inputs), "/structure/advance/weekCounts", bool = true)
        val postponeIf = expr(doc.schedule.testDay.postponeIf, state, "/schedule/testDay/postponeIf", bool = true)
        val hardLimits = LinkedHashMap<String, CompiledExpr>()
        doc.schedule.hard.forEachIndexed { i, h ->
            h.n?.let { n -> expr(n, state, "/schedule/hard/$i/n", expect = Type.INT)?.let { hardLimits[h.id] = it } }
        }
        val habitExprs = LinkedHashMap<String, Pair<CompiledExpr?, CompiledExpr?>>()
        doc.habits.forEachIndexed { i, h ->
            val skip = h.skipIf?.let { expr(it, state, "/habits/$i/skipIf", bool = true) }
            val enabled = h.enabledIf?.let { expr(it, state, "/habits/$i/enabledIf", bool = true) }
            h.measure?.let { expr(it.weekday, state, "/habits/$i/measure/weekday", expect = Type.STR) }
            for (f in listOf("hold_s", "added")) {
                TemplateResolver.str(h.prescription[f])?.let { expr(it, state, "/habits/$i/prescription/$f") }
            }
            habitExprs[h.id] = skip to enabled
        }

        if (blockWeeks == null || weekCounts == null || postponeIf == null) return null
        return CompiledDefinition(
            doc, sha, params, stateVars, metrics, derived, templates, rules, inSession, gates, modes,
            blockWeeks, weekCounts, postponeIf, hardLimits, habitExprs,
        )
    }

    private fun compileParams(): Map<String, ParamInfo> {
        val out = LinkedHashMap<String, ParamInfo>()
        for ((name, d) in doc.params) {
            val ptr = "/params/$name"
            if (!PARAM_NAME.matches(name)) err("S1", ptr, "param name must match ${PARAM_NAME.pattern}")
            val t = paramType(d, ptr) ?: continue
            val v = literal(t, d.default, "$ptr/default") ?: continue
            val lo = bound(t, d.min, "$ptr/min")
            val hi = bound(t, d.max, "$ptr/max")
            val numbers = when (v) {
                is QV -> listOf(v)
                is MapV -> v.entries.values.filterIsInstance<QV>()
                is ListV -> v.items.filterIsInstance<QV>()
                else -> emptyList()
            }
            for (x in numbers) {
                if (lo is QV && x.v < lo.v) err("S1", "$ptr/default", "default ${x.v} below min ${lo.v}")
                if (hi is QV && x.v > hi.v) err("S1", "$ptr/default", "default ${x.v} above max ${hi.v}")
            }
            out[name] = ParamInfo(name, d, t, v)
        }
        return out
    }

    private fun metricType(m: MetricSpec, ptr: String): Type? {
        val base = when (m.type) {
            "mass" -> Type.MASS
            "int" -> Type.INT
            "duration" -> Type.DURATION
            "pin" -> Type.PIN
            "bool" -> Type.BoolT
            "number" -> Type.NUM
            else -> {
                err("S1", "$ptr/type", "unknown metric type ${m.type}")
                return null
            }
        }
        return when (m.sides) {
            "none" -> base
            "LR" -> Type.SideT(base)
            else -> {
                err("S1", "$ptr/sides", "sides must be none or LR")
                null
            }
        }
    }

    private fun decodeItems(id: String, r: TemplateResolver.Resolved): List<Pair<JsonObject, ItemSpec>> =
        r.items.mapIndexedNotNull { i, raw ->
            try {
                raw to strict.decodeFromJsonElement(ItemSpec.serializer(), raw)
            } catch (e: SerializationException) {
                err("S1", "/templates/$id/items/$i", e.message ?: "invalid item")
                null
            } catch (e: IllegalArgumentException) {
                err("S1", "/templates/$id/items/$i", e.message ?: "invalid item")
                null
            }
        }

    private fun compileTemplate(id: String, r: TemplateResolver.Resolved, items: List<Pair<JsonObject, ItemSpec>>, inputs: Scopes.Inputs): ResolvedTemplate? {
        val ptr = "/templates/$id"
        val h = r.header
        val slot = TemplateResolver.str(h["slot"]) ?: run {
            err("S1", "$ptr/slot", "template needs a slot")
            return null
        }
        val kind = TemplateResolver.str(h["kind"]) ?: "gym"
        if (kind !in setOf("gym", "test", "home")) err("S1", "$ptr/kind", "kind must be gym, test or home")
        val name = (h["name"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content } ?: emptyMap()
        val validity = TemplateResolver.str(h["validity"])?.let { expr(it, Scopes.forTest(inputs), "$ptr/validity", bool = true) }
        val state = Scopes.forState(inputs)
        val compiled = items.mapIndexed { i, (_, spec) -> compileItem(spec, "$ptr/items/$i", state) }
        return ResolvedTemplate(
            id = id, slot = slot, name = name, kind = kind, bookedMin = TemplateResolver.int(h["bookedMin"]) ?: 0,
            tags = TemplateResolver.strings(h["tags"]), requiresReadiness = TemplateResolver.strings(h["requiresReadiness"]),
            tag = TemplateResolver.str(h["tag"]), proposal = TemplateResolver.str(h["proposal"]), validity = validity, items = compiled,
        )
    }

    /** Expected types of the numeric item fields; literals take the field's dimension. */
    private val itemFieldTypes: Map<String, Type> = linkedMapOf(
        "sets" to Type.NUM, "reps" to Type.NUM, "hold_s" to Type.DURATION, "ecc_s_target" to Type.DURATION,
        "duration_s" to Type.DURATION, "added" to Type.MASS, "assistPin" to Type.PIN, "fingers" to Type.INT,
        "ladders" to Type.NUM,
    )
    private val itemFieldDims: Map<String, Dim> = linkedMapOf(
        "sets" to Dim.INT, "reps" to Dim.INT, "hold_s" to Dim.DURATION, "ecc_s_target" to Dim.DURATION,
        "duration_s" to Dim.DURATION, "added" to Dim.MASS, "assistPin" to Dim.PIN, "fingers" to Dim.INT, "ladders" to Dim.INT,
    )

    private fun fieldValue(field: String, e: JsonElement, ptr: String, scope: TypeScope): FieldValue? {
        val dim = itemFieldDims.getValue(field)
        return when {
            e is JsonPrimitive && e.isString -> {
                if (field == "reps" && e.content == "ramp") return FieldValue.Keyword("ramp")
                expr(e.content, scope, ptr, expect = itemFieldTypes.getValue(field))?.let { FieldValue.Expr(it) }
            }
            e is JsonPrimitive && e.booleanOrNull == null -> FieldValue.Lit(QV(dim, BigDecimal(e.content)))
            e is JsonArray && e.size == 2 && e.all { it is JsonPrimitive && !it.isString } ->
                FieldValue.Range(QV(dim, BigDecimal((e[0] as JsonPrimitive).content)), QV(dim, BigDecimal((e[1] as JsonPrimitive).content)))
            else -> {
                err("S1", ptr, "expected a number, a [min, max] range or an expression")
                null
            }
        }
    }

    private fun fieldsOf(o: Map<String, JsonElement?>, ptr: String, scope: TypeScope): Map<String, FieldValue> {
        val out = LinkedHashMap<String, FieldValue>()
        for (f in itemFieldTypes.keys) {
            val e = o[f] ?: continue
            fieldValue(f, e, "$ptr/$f", scope)?.let { out[f] = it }
        }
        return out
    }

    private fun compileItem(spec: ItemSpec, ptr: String, scope: TypeScope): CompiledItem {
        val raw = linkedMapOf<String, JsonElement?>(
            "sets" to spec.sets, "reps" to spec.reps, "hold_s" to spec.holdS, "ecc_s_target" to spec.eccSTarget,
            "duration_s" to spec.durationS, "added" to spec.added, "assistPin" to spec.assistPin, "fingers" to spec.fingers,
            "ladders" to spec.ladders,
        )
        val fields = fieldsOf(raw, ptr, scope)
        val exercise = spec.exercise?.let { exerciseChoice(it, "$ptr/exercise", scope) }
        val skipIf = spec.skipIf?.let { expr(it, scope, "$ptr/skipIf", bool = true) }
        val enabledIf = spec.enabledIf?.let { expr(it, scope, "$ptr/enabledIf", bool = true) }
        val position = spec.position?.let { positionValue(it, "$ptr/position", scope) }
        val variants = spec.variants.mapValues { (v, o) -> fieldsOf(o, "$ptr/variants/$v", scope) }
        if (spec.assist != null && spec.assist != "none") err("S1", "$ptr/assist", "assist must be 'none'")
        if (spec.role !in ROLES) err("S1", "$ptr/role", "role must be one of $ROLES")
        return CompiledItem(spec, exercise, fields, skipIf, enabledIf, position, variants)
    }

    private fun exerciseChoice(e: JsonElement, ptr: String, scope: TypeScope): ExerciseChoice? = when {
        e is JsonPrimitive && e.isString -> ExerciseChoice.Fixed(e.content)
        e is JsonObject && e["anyOf"] != null -> ExerciseChoice.AnyOf(TemplateResolver.strings(e["anyOf"]), TemplateResolver.str(e["pick"]) ?: "preference")
        e is JsonObject && e["expr"] != null -> TemplateResolver.str(e["expr"])?.let { expr(it, scope, "$ptr/expr", expect = Type.STR) }?.let { ExerciseChoice.Expr(it) }
        else -> {
            err("S1", ptr, "exercise must be a key, {anyOf, pick} or {expr}")
            null
        }
    }

    private fun positionValue(e: JsonElement, ptr: String, scope: TypeScope): FieldValue? = when {
        e is JsonPrimitive && e.isString -> {
            if (!validPosition(e.content)) err("S1", ptr, "unknown position ${e.content}")
            FieldValue.Lit(StrV(e.content))
        }
        e is JsonObject && e["expr"] != null ->
            TemplateResolver.str(e["expr"])?.let { expr(it, scope, "$ptr/expr", expect = Type.STR) }?.let { c ->
                (c.type as? Type.StrT)?.literals?.filterNot(::validPosition)?.takeIf { it.isNotEmpty() }?.let {
                    err("S1", "$ptr/expr", "unknown positions $it")
                }
                FieldValue.Expr(c)
            }
        else -> {
            err("S1", ptr, "position must be a string or {expr}")
            null
        }
    }

    private fun compileRule(r: RuleSpec, ptr: String, inputs: Scopes.Inputs): CompiledRule? {
        val stage = Stage.of(r.stage) ?: run {
            err("S1", "$ptr/stage", "unknown stage ${r.stage}")
            return null
        }
        if (!RULE_ID.matches(r.id)) err("S1", "$ptr/id", "rule id must match ${RULE_ID.pattern}")
        for (e in r.on) if (e !in Scopes.INPUT_EVENTS && e !in Scopes.DERIVED_EVENTS) err("V02", "$ptr/on", "unknown event type $e")
        val condition = expr(r.condition, Scopes.forEvents(inputs, r.on), "$ptr/if", bool = true)
        val lets = LinkedHashMap<String, Type>()
        val compiledLets = ArrayList<Pair<String, CompiledExpr>>()
        r.let.forEachIndexed { i, l ->
            val c = expr(l.expr, Scopes.forEvents(inputs, r.on, lets), "$ptr/let/$i/expr")
            if (c != null) {
                lets[l.name] = c.type
                compiledLets += l.name to c
            }
        }
        val scope = Scopes.forEvents(inputs, r.on, lets)
        val actions = actions(r.then, "$ptr/then", scope, stage, depth = 1)
        if (condition == null || actions == null) return null
        return CompiledRule(r, stage, condition, compiledLets, actions)
    }

    private fun stateTarget(path: String?, ptr: String): String? {
        if (path == null || !path.startsWith("s.")) {
            err("V04", ptr, "target must be a state var (s.<name>), got $path")
            return null
        }
        val name = path.removePrefix("s.")
        if (doc.state.vars.none { it.name == name }) {
            err("V04", ptr, "unknown state var $name")
            return null
        }
        return name
    }

    private fun varType(name: String): Pair<Type, Boolean>? = doc.state.vars.firstOrNull { it.name == name }?.let { v ->
        val t = when (v.type) {
            "enum" -> Type.EnumT(v.values)
            "bool" -> Type.BoolT
            "int" -> Type.INT
            "number" -> Type.NUM
            "mass" -> Type.MASS
            "pin" -> Type.PIN
            "duration" -> Type.DURATION
            "days" -> Type.DAYS
            "date" -> Type.DateT
            else -> return null
        }
        t to v.nullable
    }

    private fun actions(list: List<JsonObject>, ptr: String, scope: TypeScope, stage: Stage, depth: Int): List<Action>? {
        val out = ArrayList<Action>()
        var ok = true
        list.forEachIndexed { i, o ->
            val a = action(o, "$ptr/$i", scope, stage, depth)
            if (a == null) ok = false else out += a
        }
        return if (ok) out else null
    }

    private fun action(o: JsonObject, ptr: String, scope: TypeScope, stage: Stage, depth: Int): Action? {
        fun keys(vararg allowed: String): Boolean {
            val extra = o.keys - allowed.toSet()
            if (extra.isNotEmpty()) err("S1", ptr, "unknown action keys $extra")
            return extra.isEmpty()
        }
        fun stageOnly(name: String, vararg stages: Stage) {
            if (stage !in stages) err("S1", ptr, "$name is not allowed in stage ${stage.code}")
        }
        return when {
            o.containsKey("set") -> {
                if (!keys("set", "to")) return null
                val v = stateTarget(TemplateResolver.str(o["set"]), "$ptr/set") ?: return null
                val (t, nullable) = varType(v) ?: return null
                val src = TemplateResolver.str(o["to"]) ?: run {
                    err("S1", "$ptr/to", "expected an expression")
                    return null
                }
                val c = expr(src, scope, "$ptr/to") ?: return null
                if (!assignable(c.type, t, nullable)) {
                    err("V04", "$ptr/to", "'$src' has type ${c.type}; s.$v is $t")
                    return null
                }
                Action.Set(v, c)
            }
            o.containsKey("adjust") -> {
                if (!keys("adjust", "by", "round")) return null
                val v = stateTarget(TemplateResolver.str(o["adjust"]), "$ptr/adjust") ?: return null
                val (t, _) = varType(v) ?: return null
                val src = TemplateResolver.str(o["by"]) ?: return null
                val c = expr(src, scope, "$ptr/by") ?: return null
                if (t !is Type.Q || !assignable(c.type, t, false)) {
                    err("V04", "$ptr/by", "cannot adjust s.$v ($t) by ${c.type}")
                    return null
                }
                val round = o["round"] as? JsonObject
                val profile = TemplateResolver.str(round?.get("profile"))
                val mode = TemplateResolver.str(round?.get("mode"))
                if (mode != null && mode !in setOf("down", "up", "nearest")) err("S1", "$ptr/round/mode", "unknown rounding mode $mode")
                if (t.dim == Dim.MASS && profile == null) err("V15", "$ptr/round", "a mass adjustment needs a load profile (roundLoad)")
                Action.Adjust(v, c, profile, mode)
            }
            o.containsKey("lock") -> {
                if (!keys("lock", "kind")) return null
                val vars = TemplateResolver.strings(o["lock"]).mapNotNull { stateTarget(it, "$ptr/lock") }
                val kind = LockKind.of(TemplateResolver.str(o["kind"]) ?: "") ?: run {
                    err("S1", "$ptr/kind", "lock kind must be noIncrease, noDecrease or freeze")
                    return null
                }
                Action.Lock(vars, kind)
            }
            o.containsKey("setMode") -> {
                if (!keys("setMode")) return null
                stageOnly("setMode", Stage.SAFETY, Stage.STRUCTURAL)
                val m = TemplateResolver.str(o["setMode"])
                if (m !in Modes.ALL) {
                    err("V04", "$ptr/setMode", "unknown mode $m")
                    return null
                }
                Action.SetMode(m!!)
            }
            o.containsKey("notify") -> {
                if (!keys("notify", "msg", "once")) return null
                val kind = TemplateResolver.str(o["notify"]) ?: ""
                if (kind !in NOTIFY_KINDS) err("S1", "$ptr/notify", "notify kind must be one of $NOTIFY_KINDS")
                val once = TemplateResolver.str(o["once"]) ?: ""
                if (once.isNotEmpty() && once !in ONCE) err("S1", "$ptr/once", "once must be one of $ONCE")
                Action.Notify(kind, TemplateResolver.str(o["msg"]) ?: "", once)
            }
            o.containsKey("restoreSnapshot") -> {
                if (!keys("restoreSnapshot")) return null
                stageOnly("restoreSnapshot", Stage.SAFETY, Stage.STRUCTURAL)
                val c = expr(TemplateResolver.str(o["restoreSnapshot"]) ?: "", scope, "$ptr/restoreSnapshot", expect = Type.INT) ?: return null
                Action.RestoreSnapshot(c)
            }
            o.containsKey("advancePhase") -> {
                stageOnly("advancePhase", Stage.STRUCTURAL)
                if (keys("advancePhase")) Action.AdvancePhase else null
            }
            o.containsKey("advanceWeek") -> {
                stageOnly("advanceWeek", Stage.STRUCTURAL)
                if (keys("advanceWeek")) Action.AdvanceWeek else null
            }
            o.containsKey("repeatWeek") -> {
                stageOnly("repeatWeek", Stage.STRUCTURAL)
                if (keys("repeatWeek")) Action.RepeatWeek else null
            }
            o.containsKey("startNewBlock") -> {
                if (!keys("startNewBlock")) return null
                stageOnly("startNewBlock", Stage.SAFETY, Stage.STRUCTURAL)
                val spec = o["startNewBlock"] as? JsonObject
                val first = TemplateResolver.str(spec?.get("firstWeekMode"))?.let {
                    expr(it, scope, "$ptr/startNewBlock/firstWeekMode", expect = Scopes.MODE) ?: return null
                }
                Action.StartNewBlock(first)
            }
            o.containsKey("restartBlock") -> {
                stageOnly("restartBlock", Stage.SAFETY, Stage.STRUCTURAL)
                if (keys("restartBlock")) Action.RestartBlock else null
            }
            o.containsKey("scheduleTest") -> {
                stageOnly("scheduleTest", Stage.STRUCTURAL)
                val how = TemplateResolver.str(o["scheduleTest"])
                if (how !in setOf("testWeekday", "fixedDate")) err("S1", "$ptr/scheduleTest", "scheduleTest must be testWeekday or fixedDate")
                if (keys("scheduleTest")) Action.ScheduleTest(how ?: "") else null
            }
            o.containsKey("postponeTest") -> {
                stageOnly("postponeTest", Stage.STRUCTURAL)
                val how = TemplateResolver.str(o["postponeTest"])
                if (how != "firstTestWeekdayAfterCleanSession") err("S1", "$ptr/postponeTest", "unknown postponeTest $how")
                if (keys("postponeTest")) Action.PostponeTest(how ?: "") else null
            }
            o.containsKey("if") -> {
                if (!keys("if", "do", "else")) return null
                if (depth > ctx.maxConditionalNesting) {
                    err("S1", ptr, "conditional actions nested deeper than ${ctx.maxConditionalNesting}")
                    return null
                }
                val c = expr(TemplateResolver.str(o["if"]) ?: "", scope, "$ptr/if", bool = true) ?: return null
                val thenList = (o["do"] as? JsonArray)?.map { it as JsonObject } ?: emptyList()
                val elseList = (o["else"] as? JsonArray)?.map { it as JsonObject } ?: emptyList()
                val t = actions(thenList, "$ptr/do", scope, stage, depth + 1) ?: return null
                val e = actions(elseList, "$ptr/else", scope, stage, depth + 1) ?: return null
                Action.If(c, t, e)
            }
            o.containsKey("suggest") || o.containsKey("capTarget") -> {
                err("S1", ptr, "suggest and capTarget are in-session actions only")
                null
            }
            else -> {
                err("S1", ptr, "unknown action ${o.keys}")
                null
            }
        }
    }

    private fun compileInSession(r: InSessionRuleSpec, ptr: String, inputs: Scopes.Inputs, itemKeys: List<String>, slots: List<String>): CompiledInSessionRule? {
        if (r.on !in setOf("set.logged", "pair.logged")) {
            err("S1", "$ptr/on", "in-session rules listen to set.logged or pair.logged")
            return null
        }
        if (r.item != "*" && r.item !in itemKeys) err("V02", "$ptr/item", "unknown item key ${r.item}")
        for (s in r.slots) if (s !in slots) err("V02", "$ptr/slots", "unknown slot $s")
        val scope = Scopes.forInSession(inputs, r.on)
        val cond = expr(r.condition, scope, "$ptr/if", bool = true) ?: return null
        val out = ArrayList<InSessionAction>()
        r.then.forEachIndexed { i, o ->
            val p = "$ptr/then/$i"
            when {
                o.containsKey("suggest") -> {
                    val s = o["suggest"] as? JsonObject ?: return null
                    val field = TemplateResolver.str(s["field"]) ?: ""
                    val t = SUGGEST_FIELDS[field] ?: run {
                        err("S1", "$p/suggest/field", "unknown field $field")
                        return null
                    }
                    val op = TemplateResolver.str(s["op"]) ?: ""
                    if (op !in setOf("add", "set")) err("S1", "$p/suggest/op", "op must be add or set")
                    val scopeName = TemplateResolver.str(s["scope"]) ?: ""
                    if (scopeName != "remainingSets") err("S1", "$p/suggest/scope", "scope must be remainingSets")
                    val v = expr(TemplateResolver.str(s["value"]) ?: "", scope, "$p/suggest/value", expect = t) ?: return null
                    val round = s["round"] as? JsonObject
                    out += InSessionAction.Suggest(
                        field, scopeName, op, v, TemplateResolver.str(round?.get("profile")), TemplateResolver.str(round?.get("mode")),
                        TemplateResolver.str(o["msg"]) ?: "",
                    )
                }
                o.containsKey("notify") -> out += InSessionAction.Notify(TemplateResolver.str(o["notify"]) ?: "", TemplateResolver.str(o["msg"]) ?: "")
                o.containsKey("capTarget") -> {
                    val c = o["capTarget"] as? JsonObject ?: return null
                    val v = expr(TemplateResolver.str(c["value"]) ?: "", scope, "$p/capTarget/value", expect = Type.INT) ?: return null
                    out += InSessionAction.CapTarget(TemplateResolver.str(c["scope"]) ?: "", TemplateResolver.str(c["field"]) ?: "", v)
                }
                else -> {
                    err("S1", p, "unknown in-session action ${o.keys}")
                    return null
                }
            }
        }
        return CompiledInSessionRule(r, cond, out)
    }

    companion object {
        private val PARAM_NAME = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")
        private val RULE_ID = Regex("^[A-Za-z][A-Za-z0-9_.+-]{0,79}$")
        val ROLES: List<String> = listOf("warmup", "main", "prehab", "test", "attempt")
        val NOTIFY_KINDS: List<String> = listOf("stop", "warn", "info", "celebrate", "prompt")
        val ONCE: List<String> = listOf("perDay", "perBlock", "ever")
        val SUGGEST_FIELDS: Map<String, Type> = linkedMapOf(
            "assistPin" to Type.PIN, "added" to Type.MASS, "reps" to Type.INT, "hold_s" to Type.DURATION, "fingers" to Type.INT,
        )

        fun validPosition(p: String): Boolean = p in setOf("first", "last", "lastMain", "inPlace") || p.startsWith("after:")
    }
}
