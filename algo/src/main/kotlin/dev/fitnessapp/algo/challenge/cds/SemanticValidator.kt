package dev.fitnessapp.algo.challenge.cds

import dev.fitnessapp.algo.challenge.fitexpr.CompiledExpr
import dev.fitnessapp.algo.challenge.fitexpr.Dim
import dev.fitnessapp.algo.challenge.fitexpr.Node
import dev.fitnessapp.algo.challenge.fitexpr.Type
import dev.fitnessapp.algo.selection.catalog.CatalogDomains
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Stage 2 checks V02 (literal arguments), V06-V18 of CHALLENGE_ENGINE 2.6 on a compiled definition, against
 * the catalog and reference data of [ctx]. V01-V05 are raised by the compiler and the template resolver.
 * Findings marked `warning` are listed for review and do not fail validation.
 */
class SemanticValidator(private val doc: DefinitionDoc, private val def: CompiledDefinition, private val ctx: ValidationContext) {
    private val out = ArrayList<ValidationError>()

    private fun err(id: String, ptr: String, msg: String) {
        out += ValidationError(id, ptr, msg)
    }

    private fun warn(id: String, ptr: String, msg: String) {
        out += ValidationError(id, ptr, msg, warning = true)
    }

    private val exerciseKeys: Set<String> = doc.exercises.map { it.key }.toSortedSet()
    private val itemKeys: Set<String> = (def.templates.values.flatMap { t -> t.items.map { it.key } } +
        doc.habits.mapNotNull { TemplateResolver.str(it.prescription["key"]) }).toSortedSet()
    private val slots: Set<String> = (doc.schedule.slotsPerWeek.values.flatten() + def.templates.values.map { it.slot }).toSortedSet()
    private val proposalIds: Set<String> = doc.proposals.map { it.id }.toSortedSet()

    fun check(): List<ValidationError> {
        v02LiteralArguments()
        v06SlotsHaveTemplates()
        v07GatesAreProduced()
        v08Exercises()
        v09Proposals()
        v10VarsAreWritten()
        v11Messages()
        v12Calendar()
        v13Integration()
        v14Frozen()
        v15Units()
        v17Params()
        v18Vocabulary()
        return out.toList()
    }

    /** Every compiled expression with a pointer-like location. */
    private fun allExprs(): List<Pair<String, CompiledExpr>> {
        val list = ArrayList<Pair<String, CompiledExpr>>()
        fun actions(where: String, a: List<Action>) {
            for (x in a) when (x) {
                is Action.Set -> list += where to x.to
                is Action.Adjust -> list += where to x.by
                is Action.RestoreSnapshot -> list += where to x.block
                is Action.StartNewBlock -> x.firstWeekMode?.let { list += where to it }
                is Action.If -> {
                    list += where to x.condition
                    actions(where, x.then)
                    actions(where, x.otherwise)
                }
                else -> Unit
            }
        }
        for (r in def.rules) {
            val w = "rule ${r.id}"
            list += w to r.condition
            r.lets.forEach { list += w to it.second }
            actions(w, r.actions)
        }
        for (r in def.inSessionRules) {
            list += "inSessionRule ${r.id}" to r.condition
            r.actions.forEach { a ->
                when (a) {
                    is InSessionAction.Suggest -> list += "inSessionRule ${r.id}" to a.value
                    is InSessionAction.CapTarget -> list += "inSessionRule ${r.id}" to a.value
                    else -> Unit
                }
            }
        }
        def.derived.forEach { list += "derived ${it.name}" to it.expr }
        def.metrics.values.forEach { m -> m.derived?.let { list += "metric ${m.id}" to it } }
        def.stateVars.forEach { v -> v.init?.let { list += "state var ${v.name}" to it } }
        def.gates.values.forEach { g -> g.criteria.forEach { list += "phase ${g.phaseId} gate" to it } }
        for (t in def.templates.values) {
            t.validity?.let { list += "template ${t.id} validity" to it }
            for (item in t.items) {
                val w = "template ${t.id} item ${item.key}"
                item.skipIf?.let { list += w to it }
                item.enabledIf?.let { list += w to it }
                (item.exercise as? ExerciseChoice.Expr)?.let { list += w to it.expr }
                (item.position as? FieldValue.Expr)?.let { list += w to it.expr }
                (item.fields.values + item.variants.values.flatMap { it.values }).filterIsInstance<FieldValue.Expr>().forEach { list += w to it.expr }
            }
        }
        def.habits.forEach { (id, h) -> listOfNotNull(h.skipIf, h.enabledIf).forEach { list += "habit $id" to it } }
        return list
    }

