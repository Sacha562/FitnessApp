package dev.fitnessapp.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.fitnessapp.algo.accounting.setcredit.Coverage
import dev.fitnessapp.algo.accounting.setcredit.SetCredit
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.materialize.SessionPlan
import dev.fitnessapp.algo.common.Groups
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.planning.PlanningFacts
import dev.fitnessapp.app.ui.challenges.ChallengeTexts
import dev.fitnessapp.app.ui.common.ChipButton
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.live.PAIN_KINDS
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.app.ui.you.groupLabel
import dev.fitnessapp.app.uidet.Prompt
import dev.fitnessapp.app.uidet.PromptPlan
import dev.fitnessapp.data.repo.PromptNotAnswered
import dev.fitnessapp.data.repo.PromptShown
import java.util.TreeMap

/** The answers of the readiness sheet (UX 16.3); null or absent = not answered. */
data class PreAnswers(
    val readiness: String? = null,
    val elbow: Int? = null,
    val symptoms: List<String> = emptyList(),
    val soreness: Map<String, String> = emptyMap(),
)

/** A sheet waiting to be answered: the prompt plan, whether the template requires the elbow check, when it opened. */
data class PreSheet(val plan: PromptPlan, val elbowRequired: Boolean, val openedAtMs: Long, val slot: String? = null)

/**
 * The pre-session prompts (UX 16.3, 19.4) around a session start: the planned direct FS that picks the soreness rows,
 * the elbow check run through `safety` and the challenge before the session is materialized, and the record of the
 * sheet once the session exists.
 */
object PreSession {
    /** Planned direct FS per group of a challenge session plan (`setcredit` on its rows, C10). */
    fun plannedFs(c: AppContainer, def: CompiledDefinition, plan: SessionPlan): Map<String, Double> {
        val start = c.clock.now()
        val sets = Coverage.plannedSets(def, plan, c.catalogModel, start, c.params)
        return sum(sets.map { s -> SetCredit.credit(s, c.catalogModel.exercise(s.exerciseSlug)!!, c.catalogModel, null, PlanningFacts.ZONE, c.params).fs })
    }

    /** Planned direct FS of a repeated general session: what the source session did. */
    suspend fun plannedFsOfSession(c: AppContainer, sessionId: String): Map<String, Double> {
        val rows = c.db.setLogs().bySession(sessionId)
        return sum(rows.mapNotNull { r ->
            val ex = c.catalog.byId(r.exercise_id) ?: return@mapNotNull null
            val model = c.catalogModel.exercise(ex.slug) ?: return@mapNotNull null
            SetCredit.credit(PlanningFacts.creditSet(r, ex.slug), model, c.catalogModel, null, PlanningFacts.ZONE, c.params).fs
        })
    }

    /** Planned direct FS of a built session (D62): each exercise's sets times its group coefficients of 0.5 or more (C10). */
    fun plannedFsOfBuilt(c: AppContainer, r: dev.fitnessapp.algo.pipeline.BuildResult): Map<String, Double> = sum(
        r.exercises.mapNotNull { x ->
            val e = c.catalogModel.exercise(x.slug) ?: return@mapNotNull null
            Groups.CANONICAL.associateWith { g -> c.catalogModel.groupCoeff(e, g).let { if (it >= 0.5) it * x.sets else 0.0 } }
        },
    )

    private fun sum(ms: List<Map<String, Double>>): Map<String, Double> {
        val out = TreeMap<String, Double>()
        for (g in Groups.CANONICAL) ms.sumOf { it[g] ?: 0.0 }.takeIf { it > 0.0 }?.let { out[g] = Grid.ratio(it) }
        return out
    }

    /**
     * The elbow answer before the session starts: with a running challenge its rules run on it (a stop signal enters the
     * elbow protocol, C33); returns the notice to show (kind, text), if any.
     */
    suspend fun elbow(c: AppContainer, score: Int, symptoms: List<String>): Pair<String, String>? {
        val e = c.challenges.open().firstOrNull { it.status == "active" }
        if (e == null) {
            c.prompts.elbowOnly(score, symptoms)
            return null
        }
        val out = runCatching { c.challengeSessions.elbowCheck(e.id, score, symptoms) }.getOrNull() ?: return null
        return out.notifications.firstOrNull { it.kind == "stop" || it.kind == "warn" }?.let { n -> n.kind to ChallengeTexts.message(out.folded.def, n) }
    }

