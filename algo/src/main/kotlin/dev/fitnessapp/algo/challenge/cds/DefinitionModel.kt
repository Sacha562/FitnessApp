package dev.fitnessapp.algo.challenge.cds

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Display text by language; `en` is required (D12), `nl` optional. */
typealias I18n = Map<String, String>

/**
 * CDS v1 (`fitapp.challenge/1`, CHALLENGE_ENGINE 2.3-2.4) as Kotlin types. Decoding is strict (no unknown
 * keys), which is validation stage 1 (D32). Fields whose JSON shape is polymorphic stay [JsonElement] and are
 * interpreted by the compiler; templates and block items stay raw until inheritance is resolved (6.2).
 */
@Serializable
data class DefinitionDoc(
    val schema: String,
    val id: String,
    val version: String,
    val engine: String,
    val title: I18n,
    val summary: I18n,
    val provenance: Provenance,
    val challengeRules: ChallengeRules,
    val proposals: List<ProposalSpec> = emptyList(),
    val params: Map<String, ParamDecl> = emptyMap(),
    val equipment: EquipmentSpec,
    val exercises: List<ExerciseSpec>,
    val substitutions: List<SubstitutionSpec> = emptyList(),
    val metrics: List<MetricSpec> = emptyList(),
    val derived: List<DerivedSpec> = emptyList(),
    val state: StateSpec,
    val structure: StructureSpec,
    val phases: List<PhaseSpec>,
    val blocks: Map<String, List<JsonObject>> = emptyMap(),
    val templates: Map<String, JsonObject>,
    val habits: List<HabitSpec> = emptyList(),
    val schedule: ScheduleSpec,
    val calendar: CalendarSpec,
    val integration: IntegrationSpec,
    val rules: List<RuleSpec> = emptyList(),
    val inSessionRules: List<InSessionRuleSpec> = emptyList(),
    val messages: Map<String, I18n> = emptyMap(),
    val onboarding: OnboardingSpec,
    val importSpec: ImportSpecDoc,
    val migrations: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class Provenance(
    val sources: List<SourceRef>,
    val evidenceNote: String? = null,
    val tagLegend: Map<String, String> = emptyMap(),
)

@Serializable
data class SourceRef(val id: String, val ref: String, val asOf: String, val role: String)

@Serializable
data class ChallengeRules(
    val start: String? = null,
    val implements: List<String> = emptyList(),
    val forbidden: List<String> = emptyList(),
    val finish: String? = null,
    @SerialName("return") val returnTo: String? = null,
    val grip: String? = null,
    val agreedWithOpponentOn: String? = null,
    val attemptChecklist: List<I18n> = emptyList(),
)

@Serializable
data class ProposalSpec(
    val id: String,
    val title: I18n,
    val rationale: I18n,
    val ifDeclined: I18n,
    val recommended: String,
    val supersedes: List<String> = emptyList(),
    val affects: ProposalAffects = ProposalAffects(),
)

@Serializable
data class ProposalAffects(
    val rules: List<String> = emptyList(),
    val params: List<String> = emptyList(),
    val derived: List<String> = emptyList(),
    val templates: List<String> = emptyList(),
    val inSessionRules: List<String> = emptyList(),
    val modes: List<String> = emptyList(),
    val phases: List<Int> = emptyList(),
    val habits: List<String> = emptyList(),
    val integration: List<String> = emptyList(),
)

@Serializable
data class ParamDecl(
    val type: String,
    val default: JsonElement,
    val min: JsonPrimitive? = null,
    val max: JsonPrimitive? = null,
    val values: List<String> = emptyList(),
    val valueType: String? = null,
    val tag: String,
    val proposal: String? = null,
    val source: String? = null,
    val grade: String? = null,
    val tunable: Boolean = false,
    val tunedBy: String? = null,
    val userEditable: Boolean = false,
    val note: String? = null,
)

@Serializable
data class EquipmentSpec(val required: List<EquipmentEntry> = emptyList(), val optional: List<EquipmentEntry> = emptyList())

@Serializable
data class EquipmentEntry(
    val id: String,
    val binding: String? = null,
    val defaults: JsonObject? = null,
    val owner: String? = null,
    val roundingProfile: String? = null,
    val unlocks: List<String> = emptyList(),
    val note: String? = null,
)

@Serializable
data class ExerciseSpec(
    val key: String,
    val catalogRef: JsonElement? = null,
    val overlay: ExerciseOverlay? = null,
    val local: Boolean = false,
    val name: I18n? = null,
    val type: String? = null,
    val pattern: String? = null,
    val laterality: String? = null,
    val grip: String? = null,
    val notGrip: String? = null,
    val implement: String? = null,
    val loadMode: String? = null,
    val fBw: Double? = null,
    val regions: Map<String, Double> = emptyMap(),
    val tags: List<String> = emptyList(),
    val flagsOffered: List<String> = emptyList(),
    val clockStopsOn: String? = null,
    val logAs: String? = null,
    val holdKind: String? = null,
    val holdClass: String? = null,
    val effortChips: Boolean = false,
    val entersSse: Boolean = false,
    val curveClass: String? = null,
    val jointStress: Map<String, Int> = emptyMap(),
    val gripDemand: Int = 0,
    val requiresStation: List<String> = emptyList(),
    val setupS: Int = 0,
    val assistScale: AssistScale? = null,
    val attemptSeconds: AttemptSeconds? = null,
    val angles: List<String> = emptyList(),
    val records: String? = null,
    val checklist: String? = null,
    val note: String? = null,
)

@Serializable
data class ExerciseOverlay(val flagsOffered: List<String> = emptyList(), val tags: List<String> = emptyList())

@Serializable
data class AssistScale(val unit: String, val levels: List<Int>)

@Serializable
data class AttemptSeconds(val hold: Int, val reset: Int)

@Serializable
data class SubstitutionSpec(
    val id: String,
    val from: String,
    val whenReason: String,
    val to: String,
    val fingers: Int? = null,
    val progressionData: String,
    val tag: String,
    val source: String? = null,
)

@Serializable
data class MetricSpec(
    val id: String,
    val type: String,
    val sides: String,
    val better: String,
    val protocol: I18n? = null,
    val protocolCode: String? = null,
    val derived: String? = null,
    val displayOnly: Boolean = false,
    val historyOnly: Boolean = false,
    val effectiveKg: String? = null,
    val evidence: String? = null,
    val tag: String? = null,
    val source: String? = null,
    val note: String? = null,
)

@Serializable
data class DerivedSpec(
    val name: String,
    val type: String,
    val events: List<String>,
    val expr: String,
    val tag: String,
    val uses: List<String> = emptyList(),
    val source: String? = null,
    val note: String? = null,
)

@Serializable
data class StateSpec(val vars: List<StateVarSpec>, val snapshotVars: List<String>, val snapshotOn: String)

@Serializable
data class StateVarSpec(
    val name: String,
    val type: String,
    val init: String,
    val min: JsonPrimitive? = null,
    val max: JsonPrimitive? = null,
    val nullable: Boolean = false,
    val values: List<String> = emptyList(),
    val static: Boolean = false,
    val tag: String,
    val proposal: String? = null,
    val note: String? = null,
)

@Serializable
data class StructureSpec(
    val weekStart: String,
    val block: BlockShape,
    val advance: AdvanceSpec,
    val modes: Map<String, ModeSpec>,
)

@Serializable
data class BlockShape(val weeks: String, val testSlot: String, val testReplacesSlot: String)

@Serializable
data class AdvanceSpec(
    val mode: String,
    val weekCounts: String,
    val onMissed: String,
    val tag: String? = null,
    val source: String? = null,
)

@Serializable
data class ModeSpec(
    val tag: String? = null,
    val proposal: String? = null,
    val source: String? = null,
    val inherits: String? = null,
    val inheritsIfDeclined: String? = null,
    val sets: String? = null,
    val loadFactor: Double? = null,
    val holdSecondsFactor: Double? = null,
    val exemptRoles: List<String> = emptyList(),
    val habitScale: Map<String, HabitScale> = emptyMap(),
    val excludeItemTags: List<String> = emptyList(),
    val disableHabits: List<String> = emptyList(),
    val prehabSetsFactor: Int? = null,
    val progression: String? = null,
    val progressionNote: String? = null,
    val countsAsBlockWeek: Boolean? = null,
    val lightBilateral: LightBilateral? = null,
)

@Serializable
data class HabitScale(val sets: Double, val time: Double)

@Serializable
data class LightBilateral(
    val proposal: String,
    val sets: String,
    val rirMin: Int,
    val overrides: Map<String, JsonObject> = emptyMap(),
    val ifDeclined: String,
)

@Serializable
data class PhaseSpec(
    val id: Int,
    val name: I18n,
    val expectedWeeks: List<Int?> = emptyList(),
    val templates: Map<String, String>,
    val templatesIfNotApproved: TemplatesIfNotApproved? = null,
    val tag: String? = null,
    val exitGate: ExitGate,
    val onExit: String? = null,
)

@Serializable
data class TemplatesIfNotApproved(val proposal: String, val templates: Map<String, String>)

@Serializable
data class ExitGate(val source: String, val all: List<String>, val labels: List<I18n> = emptyList())

@Serializable
data class HabitSpec(
    val id: String,
    val name: I18n,
    val where: String,
    val schedule: HabitSchedule,
    val skipIf: String? = null,
    val enabledIf: String? = null,
    val disabledInModes: List<String> = emptyList(),
    val tag: String,
    val source: String? = null,
    val prescription: JsonObject,
    val measure: HabitMeasure? = null,
)

@Serializable
data class HabitSchedule(
    val rrule: String? = null,
    val timesPerWeek: Int? = null,
    val preferredWeekdays: List<String> = emptyList(),
    val avoid: List<String> = emptyList(),
    val time: String,
)

@Serializable
data class HabitMeasure(
    val metric: String,
    val frequency: String,
    val weekday: String,
    val protocol: I18n? = null,
    val tag: String? = null,
    val uses: List<String> = emptyList(),
)

@Serializable
data class ScheduleSpec(
    val slotsPerWeek: Map<String, List<String>>,
    val slotPriority: List<String>,
    val order: OrderSpec,
    val hard: List<HardConstraintSpec> = emptyList(),
    val preferred: List<PreferredSlot> = emptyList(),
    val testDay: TestDaySpec,
)

@Serializable
data class OrderSpec(
    val sequence: List<List<String>>,
    val strictness: String,
    val tag: String? = null,
    val source: String? = null,
    /** D38: a slot that goes before every other slot of its week while its condition holds. */
    val first: List<FirstSlotSpec> = emptyList(),
)

@Serializable
data class FirstSlotSpec(val slot: String, @SerialName("if") val condition: String, val tag: String? = null, val source: String? = null)

@Serializable
data class HardConstraintSpec(
    val id: String,
    val type: String,
    val days: Int? = null,
    val appliesToTags: List<String> = emptyList(),
    val slots: List<String> = emptyList(),
    val scope: String? = null,
    val runs: String? = null,
    val sessionTag: String? = null,
    val n: String? = null,
    val slot: String? = null,
    val weekday: String? = null,
    val tag: String? = null,
    val source: String? = null,
)

@Serializable
data class PreferredSlot(val slot: String, val weekday: String, val start: String, val durMin: Int)

@Serializable
data class TestDaySpec(
    val weekday: String,
    val postponeIf: String,
    val postponeTo: String,
    val postponedSlotBecomes: String,
    val tag: String? = null,
    val source: String? = null,
)

@Serializable
data class CalendarSpec(
    val patterns: List<CalendarPattern>,
    val slotAliases: List<List<String>> = emptyList(),
    val slotTitles: Map<String, I18n> = emptyMap(),
    val writeBack: WriteBackSpec,
)

@Serializable
data class CalendarPattern(val slot: String, val title: String)

@Serializable
data class WriteBackSpec(
    val mode: String,
    val horizonIsoWeeks: Int,
    val titleTemplate: I18n,
    val testTitleTemplate: I18n,
    val location: String? = null,
    val description: String? = null,
    val marker: String? = null,
    val markerNote: String? = null,
    val humanLine: I18n? = null,
    val titleLocaleDefault: String,
    val titleLocaleNote: String? = null,
    val tag: String? = null,
    val source: String? = null,
)

@Serializable
data class IntegrationSpec(
    val ownsMuscles: List<String>,
    val coverage: JsonObject,
    val protectedWindows: List<ProtectedWindowSpec> = emptyList(),
    val sessionTagRules: JsonObject,
    val addOnSlots: List<AddOnSlotSpec> = emptyList(),
    val generalSessions: GeneralSessionsSpec,
    val modeEffects: Map<String, ModeEffectSpec> = emptyMap(),
)

@Serializable
data class ProtectedWindowSpec(
    val id: String,
    val relation: String,
    val slots: List<String>,
    val hours: Int,
    val mode: String,
    val groups: List<String> = emptyList(),
    val regions: List<String> = emptyList(),
    val sessionTags: List<String> = emptyList(),
    val scope: String? = null,
    val rMin: Double? = null,
    val tissueRMin: Map<String, Double> = emptyMap(),
    val tag: String,
    val proposal: String? = null,
    val source: String? = null,
)

@Serializable
data class AddOnSlotSpec(
    val slot: String,
    val position: String,
    val capacity: String? = null,
    val bufferMin: Int? = null,
    val allowPatterns: List<String> = emptyList(),
    val forbidGroups: JsonElement? = null,
    val forbidSessionTags: List<String> = emptyList(),
    val maxGripDemand: Int? = null,
    val maxSets: Int? = null,
    val minRir: Int? = null,
    val generalWarmup: Boolean? = null,
    val tag: String,
    val source: String? = null,
    val note: String? = null,
)

@Serializable
data class GeneralSessionsSpec(
    val allowedOnGapDaysIfNoTags: List<String> = emptyList(),
    val neverOn: List<String> = emptyList(),
    val forbidGroupsInHardWindows: Boolean = true,
    val tag: String? = null,
    val source: String? = null,
    val note: String? = null,
)

@Serializable
data class ModeEffectSpec(
    val forbidSessionTags: List<String> = emptyList(),
    val forbidExerciseTags: List<String> = emptyList(),
    val jointFlags: Map<String, Int> = emptyMap(),
    val contract: String? = null,
    val tag: String? = null,
    val source: String? = null,
)

@Serializable
data class RuleSpec(
    val id: String,
    val tag: String,
    val proposal: String? = null,
    val uses: List<String> = emptyList(),
    val supersededBy: String? = null,
    val stage: String,
    val prio: Int,
    val on: List<String>,
    @SerialName("if") val condition: String,
    val let: List<LetSpec> = emptyList(),
    val then: List<JsonObject>,
    val allowInFrozen: Boolean = false,
    val allowInFrozenNote: String? = null,
    val explain: I18n,
    val source: String? = null,
)

@Serializable
data class LetSpec(val name: String, val expr: String)

@Serializable
data class InSessionRuleSpec(
    val id: String,
    val tag: String,
    val proposal: String? = null,
    val item: String,
    val slots: List<String> = emptyList(),
    val on: String,
    @SerialName("if") val condition: String,
    val then: List<JsonObject>,
    val source: String? = null,
)

@Serializable
data class OnboardingSpec(val steps: List<OnboardingStep>)

@Serializable
data class OnboardingStep(
    val id: String,
    val required: Boolean,
    val fields: List<String> = emptyList(),
    val shows: List<String> = emptyList(),
    val modes: List<String> = emptyList(),
    val requiresDecisionOnEveryProposal: Boolean = false,
)

@Serializable
data class ImportSpecDoc(val fields: List<ImportField>)

@Serializable
data class ImportField(
    val name: String,
    val type: String,
    val values: List<String> = emptyList(),
    val nullable: Boolean = false,
    val default: JsonElement? = null,
    val note: String? = null,
)

/**
 * One template item after inheritance and block expansion (CHALLENGE_ENGINE 2.4.7). Polymorphic fields keep
 * their JSON form: a number, a `[min, max]` range, a FitExpr string, or an object (`exercise`, `position`).
 */
@Serializable
data class ItemSpec(
    val key: String,
    val exercise: JsonElement? = null,
    val metric: String? = null,
    val record: String? = null,
    val records: String? = null,
    val attempts: Int? = null,
    val role: String,
    val sets: JsonElement? = null,
    val reps: JsonElement? = null,
    val repsRange: List<Int> = emptyList(),
    val rounds: Int? = null,
    val attemptsPerRound: Int? = null,
    @SerialName("hold_s") val holdS: JsonElement? = null,
    @SerialName("ecc_s_target") val eccSTarget: JsonElement? = null,
    @SerialName("duration_s") val durationS: JsonElement? = null,
    val added: JsonElement? = null,
    val assistPin: JsonElement? = null,
    val assist: String? = null,
    val fingers: JsonElement? = null,
    val load: String? = null,
    val perSide: Boolean = false,
    val sideOrder: List<String> = emptyList(),
    val sideRule: String? = null,
    val rirTarget: Int? = null,
    val creditRir: Int? = null,
    @SerialName("rest_s") val restS: Int? = null,
    val stopSetOn: List<String> = emptyList(),
    val calibrationFirstSet: Boolean = false,
    val progression: String? = null,
    val validForGate: Boolean? = null,
    val skipIf: String? = null,
    val enabledIf: String? = null,
    val position: JsonElement? = null,
    val after: String? = null,
    val replacesHabitToday: String? = null,
    val variantVar: String? = null,
    val variants: Map<String, JsonObject> = emptyMap(),
    val variantFixed: String? = null,
    val bootstrap: String? = null,
    val chalk: Boolean = false,
    val toFailure: Boolean? = null,
    val angles: List<String> = emptyList(),
    val note: I18n? = null,
    val protocol: I18n? = null,
    val protocolIfApproved: I18n? = null,
    val protocolProposal: String? = null,
    /** Structured protocol of a test item (D36): always, or only with [protocolProposal] approved. */
    val steps: JsonObject? = null,
    val stepsIfApproved: JsonObject? = null,
    // ladder variant fields (B1 `ladder123`, P17)
    val ladders: JsonElement? = null,
    val rungs: List<Int> = emptyList(),
    @SerialName("rest_s_between_rungs") val restSBetweenRungs: Int? = null,
    @SerialName("rest_s_between_ladders") val restSBetweenLadders: Int? = null,
    val proposal: String? = null,
)