    private fun v02LiteralArguments() {
        val catalogKeys = doc.exercises.map { it.key }.toSet()
        for ((where, e) in allExprs()) {
            for ((fn, arg) in e.literalCalls) {
                arg ?: continue
                val ok = when (fn) {
                    "valid", "reasonOn", "validInBlock" -> arg in itemKeys
                    "todayHas" -> arg in slots
                    "has" -> arg in ctx.equipmentTypes
                    "userK", "userKQuantile" -> arg in catalogKeys
                    "daysSincePain" -> arg in ctx.bodyRegions
                    else -> true
                }
                if (!ok) err("V02", where, "$fn('$arg'): '$arg' is not a known ${kindOf(fn)}")
            }
        }
    }

    private fun kindOf(fn: String) = when (fn) {
        "valid", "reasonOn", "validInBlock" -> "item key"
        "todayHas" -> "slot"
        "has" -> "equipment type"
        "userK", "userKQuantile" -> "exercise key"
        else -> "body region"
    }

    private fun v06SlotsHaveTemplates() {
        val needed = doc.schedule.slotsPerWeek.values.flatten().toSortedSet() + doc.schedule.testDay.postponedSlotBecomes
        doc.phases.forEachIndexed { i, ph ->
            val maps = listOfNotNull(ph.templates, ph.templatesIfNotApproved?.templates)
            for ((j, m) in maps.withIndex()) {
                val ptr = if (j == 0) "/phases/$i/templates" else "/phases/$i/templatesIfNotApproved/templates"
                for (slot in needed) if (m[slot] == null) err("V06", ptr, "slot $slot has no template in phase ${ph.id}")
                for ((slot, t) in m) {
                    val tpl = def.templates[t]
                    if (tpl == null) err("V06", "$ptr/$slot", "unknown template $t") else if (tpl.slot != slot) err("V06", "$ptr/$slot", "template $t is for slot ${tpl.slot}")
                }
            }
        }
    }

    private fun metricReads(e: CompiledExpr, root: String): Set<String> =
        e.reads.filter { it.startsWith("$root.") }.map { it.removePrefix("$root.").substringBefore('.') }.toSortedSet()

    private fun v07GatesAreProduced() {
        val testSlot = doc.structure.block.testSlot
        doc.phases.forEachIndexed { i, ph ->
            val gate = def.gates[ph.id] ?: return@forEachIndexed
            if (gate.source == "sameValidTestDay") {
                val tpl = ph.templates[testSlot]?.let { def.templates[it] } ?: return@forEachIndexed
                val produced = produced(tpl)
                for (c in gate.criteria) {
                    for (m in metricReads(c, "t")) if (m !in produced) err("V07", "/phases/$i/exitGate", "gate metric $m is not produced by test template ${tpl.id}")
                }
                ph.templatesIfNotApproved?.templates?.get(testSlot)?.let { def.templates[it] }?.let { fb ->
                    val fp = produced(fb)
                    for (c in gate.criteria) for (m in metricReads(c, "t")) if (m !in fp) {
                        warn("V07", "/phases/$i/templatesIfNotApproved", "with ${ph.templatesIfNotApproved.proposal} declined the gate metric $m is never measured (${fb.id})")
                    }
                }
            } else {
                val recorded = def.templates.values.flatMap { t -> t.items.flatMap { listOfNotNull(it.spec.records, it.spec.metric) } }.toSet()
                for (c in gate.criteria) for (m in metricReads(c, "m")) if (m !in recorded) {
                    err("V07", "/phases/$i/exitGate", "observation $m is recorded by no item")
                }
            }
        }
    }

    /** Metrics a test template records, plus derived metrics whose inputs it records (fixed point). */
    private fun produced(t: ResolvedTemplate): Set<String> {
        val out = t.items.mapNotNull { it.spec.metric }.toSortedSet()
        var changed = true
        while (changed) {
            changed = false
            for (m in def.metrics.values) {
                val d = m.derived ?: continue
                if (m.id !in out && metricReads(d, "t").all { it in out }) {
                    out += m.id
                    changed = true
                }
            }
        }
        return out
    }

