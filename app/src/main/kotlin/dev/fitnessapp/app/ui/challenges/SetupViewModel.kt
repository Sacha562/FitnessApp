package dev.fitnessapp.app.ui.challenges

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.enrollment.ChallengeEvent
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentFold
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.enrollment.ImportBlock
import dev.fitnessapp.algo.challenge.enrollment.PastTest
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.DateV
import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.fitexpr.Dim
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.NullV
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.StrV
import dev.fitnessapp.algo.challenge.fitexpr.Type
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.materialize.MaterializeInput
import dev.fitnessapp.algo.challenge.materialize.Materializer
import dev.fitnessapp.algo.challenge.plan.PlanInput
import dev.fitnessapp.algo.challenge.plan.WeekPlanner
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap
import java.util.UUID

/** The wizard's saved progress (UX 14.8: each step saves; the wizard can be left and resumed). Values are decimal text. */
@Serializable
data class SetupDraft(
    /** [SetupViewModel.LAYOUT] of the wizard that saved it; a draft of the old ten-step wizard (0) is dropped. */
    val layout: Int = 0,
    val step: Int = 0,
    val params: Map<String, String> = emptyMap(),
    /** `baseline`, `known` or `import` ("Enter my numbers" with "I'm mid-program", UX 14.8 step 2). */
    val start: String = "baseline",
    val onboard: Map<String, String> = emptyMap(),
    val phase: Int = 1,
    val blockIndex: Int = 1,
    val blockWeek: Int = 1,
    val mode: String = "normal",
    val weekOrdinal: Int = 1,
    val plannedTestDate: String? = null,
    val vars: Map<String, String> = emptyMap(),
    /** Proposal decisions changed under Customize; the others follow the definition's recommendation (D35). */
    val decisions: Map<String, String> = emptyMap(),
    /** Valid sessions per item key already done in the current block (`importSpec.blockItemCounts`, D40). */
    val blockItemCounts: Map<String, Int> = emptyMap(),
    /** Tests done before the app took over (`importSpec.pastTests`, C59, D40). */
    val pastTests: List<DraftTest> = emptyList(),
)

/**
 * A test from before the cutover. [metrics] maps a metric id, or `id.L` / `id.R` for a sided metric, to decimal text
 * (`true` / `false` for a yes/no metric); [notComparable] metrics were measured another way and never feed comparisons.
 */
@Serializable
data class DraftTest(
    val date: String,
    val validForGate: Boolean,
    val metrics: Map<String, String> = emptyMap(),
    val notComparable: List<String> = emptyList(),
    val protocolCodes: Map<String, String> = emptyMap(),
    val note: String? = null,
)

/** One onboarding value: the `onboard.<name>` key, its label, its inferred dimension and whether a state var's init reads it. */
data class OnboardField(val key: String, val label: String, val dim: Dim, val side: String?, val required: Boolean)

/** One exercise row of a reviewed session: its name and "5×3 · pin 40 lb". */
data class ReviewExercise(val name: String, val detail: String)

/** One planned session of the setup review: "Session A", its theme, "Fri 23 Oct · 19:45" and its exercises. */
data class ReviewSession(val name: String, val theme: String?, val whenText: String, val exercises: List<ReviewExercise>)

/** One week of the setup review (UX 14.8 step 3): its sessions and the planner's notes. */
data class ReviewWeek(val title: String, val sessions: List<ReviewSession>, val notes: List<String> = emptyList())

