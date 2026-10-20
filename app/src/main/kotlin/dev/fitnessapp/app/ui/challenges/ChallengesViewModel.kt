package dev.fitnessapp.app.ui.challenges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.eca.GateCriteria
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.enrollment.PauseTools
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.data.challenge.ChallengeEventDraft
import dev.fitnessapp.data.db.entity.ChallengeEnrollmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One card of the Challenges library (UX 14.1). */
data class ChallengeCard(
    val enrollmentId: String?,
    val definitionId: String,
    val title: String,
    val status: String?,
    val lines: List<String>,
    val note: String? = null,
)

data class LibraryUi(
    val loading: Boolean = true,
    val active: List<ChallengeCard> = emptyList(),
    val available: List<ChallengeCard> = emptyList(),
    val past: List<ChallengeCard> = emptyList(),
    /** CHALLENGE_ENGINE 4.6: the enrollment whose ticks wait for the "Were you away?" answer, with the last session date. */
    val absence: Pair<String, LocalDate>? = null,
    val error: String? = null,
)

/**
 * The Challenges tab (UX 14.1): open, available and past challenges. Opening the tab synthesizes due ticks
 * (4.6); an enrollment due for the absence prompt waits for the user's answer here.
 */
class ChallengesViewModel(private val c: AppContainer) : ViewModel() {
    private val zone: ZoneId = ZoneId.of("Europe/Amsterdam")
    private val _ui = MutableStateFlow(LibraryUi())
    val ui: StateFlow<LibraryUi> = _ui

    init {
        viewModelScope.launch {
            val prompt = runCatching {
                withContext(Dispatchers.Default) {
                    c.challenges.ensureBundled()
                    c.challenges.catchUp().absencePromptDue.firstOrNull()
                }
            }.onFailure { _ui.value = _ui.value.copy(error = it.message) }.getOrNull()
            c.challenges.observeAll().collect { rows -> rebuild(rows, prompt) }
        }
    }

    private suspend fun rebuild(rows: List<ChallengeEnrollmentEntity>, prompt: String?) {
        val ui = withContext(Dispatchers.Default) {
            val defs = c.challenges.definitions()
            val open = rows.filter { it.status in setOf("onboarding", "active", "paused") }
            val active = open.mapNotNull { e -> runCatching { card(e) }.getOrNull() }
            val past = rows.filter { it.status in setOf("completed", "abandoned") }.mapNotNull { e ->
                runCatching { ChallengeCard(e.id, e.definition_id, ChallengeTexts.title(c.challenges.definition(e.definition_id)), ChallengeTexts.status(e.status), listOfNotNull(e.started_on?.let { "Started ${ChallengeTexts.date(LocalDate.parse(it))}" })) }.getOrNull()
            }
            // One version per slug: the newest published one that is not already open.
            val available = defs.groupBy { it.second.slug }.mapNotNull { (_, list) -> list.maxByOrNull { it.second.version } }
                .filter { (_, d) -> open.none { it.definition_slug == d.slug } }
                .map { (id, d) -> availableCard(id, d) }
            val absence = prompt?.let { id ->
                val f = c.challenges.folded(id)
                f.state.lastChallengeSessionDate?.let { id to it }
            }
            LibraryUi(false, active, available, past, absence, null)
        }
        _ui.value = ui
    }

    private suspend fun card(e: ChallengeEnrollmentEntity): ChallengeCard {
        val f = c.challenges.folded(e.id)
        val s = f.state
        val facts = c.challengeFacts.facts(f.def, s)
        val met = GateCriteria.criteria(f.def, c.params, s, facts)
        val lines = listOfNotNull(
            "Phase ${s.phase} of ${f.def.doc.phases.size} · Block ${s.block.index} · Week ${s.block.week}",
            s.plannedTest.date?.let { "Next test: ${ChallengeTexts.date(it)}" } ?: if (s.plannedTest.basis == "postponed") "Test postponed: waits for a clean session" else null,
            "Gates ${met.count { it.met == true }} of ${met.size} met",
        )
        return ChallengeCard(e.id, e.definition_id, ChallengeTexts.title(f.def), if (s.status == EnrollmentState.ACTIVE) ChallengeTexts.mode(s.mode) else ChallengeTexts.status(s.status), lines)
    }

    private fun availableCard(id: String, d: CompiledDefinition): ChallengeCard {
        val slots = d.doc.schedule.slotsPerWeek["normal"].orEmpty().size
        val minutes = d.templates.values.filter { it.slot in d.doc.schedule.slotsPerWeek["normal"].orEmpty() }.mapNotNull { it.bookedMin }.maxOrNull()
        return ChallengeCard(
            null, id, ChallengeTexts.title(d), null,
            listOfNotNull(d.doc.summary["en"], "$slots sessions a week" + (minutes?.let { " · about $it min" } ?: "")),
            d.doc.provenance.evidenceNote,
        )
    }

    /** "Yes, I was away": a pause from the day after the last session and a resume today (4.6). */
    fun acceptAbsence() {
        val (id, last) = _ui.value.absence ?: return
        viewModelScope.launch {
            val today = LocalDate.now(zone)
            val from = last.plusDays(1)
            val pauseAt = Week.atLocal(from, LocalTime.NOON, zone)
            runCatching {
                c.challenges.record(id, ChallengeEventDraft(EventTypes.PAUSE, EventPayload.Pause("other"), pauseAt, from))
                c.challenges.record(id, ChallengeEventDraft(EventTypes.RESUME, EventPayload.Resume("other", ChronoUnit.DAYS.between(from, today)), Instant.now(), today))
            }.onFailure { _ui.value = _ui.value.copy(error = it.message) }
            _ui.value = _ui.value.copy(absence = null)
            c.fgsEvent("prompt_answered", mapOf("prompt" to "absence", "value_code" to "pause"))
        }
    }

    /** "No": the missed weeks count as missed (same loads, no advance). */
    fun declineAbsence() {
        viewModelScope.launch {
            runCatching { c.challenges.catchUp(force = true) }
            _ui.value = _ui.value.copy(absence = null)
            c.fgsEvent("prompt_answered", mapOf("prompt" to "absence", "value_code" to "missed"))
        }
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(error = null)
    }

    /** The resume preview of 10.5 for a pause starting today: what 1, 7 and 28 days away would do. */
    suspend fun pausePreview(enrollmentId: String): List<Pair<Long, List<String>>> = withContext(Dispatchers.Default) {
        val f = c.challenges.folded(enrollmentId)
        PauseTools.previewResume(f.def, c.params, f.state, LocalDate.now(zone), zone, f.state.lastFacts, listOf(1, 7, 28))
            .map { p -> p.gapDays to p.notifications.map { ChallengeTexts.message(f.def, it) } }
    }
}
