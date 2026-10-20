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
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.DateV
import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.fitexpr.Dim
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.NullV
import dev.fitnessapp.algo.challenge.fitexpr.QV
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.TreeMap
import java.util.UUID

/** The wizard's saved progress (UX 14.8: each step saves; the wizard can be left and resumed). Values are decimal text. */
@Serializable
data class SetupDraft(
    val step: Int = 0,
    val params: Map<String, String> = emptyMap(),
    /** `import`, `known` or `baseline` (UX 14.8 step 4). */
    val start: String = "import",
    val onboard: Map<String, String> = emptyMap(),
    val phase: Int = 1,
    val blockIndex: Int = 1,
    val blockWeek: Int = 1,
    val mode: String = "normal",
    val weekOrdinal: Int = 1,
    val plannedTestDate: String? = null,
    val vars: Map<String, String> = emptyMap(),
    val decisions: Map<String, String> = emptyMap(),
    val cutover: Boolean = false,
)

/** One onboarding value: the `onboard.<name>` key, its label and its inferred dimension. */
data class OnboardField(val key: String, val label: String, val dim: Dim, val side: String?)

data class SetupUi(
    val def: CompiledDefinition? = null,
    val draft: SetupDraft = SetupDraft(),
    val fields: List<OnboardField> = emptyList(),
    /** Init values computed from the onboarding values, shown and editable in import mode. */
    val computed: Map<String, Value> = emptyMap(),
    val review: List<String> = emptyList(),
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
            val draft = saved ?: defaults(def)
            _ui.value = SetupUi(def, draft, fields(def))
            refreshComputed()
        }
    }

    private fun defaults(def: CompiledDefinition): SetupDraft {
        val params = def.doc.params.filter { (k, p) -> p.userEditable && !k.startsWith("approved_") && p.type == "enum" }
            .mapValues { (_, p) -> (p.default as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" }
        return SetupDraft(params = params)
    }

    /**
     * The onboarding fields of the definition's `baseline` step. Their dimension is the state var's of the same name,
     * else read from the name (`bw`, `*Added`, `*Load` mass; `*Reps` count; `*Pin*` machine pin; holds seconds).
     */
    private fun fields(def: CompiledDefinition): List<OnboardField> {
        val step = def.doc.onboarding.steps.firstOrNull { it.id == "baseline" } ?: return emptyList()
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
            OnboardField(path, label(name) + (side?.let { " ($it)" } ?: ""), dim, side)
        }
    }

    private fun label(name: String) = mapOf(
        "bw" to "Bodyweight (kg)", "wpu3rmAdded" to "Weighted 3RM, added kg", "bwMaxReps" to "Max strict pull-ups", "anchorLoad" to "Session C weighted load (anchor, kg)",
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
            if (step == 3) refreshComputed()
            if (step == 8) refreshReview()
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

    /** The import block of the draft (D4); `baseline` starts in a test-pending week with the test this Sunday. */
    private fun importOf(def: CompiledDefinition, d: SetupDraft): ImportBlock? = when (d.start) {
        "import" -> ImportBlock(
            phase = d.phase, blockIndex = d.blockIndex, blockWeek = d.blockWeek, mode = d.mode, weekOrdinal = d.weekOrdinal,
            plannedTestDate = d.plannedTestDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            vars = TreeMap(d.vars.mapNotNull { (k, v) -> parseVar(def, k, v)?.let { k to it } }.toMap()),
        )
        "baseline" -> ImportBlock(
            mode = "test_pending", blockWeek = 1,
            plannedTestDate = LocalDate.now(zone).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)),
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
        val decisions = def.doc.proposals.mapIndexed { i, p ->
            ChallengeEvent(UUID(0, 3L + i), EventTypes.APPROVAL, now.plusMillis(1L + i), LocalDate.now(zone), EventPayload.ApprovalChanged(p.id, d.decisions[p.id] ?: "declined"), facts)
        }
        return EnrollmentFold.fold(def, c.params, listOf(created) + decisions).state
    }

    private fun refreshComputed() {
        val def = _ui.value.def ?: return
        viewModelScope.launch {
            val computed = withContext(Dispatchers.Default) {
                runCatching {
                    val facts = c.challengeFacts.facts(def, null)
                    val d = _ui.value.draft.copy(start = "known")
                    preview(def, d, facts).vars.filterKeys { name -> def.stateVarByName[name]?.spec?.let { !it.static && it.tag != "E" } == true }
                }.getOrDefault(emptyMap())
            }
            _ui.value = _ui.value.copy(computed = computed)
        }
    }

    /** UX 14.8 step 9: the current and next ISO week, materialized from the dry-run state. */
    private fun refreshReview() {
        val def = _ui.value.def ?: return
        viewModelScope.launch {
            val lines = withContext(Dispatchers.Default) {
                runCatching {
                    val facts = c.challengeFacts.facts(def, null)
                    val s = preview(def, _ui.value.draft, facts)
                    val monday = Week.isoWeekStart(LocalDate.now(zone))
                    val out = ArrayList<String>()
                    for (w in 0..1) {
                        val start = monday.plusWeeks(w.toLong())
                        val plan = WeekPlanner.plan(def, c.params, PlanInput(Instant.now(), zone, s, facts, start, projected = w == 1))
                        out += "Week ${Week.isoWeek(start)} · ${ChallengeTexts.mode(s.mode)}"
                        for (p in plan.sessions) {
                            val sp = Materializer.materialize(def, c.params, MaterializeInput(p.slot, p.date, s, facts, c.catalog.doc, changeoverMin = AppContainer.CHANGEOVER_MIN))
                            out += "  ${p.slot} ${ChallengeTexts.date(p.date)} ${ChallengeTexts.time(Week.localTime(p.interval.start, zone))}: " +
                                sp.items.filter { it.status == "planned" && it.role != "warmup" }.joinToString("; ") { item -> itemLine(item) }
                        }
                        plan.droppedSlots.forEach { out += "  $it: no room in this week" }
                        plan.warnings.forEach { out += "  Note: $it" }
                    }
                    out
                }.getOrElse { listOf("Preview failed: ${it.message}") }
            }
            _ui.value = _ui.value.copy(review = lines)
        }
    }

    private fun itemLine(item: dev.fitnessapp.algo.challenge.materialize.PlannedItem): String {
        val r = item.rows.firstOrNull() ?: return item.name["en"] ?: item.key
        val sets = item.rows.count { it.side == "L" || it.side == "both" || it.side == "none" }.coerceAtLeast(1)
        val pin = r.pin
        val added = r.addedKg
        val load = when {
            pin != null -> "pin ${pin.stripTrailingZeros().toPlainString()} lb"
            added != null && added.signum() > 0 -> "+${added.stripTrailingZeros().toPlainString()} kg"
            else -> null
        }
        fun n(x: java.math.BigDecimal) = x.stripTrailingZeros().toPlainString()
        val attempts = r.attempts
        val volume = when {
            // Top-hold rounds (UX 11.7.4): rounds of short attempts, no per-attempt target.
            attempts != null && attempts > 1 && r.targetReps == null && r.targetHoldS == null -> "$sets rounds × $attempts attempts"
            r.targetReps != null && r.targetRepsMax != null && r.targetRepsMax != r.targetReps -> "$sets×${r.targetReps}-${r.targetRepsMax}"
            r.targetReps != null -> "$sets×${r.targetReps}"
            r.targetHoldS != null -> "$sets×" + n(r.targetHoldS!!) + (r.targetHoldMaxS?.let { "-" + n(it) } ?: "") + " s"
            r.durationS != null -> n(r.durationS!!) + " s"
            else -> "$sets sets"
        }
        val ecc = r.targetEccS?.let { " · lower in " + n(it) + " s" } ?: ""
        return "${item.name["en"] ?: item.key} $volume$ecc" + (load?.let { " $it" } ?: "")
    }

    /** UX 14.8 step 10: creates the enrollment, clears the draft and returns its id. */
    fun start(onStarted: (String) -> Unit) {
        val def = _ui.value.def ?: return
        val d = _ui.value.draft
        if (!d.cutover || def.doc.proposals.any { it.id !in d.decisions }) return
        _ui.value = _ui.value.copy(busy = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    c.challenges.create(definitionId, params(def, d), onboard(def, d), importOf(def, d), d.decisions)
                }
            }.onSuccess { f ->
                prefs.edit().remove(definitionId).apply()
                c.fgsEvent("challenge_cutover_confirmed", mapOf("definition_id" to definitionId, "version" to def.version, "sha256" to def.jcsSha256))
                onStarted(f.enrollment.id)
            }.onFailure { _ui.value = _ui.value.copy(busy = false, error = it.message ?: it::class.simpleName) }
        }
    }

    companion object {
        val STEPS = listOf("overview", "rules", "equipment", "starting_point", "gate_method", "schedule", "calendar", "proposals", "review", "cutover")
    }
}