    /** Records the sheet for the started session: shown items, the answers, and the skips (user, budget, reduced). */
    suspend fun record(c: AppContainer, sessionId: String, sheet: PreSheet?, plan: PromptPlan, a: PreAnswers, nowMs: Long) {
        c.prompts.recordPre(
            sessionId, plan.pre.map { PromptShown(it.prompt.code, it.group, it.reduced) }, a.readiness, a.soreness, a.elbow != null,
            plan.dropped.filter { it.prompt.phase == "pre" }.map { PromptNotAnswered(it.prompt.code, it.group, it.cause) },
            sheet?.let { nowMs - it.openedAtMs },
        )
    }
}

private val READINESS = listOf("worse" to "Worse", "normal" to "Normal", "better" to "Better")
private val ELBOW = listOf(0 to "0 None", 1 to "1 Trace", 2 to "2 Mild", 3 to "3 Pain")
private val SORENESS = listOf("never_sore" to "No", "healed" to "Was, fine now", "still_sore" to "Yes")

/**
 * UX 16.3 readiness sheet: the items `ui.prompts` chose, in its order (readiness, elbow check, soreness rows). Start is
 * always enabled except while a template that requires the elbow check (`requiresReadiness: ["elbow"]`) has none yet;
 * at an elbow score of 2 or more the kinds are asked (a safety tap outside the budget, C69).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReadinessSheet(c: AppContainer, sheet: PreSheet, onDismiss: () -> Unit, onStart: (PreAnswers) -> Unit) {
    var a by remember(sheet) { mutableStateOf(PreAnswers()) }
    val needsElbow = sheet.elbowRequired && sheet.plan.pre.any { it.prompt == Prompt.ELBOW_CHECK }
    val ready = !needsElbow || a.elbow != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Before you start" + if (needsElbow) "" else " (optional)", style = MaterialTheme.typography.titleLarge)
                for (item in sheet.plan.pre) {
                    when (item.prompt) {
                        Prompt.READINESS -> {
                            Text("How do you feel today?", style = MaterialTheme.typography.titleMedium)
                            ChoiceChips(READINESS.map { it.first }, a.readiness, { k -> READINESS.first { it.first == k }.second }) { a = a.copy(readiness = it) }
                        }
                        Prompt.ELBOW_CHECK -> {
                            Text("Elbows right now?", style = MaterialTheme.typography.titleMedium)
                            if (needsElbow) Text("Your challenge needs this before the session.", style = MaterialTheme.typography.bodySmall, color = Fit.Muted)
                            ChoiceChips(ELBOW.map { it.first }, a.elbow, { k -> ELBOW.first { it.first == k }.second }) { a = a.copy(elbow = it, symptoms = if (it >= 2) a.symptoms else emptyList()) }
                            if ((a.elbow ?: 0) >= 2) {
                                Text("What kind?", style = MaterialTheme.typography.labelMedium, color = Fit.Muted)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PAIN_KINDS.filter { it.second.any { r -> r.startsWith("elbow") } }.forEach { (code, _, label) ->
                                        ChipButton(label, code in a.symptoms, { a = a.copy(symptoms = if (code in a.symptoms) a.symptoms - code else a.symptoms + code) }, role = Role.Checkbox)
                                    }
                                }
                            }
                        }
                        Prompt.SORENESS -> {
                            val g = item.group ?: continue
                            if (sheet.plan.pre.first { it.prompt == Prompt.SORENESS } == item) Text("Still sore from last time?", style = MaterialTheme.typography.titleMedium)
                            Text(groupLabel(c, g), style = MaterialTheme.typography.labelLarge)
                            ChoiceChips(SORENESS.map { it.first }, a.soreness[g], { k -> SORENESS.first { it.first == k }.second }) { a = a.copy(soreness = a.soreness + (g to it)) }
                        }
                        else -> {}
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 12.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PrimaryButton("Start", { onStart(a) }, Modifier.fillMaxWidth(), enabled = ready)
                if (!needsElbow) LinkButton("Skip questions", { onStart(PreAnswers()) })
            }
        }
    }
}
