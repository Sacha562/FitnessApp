package dev.fitnessapp.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fitnessapp.algo.challenge.materialize.Materializer
import dev.fitnessapp.algo.challenge.plan.TodayAdvisor
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.session.SessionService
import dev.fitnessapp.app.ui.challenges.ChallengeTexts
import dev.fitnessapp.app.ui.challenges.ChallengeWeek
import dev.fitnessapp.app.ui.common.Banner
import dev.fitnessapp.app.ui.common.BannerKind
import dev.fitnessapp.app.ui.common.ChipButton
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.LinkButton
import dev.fitnessapp.app.ui.common.Pill
import dev.fitnessapp.app.ui.common.PrimaryButton
import dev.fitnessapp.app.ui.common.Tile
import dev.fitnessapp.app.ui.live.PAIN_KINDS
import dev.fitnessapp.app.ui.theme.Fit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** The challenge part of Today (UX 9, CHALLENGE_ENGINE 7.6): today's planned slot, or the next one and why not today. */
data class TodayChallengeUi(
    val enrollmentId: String,
    val program: String,
    val mode: String,
    val headline: String,
    val detail: String?,
    /** The slot to start today: the planned one, else the one "train now" allows (7.6); null when none fits. */
    val slot: String?,
    val trainNow: Boolean,
    val blocked: String?,
)

class TodayChallengeViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow<TodayChallengeUi?>(null)
    val ui: StateFlow<TodayChallengeUi?> = _ui
    private val _elbowFor = MutableStateFlow<String?>(null)

    /** The slot waiting for the elbow check (templates with `requiresReadiness: ["elbow"]`, CHALLENGE_ENGINE 11). */
    val elbowFor: StateFlow<String?> = _elbowFor
    private val _notice = MutableStateFlow<Pair<String, String>?>(null)
    val notice: StateFlow<Pair<String, String>?> = _notice
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun refresh() {
        viewModelScope.launch {
            _ui.value = withContext(Dispatchers.Default) { runCatching { build() }.getOrNull() }
        }
    }

    private suspend fun build(): TodayChallengeUi? {
        val e = c.challenges.open().firstOrNull { it.status == "active" || it.status == "paused" } ?: return null
        val f = c.challenges.folded(e.id)
        val def = f.def
        val program = ChallengeTexts.title(def)
        if (f.state.status == "paused") return TodayChallengeUi(e.id, program, f.state.mode, "Challenge paused", "Resume it in the Challenges tab.", null, false, null)
        val facts = c.challengeFacts.facts(def, f.state)
        val (input, plan) = ChallengeWeek.plan(c, f, facts)
        val today = Week.localDate(input.now, input.zone)
        val advice = TodayAdvisor.advise(def, c.params, input, plan)
        fun name(slot: String) = Materializer.templateFor(def, f.state, slot)?.name?.get("en") ?: "Session $slot"
        val todaySlot = advice.slot
        if (advice.kind == "challenge" && todaySlot != null) {
            val done = plan.sessions.any { it.date == today && it.slot == todaySlot && it.source == "done" }
            val time = advice.start?.let { Week.localTime(it, input.zone).toString() }
            return TodayChallengeUi(
                e.id, program, f.state.mode, if (done) "Done today: ${name(todaySlot)}" else "Today: ${name(todaySlot)}",
                listOfNotNull(time, ChallengeTexts.mode(f.state.mode)).joinToString(" · "), if (done) null else todaySlot, false, null,
            )
        }
        val next = plan.sessions.firstOrNull { it.source != "done" && it.date.isAfter(today) }
        val nextText = next?.let { "Next: ${name(it.slot)} ${ChallengeTexts.date(it.date)} ${Week.localTime(it.interval.start, input.zone)}" }
            ?: "This week's challenge sessions are done."
        val now = TodayAdvisor.advise(def, c.params, input, plan, trainNow = true)
        val blocked = if (advice.kind == "rest") advice.reasons.firstOrNull()?.substringAfter(": ")?.let { "Rest today: $it" } else null
        return TodayChallengeUi(
            e.id, program, f.state.mode, nextText, blocked, if (now.kind == "challenge" && now.reasons == listOf("train_now")) now.slot else null, true, blocked,
        )
    }

    fun start(slot: String, onOpen: (String) -> Unit) {
        val u = _ui.value ?: return
        viewModelScope.launch {
            val needsElbow = withContext(Dispatchers.Default) {
                val f = c.challenges.folded(u.enrollmentId)
                "elbow" in Materializer.templateFor(f.def, f.state, slot)?.requiresReadiness.orEmpty() && !checkedToday()
            }
            if (needsElbow) _elbowFor.value = slot else launchSession(slot, onOpen)
        }
    }

    private suspend fun checkedToday(): Boolean {
        val today = LocalDate.now(ChallengeWeek.ZONE).toString()
        return c.db.wellness().countKindOn("elbow_check", today) > 0
    }

    /** Readiness sheet answer (UX 16.3): the safety rules run on it before the session is materialized. */
    fun submitElbow(score: Int, symptoms: List<String>, onOpen: (String) -> Unit) {
        val u = _ui.value ?: return
        val slot = _elbowFor.value ?: return
        _elbowFor.value = null
        viewModelScope.launch {
            val out = runCatching { c.challengeSessions.elbowCheck(u.enrollmentId, score, symptoms) }.getOrNull()
            out?.notifications?.firstOrNull { it.kind == "stop" || it.kind == "warn" }?.let { n -> _notice.value = n.kind to ChallengeTexts.message(out.folded.def, n) }
            launchSession(slot, onOpen)
        }
    }

    fun cancelElbow() {
        _elbowFor.value = null
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private suspend fun launchSession(slot: String, onOpen: (String) -> Unit) {
        val u = _ui.value ?: return
        _busy.value = true
        val id = runCatching { withContext(Dispatchers.Default) { c.challengeSessions.start(u.enrollmentId, slot) } }
            .onFailure { _notice.value = "warn" to (it.message ?: "The session could not start.") }.getOrNull()
        _busy.value = false
        if (id != null) {
            SessionService.start(c.appContext)
            onOpen(id)
        }
        refresh()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayChallengeTile(c: AppContainer, sessionActive: Boolean, onOpenSession: (String) -> Unit) {
    val vm: TodayChallengeViewModel = viewModel(key = "today_challenge", factory = viewModelFactory { initializer { TodayChallengeViewModel(c) } })
    val ui by vm.ui.collectAsState()
    val elbowFor by vm.elbowFor.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(sessionActive) { vm.refresh() }
    notice?.let { (kind, text) -> Banner(text, if (kind == "stop" || kind == "warn") BannerKind.SAFETY else BannerKind.ADVISORY, action = "OK", onAction = vm::dismissNotice) }
    val u = ui ?: return
    Tile(spacing = 6.dp) {
        Pill(u.program + " · " + ChallengeTexts.mode(u.mode), container = if (u.mode == "injury") Fit.SafetyContainer else Fit.Raised)
        Text(u.headline, style = MaterialTheme.typography.titleLarge)
        u.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fit.Muted) }
        val slot = u.slot
        if (!sessionActive && slot != null) {
            if (u.trainNow) {
                LinkButton(if (busy) "Starting…" else "Start session $slot now instead", { vm.start(slot, onOpenSession) }, enabled = !busy)
            } else {
                PrimaryButton(if (busy) "Starting…" else "Start session $slot", { vm.start(slot, onOpenSession) }, Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
    }
    if (elbowFor != null) ElbowCheckDialog(onDismiss = vm::cancelElbow) { score, symptoms -> vm.submitElbow(score, symptoms, onOpenSession) }
}

/** UX 16.3 elbow check: 0-3, with the kind asked at 2 or more (C69); the stop signals start the elbow protocol. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ElbowCheckDialog(onDismiss: () -> Unit, onSubmit: (Int, List<String>) -> Unit) {
    var score by remember { mutableStateOf<Int?>(null) }
    var kinds by remember { mutableStateOf(emptySet<String>()) }
    val labels = listOf(0 to "Fine", 1 to "A trace", 2 to "Some ache", 3 to "Pain")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How do your elbows feel?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChips(labels, labels.firstOrNull { it.first == score }, { it.second }) { score = it.first }
                if ((score ?: 0) >= 2) {
                    Text("What kind?", style = MaterialTheme.typography.labelMedium, color = Fit.Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PAIN_KINDS.filter { it.second.any { r -> r.startsWith("elbow") } }.forEach { (code, _, label) ->
                            ChipButton(label, code in kinds, { kinds = if (code in kinds) kinds - code else kinds + code }, role = Role.Checkbox)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(score!!, kinds.toList()) }, enabled = score != null) { Text("Continue") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