data class SetupUi(
    val def: CompiledDefinition? = null,
    val draft: SetupDraft = SetupDraft(),
    val fields: List<OnboardField> = emptyList(),
    /** Init values computed from the onboarding values, shown and editable in import mode. */
    val computed: Map<String, Value> = emptyMap(),
    val review: List<ReviewWeek> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * The challenge setup wizard (UX 14.8, CHALLENGE_ENGINE 10.2). The draft is kept on the device until Start, which
 * writes `enrollment.created` and one `rules.approval_changed` per proposal; the import is checked against the
 * definition's `importSpec` by the fold itself (unknown vars or modes fail).
 */
class SetupViewModel(private val c: AppContainer, private val definitionId: String) : ViewModel() {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val prefs = c.appContext.getSharedPreferences("challenge_setup", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _ui = MutableStateFlow(SetupUi())
    val ui: StateFlow<SetupUi> = _ui
    private var stepStartedMs = System.currentTimeMillis()

    init {
        viewModelScope.launch {
            val def = withContext(Dispatchers.Default) { c.challenges.definition(definitionId) }
            val saved = prefs.getString(definitionId, null)?.let { runCatching { json.decodeFromString(SetupDraft.serializer(), it) }.getOrNull() }
            val draft = saved?.takeIf { it.layout == LAYOUT } ?: defaults(def)
            _ui.value = SetupUi(def, draft, fields(def))
            refreshComputed()
            // A draft resumed on the last step needs its review; go() computes it only when the step changes.
            if (draft.step == STEPS.lastIndex) refreshReview()
        }
    }

    private fun defaults(def: CompiledDefinition): SetupDraft {
        val params = def.doc.params.filter { (k, p) -> p.userEditable && !k.startsWith("approved_") && p.type == "enum" }
            .mapValues { (_, p) -> (p.default as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" }
        return SetupDraft(layout = LAYOUT, params = params)
    }

    /** Every proposal's decision: the one changed under Customize, else the definition's recommendation (D35). */
    fun decisions(def: CompiledDefinition, d: SetupDraft): Map<String, String> =
        def.doc.proposals.associate { p -> p.id to (d.decisions[p.id] ?: if (p.recommended == "approve") "approved" else "declined") }

    /** Step 2 can be left: a test week needs nothing, entered numbers need every required field. */
    fun startComplete(ui: SetupUi): Boolean = positionValid(ui) &&
        (ui.draft.start == "baseline" || ui.fields.filter { it.required }.all { ui.draft.onboard[it.key]?.toBigDecimalOrNull() != null })

    /**
     * "I'm mid-program": the phase exists, the block is 1 or more and the week lies within the engine's longest block
     * (`challenge.enrollment.max_block_weeks`; the server allows 1-10), so a typo cannot create an unsyncable enrollment.
     */
    fun positionValid(ui: SetupUi): Boolean {
        val d = ui.draft
        if (d.start != "import") return true
        val phases = ui.def?.doc?.phases?.size ?: return true
        return d.phase in 1..phases && d.blockIndex >= 1 && d.blockWeek in 1..c.params.int("challenge.enrollment.max_block_weeks")
    }

    /**
     * The onboarding fields of the definition's `baseline` step. Their dimension is the state var's of the same name,
     * else read from the name (`bw`, `*Added`, `*Load` mass; `*Reps` count; `*Pin*` machine pin; holds seconds).
     * A field is required when a state var's `init` reads it.
     */
    private fun fields(def: CompiledDefinition): List<OnboardField> {
        val step = def.doc.onboarding.steps.firstOrNull { it.id == "baseline" } ?: return emptyList()
        val inits = def.doc.state.vars.map { it.init }
        return step.fields.filter { it.startsWith("onboard.") }.map { f ->
            val path = f.removePrefix("onboard.")
            val name = path.substringBefore('.')
            val side = path.substringAfter('.', "").ifEmpty { null }
            val dim = (def.stateVarByName[name]?.type as? Type.Q)?.dim ?: when {
                name == "bw" || name.endsWith("Added") || name.endsWith("Load") -> Dim.MASS
                name.endsWith("Reps") -> Dim.INT
                name.contains("Pin") -> Dim.PIN
                else -> Dim.DURATION
            }
            val read = Regex("""\bonboard\.$name\b""")
            OnboardField(path, label(name) + (side?.let { " ($it)" } ?: ""), dim, side, inits.any { read.containsMatchIn(it) })
        }
    }

    private fun label(name: String) = mapOf(
        "bw" to "Bodyweight (kg)", "wpu3rmAdded" to "Weighted 3RM, added kg", "bwMaxReps" to "Max strict pull-ups", "anchorLoad" to "Session C weighted load (kg)",
        "pinA" to "Assisted pin, session A (lb)", "pinC" to "Assisted pin, session C (lb)", "pinHold90" to "Assisted pin, 90-degree hold (lb)",
        "leastAssistPin" to "Least assistance pin (lb)", "hang" to "One-arm hang (s)", "topHold" to "One-arm top hold (s)",
    )[name] ?: name

    fun update(f: (SetupDraft) -> SetupDraft) {
        val d = f(_ui.value.draft)
        _ui.value = _ui.value.copy(draft = d, error = null)
        prefs.edit().putString(definitionId, json.encodeToString(SetupDraft.serializer(), d)).apply()
    }

    fun go(step: Int) {
        val from = _ui.value.draft.step
        val ms = System.currentTimeMillis() - stepStartedMs
        stepStartedMs = System.currentTimeMillis()
        update { it.copy(step = step) }
        viewModelScope.launch {
            c.fgsEvent("challenge_setup_step", mapOf("step" to from + 1, "step_key" to STEPS[from], "duration_ms" to ms, "action" to if (step > from) "next" else "back"))
            if (step == 1) refreshComputed()
            if (step == 2) refreshReview()
        }
    }

    private fun onboard(def: CompiledDefinition, d: SetupDraft): TreeMap<String, Value> {
        val out = TreeMap<String, Value>()
        val fields = fields(def)
        for ((name, group) in fields.groupBy { it.key.substringBefore('.') }) {
            if (group.all { it.side == null }) {
                val f = group.single()
                d.onboard[f.key]?.toBigDecimalOrNull()?.let { out[name] = QV(f.dim, Dec.norm(it)) }
            } else {
                val l = group.firstOrNull { it.side == "L" }?.let { f -> d.onboard[f.key]?.toBigDecimalOrNull()?.let { QV(f.dim, Dec.norm(it)) } }
                val r = group.firstOrNull { it.side == "R" }?.let { f -> d.onboard[f.key]?.toBigDecimalOrNull()?.let { QV(f.dim, Dec.norm(it)) } }
                if (l != null || r != null) out[name] = dev.fitnessapp.algo.challenge.fitexpr.SideV(l, r)
            }
        }
        return out
    }

    private fun parseVar(def: CompiledDefinition, name: String, text: String): Value? {
        val t = def.stateVarByName[name]?.type ?: return null
        return when (t) {
            is Type.Q -> text.toBigDecimalOrNull()?.let { QV(t.dim, Dec.norm(it)) }
            Type.BoolT -> text.toBooleanStrictOrNull()?.let(BoolV::of)
            is Type.EnumT -> text.takeIf { it in t.values }?.let(::StrV)
            Type.DateT -> runCatching { DateV(LocalDate.parse(text)) }.getOrNull() ?: NullV
            else -> null
        }
    }

    /** The item keys whose block counts a rule reads (`validInBlock('neg')`), with the item's exercise name. */
    fun blockCountItems(def: CompiledDefinition): List<Pair<String, String>> {
        // Anywhere in a rule (its condition, a `let`, an action), so the whole rule is searched.
        val keys = def.doc.rules.flatMap { r ->
            BLOCK_COUNT.findAll(Json.encodeToString(dev.fitnessapp.algo.challenge.cds.RuleSpec.serializer(), r)).map { it.groupValues[1] }.toList()
        }.distinct().sorted()
        return keys.map { key -> key to (itemName(def, key) ?: key) }
    }

    private fun itemName(def: CompiledDefinition, key: String): String? {
        val exercise = def.doc.templates.values.asSequence()
            .flatMap { t -> (t["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().asSequence() }
            .mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            .firstOrNull { (it["key"] as? kotlinx.serialization.json.JsonPrimitive)?.content == key }
            ?.let { (it["exercise"] as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return null
        return def.doc.exercises.firstOrNull { it.key == exercise }?.name?.get("en")
    }

    /** The import block of the draft (D4); `baseline` starts in a test-pending week with the test due today (D38). */
    private fun importOf(def: CompiledDefinition, d: SetupDraft): ImportBlock? = when (d.start) {
        "import" -> ImportBlock(
            phase = d.phase, blockIndex = d.blockIndex, blockWeek = d.blockWeek, mode = d.mode, weekOrdinal = d.weekOrdinal,
            plannedTestDate = d.plannedTestDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            // The protocol's start date is only meaningful in the elbow protocol (D40).
            vars = TreeMap(d.vars.filterKeys { it != INJURY_START || d.mode == "injury" }.mapNotNull { (k, v) -> parseVar(def, k, v)?.let { k to it } }.toMap()),
            pastTests = d.pastTests.mapNotNull { SetupImport.pastTest(def, it) },
            blockItemCounts = TreeMap(d.blockItemCounts.filterValues { it > 0 }),
        )
        "baseline" -> ImportBlock(
            mode = "test_pending", blockWeek = 1,
            // D38: the test can be any day and goes first while there are no loads, so it is due from today.
            plannedTestDate = LocalDate.now(zone),
        )
        else -> null
    }

    private fun params(def: CompiledDefinition, d: SetupDraft): TreeMap<String, Value> =
        TreeMap(d.params.filterValues { it.isNotEmpty() }.mapValues { StrV(it.value) as Value }.filterKeys { it in def.params })

    /** A dry run of the creation (nothing stored) for the computed values and the review. */
    private suspend fun preview(def: CompiledDefinition, d: SetupDraft, facts: EngineFacts): EnrollmentState {
        val now = Instant.now()
        val created = ChallengeEvent(
            UUID(0, 1), EventTypes.CREATED, now, LocalDate.now(zone),
            EventPayload.Created(UUID(0, 2), params(def, d), onboard(def, d), importOf(def, d), def.version, def.jcsSha256), facts,
        )
        val all = decisions(def, d)
        val decisions = def.doc.proposals.mapIndexed { i, p ->
            ChallengeEvent(UUID(0, 3L + i), EventTypes.APPROVAL, now.plusMillis(1L + i), LocalDate.now(zone), EventPayload.ApprovalChanged(p.id, all.getValue(p.id)), facts)
        }
        // As in ChallengeRepository.create: today's ticks fold in too, so an imported protocol that is already over
        // shows the week that follows it (D40).
        val ticks = EnrollmentFold.catchUp(def, c.params, EnrollmentFold.create(def, c.params, created).state, now, zone, null).first
        return EnrollmentFold.fold(def, c.params, listOf(created) + decisions + ticks).state
    }

    private fun refreshComputed() {
        val def = _ui.value.def ?: return
        viewModelScope.launch {
            val computed = withContext(Dispatchers.Default) {
                runCatching {
                    val d = _ui.value.draft.copy(start = "known")
                    val facts = c.challengeFacts.facts(def, null, setupParams = params(def, d))
                    // Only values with a plain name (P15): engine bookkeeping such as streaks and anchorFraction stays hidden.
                    preview(def, d, facts).vars.filterKeys { name ->
                        def.stateVarByName[name]?.spec?.let { !it.static && it.tag != "E" } == true && ChallengeTexts.varLabel(name) != name
                    }
                }.getOrDefault(emptyMap())
            }
            _ui.value = _ui.value.copy(computed = computed)
        }
    }

    /** UX 14.8 step 3: the current and next ISO week, materialized from the dry-run state. */
    private fun refreshReview() {
        val def = _ui.value.def ?: return
        viewModelScope.launch {
            val lines = withContext(Dispatchers.Default) {
                runCatching {
                    val facts = c.challengeFacts.facts(def, null, setupParams = params(def, _ui.value.draft))
                    val s = preview(def, _ui.value.draft, facts)
                    val monday = Week.isoWeekStart(LocalDate.now(zone))
                    val out = ArrayList<ReviewWeek>()
                    for (w in 0..1) {
                        val start = monday.plusWeeks(w.toLong())
                        val plan = WeekPlanner.plan(def, c.params, PlanInput(Instant.now(), zone, s, facts, start, projected = w == 1))
                        val sessions = plan.sessions.map { p ->
                            val sp = Materializer.materialize(def, c.params, MaterializeInput(p.slot, p.date, s, facts, c.catalog.doc, changeoverMin = AppContainer.CHANGEOVER_MIN))
                            val planned = sp.items.filter { it.status == "planned" }
                            val main = planned.filter { it.role != "warmup" && it.role != "prehab" }.map { item ->
                                // A test item reads as what it measures ("Max strict pull-ups"), without a set count.
                                val metric = item.metric
                                if (item.role == "test" && metric != null) {
                                    ReviewExercise(if (metric == "bw") "Weigh-in" else ChallengeTexts.metricLabel(metric), if (item.rows.any { it.side == "L" || it.side == "R" }) "each arm" else "")
                                } else {
                                    ReviewExercise(item.name["en"] ?: item.key, ChallengeLines.detailOf(item))
                                }
                            }
                            val name = def.templates.values.firstOrNull { it.slot == p.slot }?.name?.get("en")
                            ReviewSession(
                                slotName(def, p.slot), name?.substringAfter(':', "")?.trim()?.ifEmpty { null }?.replaceFirstChar { it.uppercase() },
                                "${ChallengeTexts.date(p.date)} · ${ChallengeTexts.time(Week.localTime(p.interval.start, zone))}",
                                listOfNotNull(ReviewExercise("Warm-up", "").takeIf { planned.any { it.role == "warmup" } }) + main +
                                    listOfNotNull(ReviewExercise("Prehab", "").takeIf { planned.any { it.role == "prehab" } }),
                            )
                        }
                        val notes = plan.droppedSlots.map { "${slotName(def, it)}: no room this week" } + plan.warnings
                        out += ReviewWeek((if (w == 0) "This week" else "Next week") + " · " + ChallengeTexts.mode(s.mode), sessions, notes)
                    }
                    out
                }.getOrElse { listOf(ReviewWeek("Preview failed", emptyList(), listOf(it.message ?: it::class.simpleName.orEmpty()))) }
            }
            _ui.value = _ui.value.copy(review = lines)
        }
    }

    /** A slot's session name: its template's name up to the colon ("Session A"). */
    private fun slotName(def: CompiledDefinition, slot: String): String =
        def.templates.values.firstOrNull { it.slot == slot }?.name?.get("en")?.substringBefore(':') ?: if (slot == "TEST") "Test day" else "Session $slot"


    /** UX 14.8 step 3: Start is the cutover confirmation (D4); creates the enrollment, clears the draft and returns its id. */
    fun start(onStarted: (String) -> Unit) {
        val def = _ui.value.def ?: return
        val d = _ui.value.draft
        if (!startComplete(_ui.value)) return
        _ui.value = _ui.value.copy(busy = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    c.challenges.create(definitionId, params(def, d), onboard(def, d), importOf(def, d), decisions(def, d))
                }
            }.onSuccess { f ->
                prefs.edit().remove(definitionId).apply()
                c.fgsEvent("challenge_cutover_confirmed", mapOf("definition_id" to definitionId, "version" to def.version, "sha256" to def.jcsSha256))
                onStarted(f.enrollment.id)
            }.onFailure { _ui.value = _ui.value.copy(busy = false, error = it.message ?: it::class.simpleName) }
        }
    }

    companion object {
        val STEPS = listOf("overview", "starting_point", "review")

        /** The wizard layout saved drafts belong to (D35: three steps). */
        const val LAYOUT = 2

        /** The state var that holds the elbow protocol's start (`injury` mode, D40). */
        const val INJURY_START = "injuryStart"

        private val BLOCK_COUNT = Regex("""validInBlock\('([A-Za-z0-9_]+)'\)""")
    }
}