    private val noEntersSse = setOf("eccentric_reps", "isometric_hold", "assisted_isometric_hold", "timed_hold", "assisted_ordinal_reps")
    private val holdTypes = setOf("isometric_hold", "assisted_isometric_hold", "timed_hold")
    private val localTypes = setOf(
        "weighted_bodyweight_reps", "bodyweight_reps", "assisted_bodyweight_reps", "assisted_ordinal_reps", "eccentric_reps",
        "isometric_hold", "assisted_isometric_hold", "timed_hold", "attempt", "checklist",
    )
    private val coefficients = (listOf(0.0) + CatalogDomains.COEFF_LEVELS).toSet()

    private fun v08Exercises() {
        val seen = sortedSetOf<String>()
        doc.exercises.forEachIndexed { i, x ->
            val ptr = "/exercises/$i"
            if (!seen.add(x.key)) err("V08", "$ptr/key", "duplicate exercise key ${x.key}")
            val ref = x.catalogRef
            if (ref != null) {
                if (x.local) err("V08", ptr, "an entry is either local or a catalog reference")
                val slugs = when {
                    ref is JsonPrimitive && ref.isString -> listOf(ref.content)
                    ref is JsonObject -> {
                        val byParam = TemplateResolver.str(ref["byParam"])
                        val p = byParam?.let { def.params[it] }
                        val map = (ref["map"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content } ?: emptyMap()
                        if (p == null) {
                            err("V08", "$ptr/catalogRef/byParam", "unknown param $byParam")
                        } else {
                            val values = (p.type as? Type.EnumT)?.values
                            if (values == null) err("V08", "$ptr/catalogRef/byParam", "byParam needs an enum param")
                            values?.filter { it !in map }?.forEach { err("V08", "$ptr/catalogRef/map", "no catalog slug for $byParam = $it") }
                        }
                        map.values.toList()
                    }
                    else -> {
                        err("V08", "$ptr/catalogRef", "catalogRef must be a slug or {byParam, map}")
                        emptyList()
                    }
                }
                for (s in slugs) {
                    if (!CatalogDomains.SLUG.matches(s)) err("V18", "$ptr/catalogRef", "catalog slug $s is not snake_case")
                    if (s !in ctx.catalogSlugs) err("V08", "$ptr/catalogRef", "catalog slug $s is not in catalog ${ctx.catalog.catalogVersion}")
                }
                x.overlay?.flagsOffered?.forEach { f -> if (f !in Scopes.SET_FLAGS) err("V18", "$ptr/overlay/flagsOffered", "unknown set flag $f") }
                return@forEachIndexed
            }
            if (!x.local) {
                err("V08", ptr, "an entry needs catalogRef or local: true")
                return@forEachIndexed
            }
            if (x.name?.get("en").isNullOrBlank()) err("V08", "$ptr/name", "a local entry needs an English name")
            if (x.type !in localTypes) err("V08", "$ptr/type", "unknown exercise type ${x.type}")
            if (x.pattern !in ctx.patterns) err("V18", "$ptr/pattern", "unknown pattern ${x.pattern}")
            if (x.laterality !in CatalogDomains.LATERALITY) err("V18", "$ptr/laterality", "unknown laterality ${x.laterality}")
            val grip = x.grip
            if (grip == null || (grip !in CatalogDomains.GRIPS && !(grip.startsWith("param:") && def.params.containsKey(grip.removePrefix("param:"))))) {
                err("V18", "$ptr/grip", "unknown grip $grip")
            }
            if (x.loadMode !in CatalogDomains.LOAD_MODES) err("V18", "$ptr/loadMode", "unknown load mode ${x.loadMode}")
            if (x.implement != null && x.implement !in ctx.equipmentTypes && x.implement !in setOf("floor", "none")) {
                err("V08", "$ptr/implement", "unknown implement ${x.implement}")
            }
            if (x.fBw == null) err("V08", "$ptr/fBw", "a local entry needs fBw")
            var maxCoeff = 0.0
            for ((region, c) in x.regions) {
                val canonical = ctx.taxonomy.canonicalRegion(region)
                when {
                    canonical == null -> err("V08", "$ptr/regions/$region", "unknown region $region")
                    canonical != region -> warn("V08", "$ptr/regions/$region", "alias $region is stored as $canonical")
                }
                if (c !in coefficients) err("V08", "$ptr/regions/$region", "coefficient $c is not one of 0, 0.25, 0.5, 0.75, 1")
                maxCoeff = maxOf(maxCoeff, c)
            }
            if (maxCoeff < CatalogDomains.PRIMARY_COEFF && x.tags.none { it in setOf("prehab", "skill", "hang") }) {
                err("V08", "$ptr/regions", "no region >= 0.75 and not tagged prehab, skill or hang")
            }
            x.requiresStation.forEach { if (it !in ctx.equipmentTypes) err("V08", "$ptr/requiresStation", "unknown station $it") }
            x.flagsOffered.forEach { if (it !in Scopes.SET_FLAGS) err("V18", "$ptr/flagsOffered", "unknown set flag $it") }
            x.clockStopsOn?.let { if (it !in Scopes.SET_FLAGS) err("V18", "$ptr/clockStopsOn", "unknown set flag $it") }
            if (x.logAs !in setOf("reps", "eccentric", "hold", "attempt")) err("V08", "$ptr/logAs", "unknown logAs ${x.logAs}")
            if (x.type in holdTypes && x.holdClass !in CatalogDomains.HOLD_CLASSES) err("V08", "$ptr/holdClass", "a hold needs holdClass (C43)")
            if (x.type in noEntersSse && x.entersSse) err("V08", "$ptr/entersSse", "${x.type} never enters SSE-1/RCM-1 (C8)")
            if (x.entersSse && x.curveClass !in CatalogDomains.CURVE_CLASSES) err("V08", "$ptr/curveClass", "entersSse needs a curve class")
            x.records?.let { if (it !in def.metrics) err("V08", "$ptr/records", "unknown metric $it") }
        }
        for (t in def.templates.values) {
            for (item in t.items) {
                val ptr = "/templates/${t.id} item ${item.key}"
                when (val e = item.exercise) {
                    is ExerciseChoice.Fixed -> if (e.key !in exerciseKeys) err("V02", ptr, "unknown exercise ${e.key}")
                    is ExerciseChoice.AnyOf -> e.keys.filter { it !in exerciseKeys }.forEach { err("V02", ptr, "unknown exercise $it") }
                    is ExerciseChoice.Expr -> (e.expr.type as? Type.StrT)?.literals?.filter { it !in exerciseKeys }?.forEach { err("V02", ptr, "unknown exercise $it") }
                    null -> if (item.spec.metric == null) err("V08", ptr, "an item without a metric needs an exercise")
                }
                item.spec.metric?.let { if (it !in def.metrics) err("V02", "$ptr/metric", "unknown metric $it") }
                item.spec.records?.let { if (it !in def.metrics) err("V02", "$ptr/records", "unknown metric $it") }
                item.spec.stopSetOn.forEach { if (it !in Scopes.SET_FLAGS) err("V18", "$ptr/stopSetOn", "unknown set flag $it") }
            }
        }
        doc.substitutions.forEachIndexed { i, s ->
            if (s.from !in exerciseKeys) err("V08", "/substitutions/$i/from", "unknown exercise ${s.from}")
            if (s.to !in exerciseKeys) err("V08", "/substitutions/$i/to", "unknown exercise ${s.to}")
            if (s.whenReason !in ctx.reasonCodes) err("V18", "/substitutions/$i/whenReason", "unknown reason code ${s.whenReason}")
            if (s.progressionData !in setOf("excluded", "included")) err("S1", "/substitutions/$i/progressionData", "excluded or included")
        }
        (doc.equipment.required + doc.equipment.optional).forEach { e ->
            if (e.id !in ctx.equipmentTypes) err("V08", "/equipment/${e.id}", "unknown equipment type ${e.id}")
        }
    }

    /** Top-level `&&` conjuncts of a condition. */
    private fun conjuncts(n: Node): List<Node> = if (n is Node.Binary && n.op == "&&") conjuncts(n.left) + conjuncts(n.right) else listOf(n)

    private fun hasGuard(e: CompiledExpr, proposal: String, positive: Boolean): Boolean = conjuncts(e.ast).any { c ->
        if (positive) c.path() == "p.approved_$proposal" else (c is Node.Unary && c.op == "!" && c.operand.path() == "p.approved_$proposal")
    }

    private fun v09Proposals() {
        val seen = sortedSetOf<String>()
        doc.proposals.forEachIndexed { i, p ->
            val ptr = "/proposals/$i"
            if (!PROPOSAL_ID.matches(p.id)) err("V09", "$ptr/id", "proposal ids match ${PROPOSAL_ID.pattern}")
            if (!seen.add(p.id)) err("V09", "$ptr/id", "duplicate proposal ${p.id}")
            val sw = def.params["approved_${p.id}"]
            if (sw == null || sw.type != Type.BoolT || sw.decl.proposal != p.id) err("V09", ptr, "needs a bool param approved_${p.id} with proposal ${p.id}")
            for (s in p.supersedes) {
                val r = doc.rules.firstOrNull { it.id == s }
                if (r == null || r.tag != "A" || r.supersededBy != p.id) err("V09", "$ptr/supersedes", "$s must be an A rule with supersededBy ${p.id}")
            }
            val a = p.affects
            a.rules.filter { id -> doc.rules.none { it.id == id } }.forEach { err("V09", "$ptr/affects/rules", "unknown rule $it") }
            a.params.filter { it !in def.params }.forEach { err("V09", "$ptr/affects/params", "unknown param $it") }
            a.derived.filter { id -> doc.derived.none { it.name == id } }.forEach { err("V09", "$ptr/affects/derived", "unknown derived $it") }
            a.templates.filter { it !in doc.templates }.forEach { err("V09", "$ptr/affects/templates", "unknown template $it") }
            a.inSessionRules.filter { id -> doc.inSessionRules.none { it.id == id } }.forEach { err("V09", "$ptr/affects/inSessionRules", "unknown rule $it") }
            a.modes.filter { it !in doc.structure.modes }.forEach { err("V09", "$ptr/affects/modes", "unknown mode $it") }
            a.phases.filter { id -> doc.phases.none { it.id == id } }.forEach { err("V09", "$ptr/affects/phases", "unknown phase $it") }
            a.habits.filter { id -> doc.habits.none { it.id == id } }.forEach { err("V09", "$ptr/affects/habits", "unknown habit $it") }
            a.integration.filter { id -> doc.integration.protectedWindows.none { it.id == id } }.forEach { err("V09", "$ptr/affects/integration", "unknown window $it") }
        }
        fun needsProposal(ptr: String, tag: String?, proposal: String?) {
            if (tag == "P" && (proposal == null || proposal !in proposalIds)) err("V09", ptr, "a P-tagged entry names an existing proposal")
            if (proposal != null && proposal !in proposalIds) err("V09", ptr, "unknown proposal $proposal")
        }
        for (r in def.rules) {
            val ptr = "/rules/${r.id}"
            needsProposal(ptr, r.spec.tag, r.spec.proposal)
            r.spec.uses.filter { it !in proposalIds }.forEach { err("V09", "$ptr/uses", "unknown proposal $it") }
            if (r.spec.tag == "P" && r.spec.proposal != null && !hasGuard(r.condition, r.spec.proposal, positive = true)) {
                err("V09", "$ptr/if", "a P rule needs the top-level conjunct p.approved_${r.spec.proposal}")
            }
            r.spec.supersededBy?.let { pid ->
                val p = doc.proposals.firstOrNull { it.id == pid }
                if (p == null || r.id !in p.supersedes) err("V09", "$ptr/supersededBy", "proposal $pid does not list ${r.id} in supersedes")
                if (r.spec.tag != "A") err("V09", "$ptr/supersededBy", "only A rules are superseded")
                if (!hasGuard(r.condition, pid, positive = false)) err("V09", "$ptr/if", "a superseded A rule needs the top-level conjunct !p.approved_$pid")
            }
        }
        for (r in def.inSessionRules) {
            val ptr = "/inSessionRules/${r.id}"
            needsProposal(ptr, r.spec.tag, r.spec.proposal)
            if (r.spec.tag == "P" && r.spec.proposal != null && !hasGuard(r.condition, r.spec.proposal, positive = true)) {
                err("V09", "$ptr/if", "a P rule needs the top-level conjunct p.approved_${r.spec.proposal}")
            }
        }
        def.params.values.forEach { needsProposal("/params/${it.name}", it.decl.tag, it.decl.proposal) }
        doc.state.vars.forEach { needsProposal("/state/vars/${it.name}", it.tag, it.proposal) }
        def.templates.values.forEach { needsProposal("/templates/${it.id}", it.tag, it.proposal) }
        doc.structure.modes.forEach { (n, m) -> needsProposal("/structure/modes/$n", m.tag, m.proposal) }
        doc.integration.protectedWindows.forEach { needsProposal("/integration/protectedWindows/${it.id}", it.tag, it.proposal) }
        doc.phases.forEach { ph -> ph.templatesIfNotApproved?.let { needsProposal("/phases/${ph.id}", "P", it.proposal) } }
    }

    private fun written(a: List<Action>): Set<String> = a.flatMap { x ->
        when (x) {
            is Action.Set -> listOf(x.variable)
            is Action.Adjust -> listOf(x.variable)
            is Action.RestoreSnapshot -> doc.state.snapshotVars
            is Action.If -> written(x.then) + written(x.otherwise)
            else -> emptyList()
        }
    }.toSet()

    private fun v10VarsAreWritten() {
        val w = def.rules.flatMap { written(it.actions) }.toSet()
        for (v in doc.state.vars) {
            if (!v.static && v.name !in w) err("V10", "/state/vars/${v.name}", "state var ${v.name} is never written (mark it static or write it in a rule)")
            if (v.static && v.name in w) warn("V10", "/state/vars/${v.name}", "static var ${v.name} is written by a rule")
        }
        for (s in doc.state.snapshotVars) if (doc.state.vars.none { it.name == s }) err("V10", "/state/snapshotVars", "unknown var $s")
        if (doc.state.snapshotOn != "block.started") err("S1", "/state/snapshotOn", "v1 snapshots on block.started")
    }

    private fun notifies(a: List<Action>): List<String> = a.flatMap { x ->
        when (x) {
            is Action.Notify -> listOf(x.msg)
            is Action.If -> notifies(x.then) + notifies(x.otherwise)
            else -> emptyList()
        }
    }

    private fun v11Messages() {
        val placeholder = Regex("\\{([A-Za-z_][A-Za-z0-9_.]*)}")
        val known = doc.state.vars.map { it.name }.toSet() + def.params.keys + "value"
        for (r in def.rules) {
            val lets = r.lets.map { it.first }.toSet()
            for (m in notifies(r.actions)) {
                val text = doc.messages[m]
                if (text == null) {
                    err("V11", "/rules/${r.id}", "unknown message $m")
                    continue
                }
                placeholder.findAll(text["en"] ?: "").map { it.groupValues[1] }.filter { it !in known && it !in lets }.forEach {
                    err("V11", "/messages/$m", "placeholder {$it} does not resolve in ${r.id}")
                }
            }
        }
        for (r in def.inSessionRules) {
            for (a in r.actions) {
                val m = when (a) {
                    is InSessionAction.Suggest -> a.msg
                    is InSessionAction.Notify -> a.msg
                    else -> null
                } ?: continue
                if (doc.messages[m] == null) err("V11", "/inSessionRules/${r.id}", "unknown message $m")
            }
        }
        doc.messages.forEach { (k, v) -> if (v["en"].isNullOrBlank()) err("V11", "/messages/$k", "English text is required") }
    }

    private fun v12Calendar() {
        doc.calendar.patterns.forEachIndexed { i, p ->
            try {
                val c = Pattern.compile(p.title)
                if (c.matcher("").find()) err("V12", "/calendar/patterns/$i", "pattern matches the empty string")
            } catch (e: PatternSyntaxException) {
                err("V12", "/calendar/patterns/$i", "pattern does not compile: ${e.description}")
            }
            if (p.slot !in slots) err("V12", "/calendar/patterns/$i/slot", "unknown slot ${p.slot}")
        }
        for (slot in doc.schedule.slotsPerWeek.values.flatten().toSortedSet()) {
            if (doc.calendar.patterns.none { it.slot == slot }) err("V12", "/calendar/patterns", "slot $slot has no title pattern")
        }
        doc.calendar.slotAliases.flatten().filter { it !in slots }.forEach { err("V12", "/calendar/slotAliases", "unknown slot $it") }
    }

    private fun v13Integration() {
        val ig = doc.integration
        ig.ownsMuscles.filter { it !in ctx.groupIds }.forEach { err("V13", "/integration/ownsMuscles", "unknown group $it") }
        ig.protectedWindows.forEach { w ->
            val ptr = "/integration/protectedWindows/${w.id}"
            w.groups.filter { it !in ctx.groupIds }.forEach { err("V13", "$ptr/groups", "unknown group $it") }
            w.regions.filter { ctx.taxonomy.canonicalRegion(it) != it }.forEach { err("V13", "$ptr/regions", "non-canonical region $it") }
            w.sessionTags.filter { it !in ctx.reservedTags }.forEach { err("V13", "$ptr/sessionTags", "unknown session tag $it") }
            w.slots.filter { it !in slots }.forEach { err("V13", "$ptr/slots", "unknown slot $it") }
            if (w.relation !in setOf("before", "after")) err("V13", "$ptr/relation", "before or after")
            if (w.mode !in setOf("hard", "penalty")) err("V13", "$ptr/mode", "hard or penalty")
            if (w.scope != null && w.scope !in setOf("all_gym", "all_training")) err("V13", "$ptr/scope", "unknown scope ${w.scope}")
            w.tissueRMin.keys.filter { it != "elbow" && it !in CatalogDomains.JOINTS }.forEach { err("V13", "$ptr/tissueRMin", "unknown tissue $it") }
        }
        ig.addOnSlots.forEachIndexed { i, a ->
            val ptr = "/integration/addOnSlots/$i"
            if (a.slot !in slots) err("V13", "$ptr/slot", "unknown slot ${a.slot}")
            if (a.position == "end") {
                if (a.bufferMin != ctx.changeoverMin) err("V13", "$ptr/bufferMin", "bufferMin must equal the C49 changeover (${ctx.changeoverMin} min)")
                a.allowPatterns.filter { it !in ctx.patterns }.forEach { err("V13", "$ptr/allowPatterns", "unknown pattern $it") }
                when (val f = a.forbidGroups) {
                    is JsonPrimitive -> if (f.content != "ownsMuscles") err("V13", "$ptr/forbidGroups", "a string forbidGroups must be ownsMuscles")
                    null -> Unit
                    else -> TemplateResolver.strings(f).filter { it !in ctx.groupIds }.forEach { err("V13", "$ptr/forbidGroups", "unknown group $it") }
                }
                a.forbidSessionTags.filter { it !in ctx.reservedTags }.forEach { err("V13", "$ptr/forbidSessionTags", "unknown session tag $it") }
            } else if (a.position != "none") {
                err("V13", "$ptr/position", "end or none")
            }
        }
        ig.modeEffects.keys.filter { it !in Modes.ALL && it != "paused" }.forEach { err("V13", "/integration/modeEffects/$it", "unknown mode $it") }
        def.templates.values.forEach { t -> t.tags.filter { it !in ctx.reservedTags }.forEach { err("V13", "/templates/${t.id}/tags", "unknown session tag $it") } }
        doc.schedule.hard.forEach { h ->
            val ptr = "/schedule/hard/${h.id}"
            when (h.type) {
                "minGapDays" -> {
                    if (h.days == null) err("S1", ptr, "minGapDays needs days")
                    h.appliesToTags.filter { it !in ctx.reservedTags }.forEach { err("V13", "$ptr/appliesToTags", "unknown session tag $it") }
                }
                "restDayBefore" -> {
                    h.slots.filter { it !in slots }.forEach { err("V13", "$ptr/slots", "unknown slot $it") }
                    if (h.scope !in setOf("all_gym", "all_training", "tags")) err("S1", "$ptr/scope", "all_gym, all_training or tags")
                    if (h.runs !in setOf(null, "warn", "forbid")) err("S1", "$ptr/runs", "warn or forbid")
                }
                "maxPerWeek" -> if (h.sessionTag !in ctx.reservedTags || def.hardLimits[h.id] == null) err("V13", ptr, "maxPerWeek needs a known sessionTag and n")
                "slotWeekday" -> {
                    if (h.slot !in slots) err("V13", "$ptr/slot", "unknown slot ${h.slot}")
                    if (h.weekday !in WEEKDAYS) err("S1", "$ptr/weekday", "unknown weekday ${h.weekday}")
                }
                else -> err("S1", "$ptr/type", "unknown hard constraint type ${h.type}")
            }
        }
    }

    private fun v14Frozen() {
        doc.structure.modes.forEach { (name, m) ->
            val ptr = "/structure/modes/$name"
            val expected = if (name == Modes.NORMAL) "active" else "frozen"
            if (m.progression != null && m.progression != expected) err("V14", "$ptr/progression", "C47: $name progression is $expected")
            listOfNotNull(m.inherits, m.inheritsIfDeclined).filter { it !in doc.structure.modes }.forEach { err("V14", ptr, "inherits unknown mode $it") }
        }
        Modes.ALL.filter { it !in doc.structure.modes }.forEach { err("V14", "/structure/modes", "mode $it is not configured") }
        val snapshot = doc.state.snapshotVars.toSet()
        val writers = sortedMapOf<String, MutableList<String>>()
        for (r in def.rules) {
            if (r.stage != Stage.PROGRESSION) continue
            val w = written(r.actions)
            w.forEach { writers.getOrPut(it) { ArrayList() } += r.id }
            if (w.none { it in snapshot }) continue
            val guarded = conjuncts(r.condition.ast).any { c ->
                c is Node.Binary && c.op == "==" && c.left.path() == "mode" && (c.right as? Node.StrLit)?.value == Modes.NORMAL
            }
            if (r.spec.allowInFrozen) {
                warn("V14", "/rules/${r.id}", "allowInFrozen: must only make the prescription easier in frozen modes (review)")
            } else if (!guarded) {
                warn("V14", "/rules/${r.id}", "writes snapshot vars without a mode == 'normal' guard; its writes are suppressed in frozen modes")
            }
        }
        // An A rule and the P rule that supersedes it never run together (5.4): not a double write.
        fun exclusive(a: String, b: String): Boolean {
            val ra = def.rules.first { it.id == a }.spec
            val rb = def.rules.first { it.id == b }.spec
            return (ra.supersededBy != null && ra.supersededBy == rb.proposal) || (rb.supersededBy != null && rb.supersededBy == ra.proposal)
        }
        writers.forEach { (v, ids) ->
            val clash = ids.filterIndexed { i, a -> ids.withIndex().any { (j, b) -> i != j && !exclusive(a, b) } }
            if (clash.size > 1) warn("V14", "/state/vars/$v", "written by several progression rules: $clash")
        }
    }

    private fun v15Units() {
        for ((where, e) in allExprs()) {
            fun walk(n: Node) {
                if (n is Node.Call && n.name in setOf("floor", "ceil", "round")) {
                    val t = n.args.firstOrNull()?.let { e.types.getOrNull(it.id) }
                    if (t == Type.MASS) err("V15", where, "${n.name}() on a mass; masses are rounded only through roundLoad")
                }
                n.children().forEach(::walk)
            }
            walk(e.ast)
        }
    }

    private fun v17Params() {
        for (p in def.params.values) {
            val ptr = "/params/${p.name}"
            if (p.decl.tunable && p.decl.tunedBy.isNullOrBlank()) err("V17", ptr, "a tunable param needs tunedBy")
            if (p.decl.tag == "P" && p.decl.proposal == null) err("V17", ptr, "a P param names its proposal")
            if (p.decl.tag == "A" && p.decl.source.isNullOrBlank()) err("V17", ptr, "an A param needs a source")
            if (p.decl.tag !in TAGS) err("S1", "$ptr/tag", "tag must be one of $TAGS")
        }
    }

    private fun v18Vocabulary() {
        for (m in doc.metrics) {
            val ptr = "/metrics/${m.id}"
            val d = ctx.metricDefs[m.id]
            if (d == null) {
                err("V18", ptr, "metric ${m.id} is not in metric_defs")
                continue
            }
            val ok = when (m.type) {
                "mass" -> d.valueType == "number" && d.unit == "kg"
                "duration" -> d.valueType == "number" && d.unit == "s"
                "pin" -> d.valueType == "number" && d.unit.startsWith("pin")
                "int" -> d.valueType == "int"
                "bool" -> d.valueType == "bool"
                else -> d.valueType == "number"
            }
            if (!ok) err("V18", "$ptr/type", "type ${m.type} does not match metric_defs (${d.valueType}, ${d.unit})")
            if (m.sides != d.sideMode) err("V18", "$ptr/sides", "sides ${m.sides} differ from metric_defs ${d.sideMode}")
        }
        def.metrics.values.filter { it.spec.displayOnly }.forEach { m ->
            for ((where, e) in allExprs()) {
                if (where.startsWith("metric ")) continue
                if (e.reads.any { it == "t.${m.id}" || it == "pt.${m.id}" } && (where.startsWith("phase") || where.startsWith("rule") && !isDataCopy(where, m.id))) {
                    err("V18", where, "display-only metric ${m.id} in a gate or rule condition")
                }
            }
        }
        val pastFlags = Regex("[a-z][A-Za-z]*[A-Z][A-Za-z]*")
        doc.exercises.flatMap { it.flagsOffered }.filter { pastFlags.matches(it) }.forEach { err("V18", "/exercises", "camelCase flag $it; use the DATA_MODEL E6 code") }
    }

    /** `data.test.e1rm` copies the display value into state; that read is not a condition. */
    private fun isDataCopy(where: String, metric: String): Boolean {
        val id = where.removePrefix("rule ")
        val r = def.rules.firstOrNull { it.id == id } ?: return false
        return metric !in metricReads(r.condition, "t") && r.lets.none { metric in metricReads(it.second, "t") }
    }

    companion object {
        private val PROPOSAL_ID = Regex("^P[0-9]{2,3}$")
        private val TAGS = setOf("A", "P", "D", "E")
        val WEEKDAYS: List<String> = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    }
}
