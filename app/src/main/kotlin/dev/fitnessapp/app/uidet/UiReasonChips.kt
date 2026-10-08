package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.params.ParamView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The `reason_codes.ui_contexts` values (DATA_MODEL 9.1.3). */
enum class ChipContext(val code: String) {
    EASIER("easier"), HARDER("harder"), SKIP("skip"), SESSION_END("session_end"), SUBSTITUTE("substitute"),
}

/** One `fitness.reason_codes` row. */
data class ReasonCode(
    val code: String,
    val labelEn: String,
    val uiContexts: List<String>,
    val isSafety: Boolean,
    val impliesFlag: String?,
    val active: Boolean,
    val sort: Int,
)

/** The reason vocabulary; the bundled reference file and the pulled table have the same rows. */
class ReasonVocabulary(rows: List<ReasonCode>) {
    val rows: List<ReasonCode> = rows.sortedWith(compareBy<ReasonCode> { it.sort }.thenBy { it.code })
    private val byCode = this.rows.associateBy { it.code }

    fun get(code: String): ReasonCode? = byCode[code]

    companion object {
        /** Parses `{table, rows: [...]}` as bundled under `fitness/reference/reason_codes.json`. */
        fun parse(text: String): ReasonVocabulary {
            val rows = Json.parseToJsonElement(text).jsonObject.getValue("rows").jsonArray.map { e ->
                val o = e.jsonObject
                ReasonCode(
                    code = o.getValue("code").jsonPrimitive.content,
                    labelEn = o.getValue("label_en").jsonPrimitive.content,
                    uiContexts = o.getValue("ui_contexts").jsonArray.map { it.jsonPrimitive.content },
                    isSafety = o["is_safety"]?.jsonPrimitive?.booleanOrNull ?: false,
                    impliesFlag = o["implies_flag"]?.jsonPrimitive?.contentOrNull,
                    active = o["active"]?.jsonPrimitive?.booleanOrNull ?: true,
                    sort = o["sort"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }
            return ReasonVocabulary(rows)
        }
    }
}

/** What UI-05 needs to know about the exercise (C74: catalog `flags_offered` or the challenge item). */
data class ExerciseFlags(val flagsOffered: List<String>) {
    val offersTorsoRotation: Boolean get() = "torso_rotation" in flagsOffered
}

data class Chip(val code: String, val label: String, val impliesFlag: String?, val isSafety: Boolean, val position: Int)

data class ChipSet(
    val context: ChipContext,
    val visible: List<Chip>,
    val more: List<Chip>,
    /** A skip needs exactly one tap; every other context is optional. */
    val required: Boolean,
    val preselected: String?,
)

/** What a chosen chip does next (ALGORITHMS_PLANNING 19.2). */
enum class ChipRoute { RECORD, PAIN_SHEET, DO_LATER, OPTIONAL_TEXT }

data class MoveResult(
    /** The new order of session exercise ids; unchanged when [moved] is false. */
    val order: List<String>,
    val moved: Boolean,
    val deferCount: Int,
    /** From `max_defers` defers on, or with nothing left to move behind, Swap is the primary action. */
    val swapPrimary: Boolean,
)

/** One session exercise in its current order. [notDone] = still to do (pending, deferred or active). */
data class SessionExerciseRef(val id: String, val notDone: Boolean)

/**
 * `ui.reasonchips` (UI-05, ALGORITHMS_PLANNING 19.2): the static chip order per context, filtered to the codes
 * whose `ui_contexts` contain the context (C38), routing of the chosen code, and do-later.
 */
object UiReasonChips {
    const val MODULE_ID = "ui.reasonchips"

    private val ORDER: Map<ChipContext, List<String>> = mapOf(
        ChipContext.EASIER to listOf("too_easy", "feeling_good", "other"),
        ChipContext.HARDER to listOf(
            "too_hard", "feeling_bad", "too_tired", "pain", "grip_failure", "torso_rotation",
            "technique_breakdown", "no_time", "occupied", "other",
        ),
        ChipContext.SKIP to listOf("no_time", "occupied", "pain", "feeling_bad", "too_tired", "equipment_missing", "other"),
        ChipContext.SESSION_END to listOf("no_time", "pain", "other"),
        ChipContext.SUBSTITUTE to listOf("occupied", "equipment_missing"),
    )

    fun chipsFor(context: ChipContext, exercise: ExerciseFlags, vocab: ReasonVocabulary, params: ParamView): UiResult<ChipSet> {
        val codes = ORDER.getValue(context).filter { code ->
            val row = vocab.get(code)
            row != null && row.active && context.code in row.uiContexts &&
                (code != "torso_rotation" || exercise.offersTorsoRotation)
        }
        val chips = codes.mapIndexed { i, code ->
            val row = vocab.get(code)!!
            Chip(code, row.labelEn, row.impliesFlag, row.isSafety, i)
        }
        val limit = if (context == ChipContext.HARDER) params.int("ui.reasonchips.max_visible") else chips.size
        val set = ChipSet(
            context = context,
            visible = chips.take(limit),
            more = chips.drop(limit),
            required = context == ChipContext.SKIP,
            preselected = if (context == ChipContext.SESSION_END) "no_time" else null,
        )
        val input = buildJsonObject {
            put("context", context.code)
            put("flags_offered", buildJsonArray { exercise.flagsOffered.sorted().forEach { add(JsonPrimitive(it)) } })
            put("codes", buildJsonArray { vocab.rows.filter { it.active }.forEach { add(JsonPrimitive(it.code)) } })
            put("max_visible", JsonNumbers.int(limit.toLong()))
        }
        return UiResult.of(set, MODULE_ID, input)
    }

    /** The follow-up of a chosen code: pain -> Pain sheet (safety, C33); occupied on a skip -> do later. */
    fun route(code: String, context: ChipContext): ChipRoute = when {
        code == "pain" -> ChipRoute.PAIN_SHEET
        code == "occupied" && context == ChipContext.SKIP -> ChipRoute.DO_LATER
        code == "other" -> ChipRoute.OPTIONAL_TEXT
        else -> ChipRoute.RECORD
    }

    /** Whether do-later is possible: some not-done exercise follows [exerciseId]. */
    fun canDoLater(order: List<SessionExerciseRef>, exerciseId: String): Boolean {
        val i = order.indexOfFirst { it.id == exerciseId }
        return i >= 0 && order.drop(i + 1).any { it.notDone }
    }

    /** Moves [exerciseId] after the next not-done exercise and raises its defer count. */
    fun doLater(order: List<SessionExerciseRef>, exerciseId: String, deferCount: Int, params: ParamView): MoveResult {
        val maxDefers = params.int("ui.reasonchips.max_defers")
        val i = order.indexOfFirst { it.id == exerciseId }
        require(i >= 0) { "exercise $exerciseId not in the session" }
        val next = (i + 1 until order.size).firstOrNull { order[it].notDone }
            ?: return MoveResult(order.map { it.id }, false, deferCount, true)
        val ids = order.map { it.id }.toMutableList()
        val moving = ids.removeAt(i)
        ids.add(next, moving) // `next` shifted left by one after the removal, so this lands right after it
        val count = deferCount + 1
        return MoveResult(ids, true, count, count >= maxDefers || !canDoLater(reorder(order, ids), exerciseId))
    }

    private fun reorder(refs: List<SessionExerciseRef>, ids: List<String>): List<SessionExerciseRef> {
        val byId = refs.associateBy { it.id }
        return ids.map { byId.getValue(it) }
    }

    /** Input JSON of the chip list for telemetry joins (`deviation_shown.codes_shown`). */
    fun codesShown(set: ChipSet): JsonArray = buildJsonArray { (set.visible + set.more).forEach { add(JsonPrimitive(it.code)) } }
}
