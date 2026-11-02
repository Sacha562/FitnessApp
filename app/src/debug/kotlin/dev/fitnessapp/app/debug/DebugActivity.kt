package dev.fitnessapp.app.debug

import android.content.res.AssetManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.challenge.enrollment.EventPayload
import dev.fitnessapp.algo.challenge.enrollment.EventTypes
import dev.fitnessapp.algo.challenge.fitexpr.ValueJson
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.golden.GoldenRunner
import dev.fitnessapp.app.FitnessApplication
import dev.fitnessapp.app.ui.challenges.DraftTest
import dev.fitnessapp.app.ui.challenges.SetupDraft
import dev.fitnessapp.app.ui.challenges.SetupImport
import dev.fitnessapp.app.ui.challenges.SetupViewModel
import dev.fitnessapp.app.ui.theme.FitnessTheme
import dev.fitnessapp.data.challenge.ChallengeEventDraft
import dev.fitnessapp.data.telemetry.TelemetryWriter
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Debug menu (debug and sandbox builds only). "Run determinism self-test" runs every bundled golden
 * fixture through :algo on ART and compares the output hash with the one :algo:test produced on the JVM
 * (ANDROID.md 6.3 item 4, guarantee R2). Results go to logcat with the tag DeterminismSelfTest:
 *   adb shell am start -n dev.fitnessapp/dev.fitnessapp.app.debug.DebugActivity --es action selftest
 *   adb logcat -d -s DeterminismSelfTest
 * "restore_set" puts back a set deleted in History after its Undo snackbar timed out, through the same
 * [dev.fitnessapp.data.repo.HistoryRepository.restoreSet] as Undo (so the row syncs and `action_undone` is written):
 *   adb shell am start -n dev.fitnessapp/dev.fitnessapp.app.debug.DebugActivity --es action restore_set --es set_id <id>
 *   adb logcat -d -s DebugRestore
 * "reminder_probe" arms one alarm through the habit-reminder path and logs its delivery latency (ANDROID.md 17):
 *   adb shell am start -n dev.fitnessapp/dev.fitnessapp.app.debug.DebugActivity --es action reminder_probe --ei in_s 90
 *   adb logcat -d -s Reminders
 * "abandon_enrollment" ends a challenge enrollment through the same event as the dashboard's Abandon:
 *   adb shell am start -n dev.fitnessapp/dev.fitnessapp.app.debug.DebugActivity --es action abandon_enrollment --es enrollment_id <id>
 * "drop_dead_letters" discards uploads that can never succeed (test data the server rejected): every dead letter, and
 * outbox rows deferred on a missing parent (`23503`), which would otherwise dead-letter hours later. The local rows
 * stay; "Re-push all data" queues them again.
 *   adb shell am start -n dev.fitnessapp/dev.fitnessapp.app.debug.DebugActivity --es action drop_dead_letters
 *   adb logcat -d -s DebugSync
 * "repair_events" rewrites unsent events whose entity type the server refuses to `none` and requeues their dead letters:
 *   adb shell am start -n dev.fitnessapp/dev.fitnessapp.app.debug.DebugActivity --es action repair_events
 *   adb logcat -d -s DebugSync
 * "repair_sets" rewrites the logged sets of one finished session exercise in another load mode (the Pallof press of
 * 2026-10-23 saved as a belt load, which the server refused): every set becomes [input] [unit] on the gym's
 * [equipment_type], per side when per_side is true (the set's row becomes L, a copy R, 1 ms later); the session
 * exercise takes the mode and sides, its dead letters are dropped, the rows queue again and the session's report is
 * amended (`session.amended`), so the enrollment refolds. Reps, RIR and times stay as logged:
 *   adb shell am start -n dev.fitnessapp/dev.fitnessapp.app.debug.DebugActivity --es action repair_sets --es session_exercise_id <id> \
 *     --es load_mode external --es equipment_type cable_station --es input 25 --es unit lb --ez per_side true --es note "<why>"
 *   adb logcat -d -s DebugSync
 * "pause_enrollment" / "resume_enrollment" (optional enrollment_id, default the first open one) pause with Holiday or resume.
 * "setup_draft" saves a challenge setup draft (SetupDraft JSON, base64) as the wizard's progress, so the wizard opens
 * with those values for the user to check and Start (cutover import, D40); it creates nothing:
 *   adb shell am start -n dev.fitnessapp/dev.fitnessapp.app.debug.DebugActivity --es action setup_draft --es definition_id <id> --es draft_b64 <b64>
 *   adb logcat -d -s DebugSetup
 * "amend_import" corrects the imported earlier tests of an enrollment (optional enrollment_id, default the first open
 * one) with the full list of tests in the setup draft's format (List<DraftTest> JSON, base64), written as a second
 * `enrollment.created` that supersedes the original (D48); it logs the corrected comparison baseline:
 *   adb shell am start -n dev.fitnessapp/dev.fitnessapp.app.debug.DebugActivity --es action amend_import --es tests_b64 <b64>
 *   adb logcat -d -s DebugSetup
 * The calendar actions of [CalendarDebug] (`calendar_*`) work on the device-only test calendar only:
 *   adb shell am start -n dev.fitnessapp/dev.fitnessapp.app.debug.DebugActivity --es action calendar_dump
 *   adb logcat -d -s CalendarDebug
 */
class DebugActivity : ComponentActivity() {
    private val report = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.getStringExtra("action")) {
            "selftest" -> runSelfTest()
            "plan_dump" -> planDump()
            "restore_set" -> restoreSet(intent.getStringExtra("set_id"))
            "reminder_probe" -> {
                val mode = (application as FitnessApplication).container.reminders.probe(intent.getIntExtra("in_s", 90))
                Log.i("Reminders", "probe armed mode=$mode")
            }
            "abandon_enrollment" -> abandonEnrollment(intent.getStringExtra("enrollment_id"))
            "pause_enrollment", "resume_enrollment" -> pauseResume(intent.getStringExtra("action")!!, intent.getStringExtra("enrollment_id"))
            "drop_dead_letters" -> dropDeadLetters()
            "repair_events" -> repairEvents()
            "repair_sets" -> repairSets(intent)
            "setup_draft" -> setupDraft(intent.getStringExtra("definition_id"), intent.getStringExtra("draft_b64"))
            "amend_import" -> amendImport(intent.getStringExtra("enrollment_id"), intent.getStringExtra("tests_b64"))
            "calendar_create_test", "calendar_refresh", "calendar_dump", "calendar_seed", "calendar_edit", "calendar_adopt", "calendar_delete_test" -> {
                val c = (application as FitnessApplication).container
                val i = intent
                c.appScope.launch {
                    CalendarDebug.run(
                        c, i.getStringExtra("action")!!, i.getStringExtra("spec"), i.getLongExtra("event_id", -1),
                        i.getStringExtra("field"), i.getStringExtra("value"), i.getStringExtra("instance_key"),
                    )
                }
            }
        }
        setContent {
            FitnessTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Debug menu", style = MaterialTheme.typography.headlineSmall)
                        Button(onClick = ::runSelfTest) { Text("Run determinism self-test") }
                        Text(report.value, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    /**
     * Logs today's planner view per group (tag `PlanDump`): this week's volume against the target, readiness now and at
     * today's slot start, each option's direct sets and score, and the exercises of each option's build at 75 min.
     */
    private fun planDump() {
        val c = (application as FitnessApplication).container
        c.appScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val out = ArrayList<String>()
            runCatching {
                val now = c.clock.now()
                val week = dev.fitnessapp.app.planning.WeekPlans.thisWeek(c, now)
                val today = dev.fitnessapp.app.planning.WeekPlans.today(c, week, null)
                val day = week.plan.days.first { it.date == today.date }
                val slot = day.slotStart ?: now
                val params = c.params.fork()
                val rNow = dev.fitnessapp.algo.accounting.readiness.Readiness.evaluate(week.input.doses, now, null, null, params).r
                val rSlot = dev.fitnessapp.algo.accounting.readiness.Readiness.evaluate(week.input.doses, slot, null, null, params).r
                out += "now=$now slot=$slot budget=${day.slotBudgetMin} verdict=${today.verdict} primary=${today.primaryOptionId}"
                today.options.forEach { o -> out += "option ${o.optionId} score=${o.score} dJ=${o.dJ} est=${o.estMinutes} reasons=${o.reasons.map { it.code + it.params }}" }
                out += "group status done+committed/targetEff (t) rNow rSlot " + today.options.joinToString(" ") { it.optionId }
                dev.fitnessapp.algo.common.Groups.CANONICAL.forEach { g ->
                    val gt = week.input.targets.groups[g]
                    val v = (week.input.done[g] ?: 0.0) + (week.input.committed[g] ?: 0.0)
                    out += "$g ${gt?.status} ${"%.2f".format(v)}/${gt?.targetEffective} (${gt?.t}) reentry=${gt?.reentry?.active} " +
                        "rNow=${"%.2f".format(rNow[g] ?: 1.0)} rSlot=${"%.2f".format(rSlot[g] ?: 1.0)} " +
                        today.options.joinToString(" ") { o -> "%.2f".format(o.direct[g] ?: 0.0) }
                }
                week.plan.days.forEach { d -> out += "day ${d.date} ${d.status} ${d.restReason?.code ?: ""} " + d.items.joinToString { "${it.kind}:${it.type ?: it.slot}@${it.start}" } }
                val f = dev.fitnessapp.app.planning.SessionBuild.facts(c, now, weekPlan = week.plan)
                today.options.mapNotNull { it.sessionType }.forEach { t ->
                    val type = dev.fitnessapp.algo.planning.allocator.SessionType.of(t)
                    val b = dev.fitnessapp.app.planning.SessionBuild.build(c, dev.fitnessapp.app.planning.SessionBuild.request(f, type, 75.0))
                    out += "build $t est=${b.estMin} " + b.exercises.joinToString { "${it.slug} x${it.sets}" }
                    out += "  alloc direct " + b.allocation.direct.filterValues { it > 0.0 }.entries.joinToString { "${it.key}=${"%.2f".format(it.value)}" }
                }
            }.onFailure { out += "failed: $it" }
            out.forEach { Log.i("PlanDump", it) }
            report.value = out.joinToString("\n")
        }
    }

    private fun runSelfTest() {
        val lines = DeterminismSelfTest.run(assets, (application as FitnessApplication).container.params)
        lines.forEach { Log.i(TAG, it) }
        report.value = lines.joinToString("\n")
    }

    private fun restoreSet(setId: String?) {
        val c = (application as FitnessApplication).container
        c.appScope.launch {
            val result = runCatching {
                val id = requireNotNull(setId) { "set_id missing" }
                val row = requireNotNull(c.db.setLogs().get(id)) { "set not found" }
                val deletedAt = requireNotNull(row.deleted_at) { "set is not deleted" }
                val ev = c.db.events().latest("set_deleted", 50).firstOrNull { it.entity_id == id }
                requireNotNull(ev) { "no set_deleted event for this set" }
                c.history.restoreSet(id, ev.id, c.clock.now().toEpochMilli() - deletedAt)
                "restored $id"
            }.getOrElse { "failed: ${it.message}" }
            Log.i(RESTORE_TAG, result)
            report.value = result
        }
    }

    private fun abandonEnrollment(enrollmentId: String?) {
        val c = (application as FitnessApplication).container
        c.appScope.launch {
            val result = runCatching {
                val id = requireNotNull(enrollmentId) { "enrollment_id missing" }
                val draft = ChallengeEventDraft(EventTypes.ABANDON, EventPayload.Abandon("other"), Instant.now(), LocalDate.now(ZONE))
                val o = c.challenges.record(id, draft)
                "enrollment $id status=${o.folded.state.status}" + o.warnings.joinToString("") { " warning=$it" }
            }.getOrElse { "failed: ${it.message}" }
            Log.i(SYNC_TAG, result)
            report.value = result
        }
    }

    /** Pause (reason Holiday) or resume an enrollment through the dashboard's events (calendar checks, M2b.5). */
    private fun pauseResume(action: String, enrollmentId: String?) {
        val c = (application as FitnessApplication).container
        c.appScope.launch {
            val result = runCatching {
                val id = enrollmentId ?: c.challenges.open().first().id
                val today = LocalDate.now(ZONE)
                val draft = if (action == "pause_enrollment") {
                    ChallengeEventDraft(EventTypes.PAUSE, EventPayload.Pause("holiday"), Instant.now(), today)
                } else {
                    ChallengeEventDraft(EventTypes.RESUME, EventPayload.Resume("other", 0), Instant.now(), today)
                }
                "enrollment $id status=${c.challenges.record(id, draft).folded.state.status}"
            }.getOrElse { "failed: ${it.message}" }
            Log.i(CalendarDebug.TAG, result)
        }
    }

    private fun setupDraft(definitionId: String?, draftB64: String?) {
        val result = runCatching {
            val id = requireNotNull(definitionId) { "definition_id missing" }
            val text = String(android.util.Base64.decode(requireNotNull(draftB64) { "draft_b64 missing" }, android.util.Base64.DEFAULT), Charsets.UTF_8)
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = false }
            val draft = json.decodeFromString(SetupDraft.serializer(), text).copy(layout = SetupViewModel.LAYOUT)
            getSharedPreferences("challenge_setup", MODE_PRIVATE).edit()
                .putString(id, kotlinx.serialization.json.Json.encodeToString(SetupDraft.serializer(), draft)).commit()
            "draft saved for $id: start=${draft.start} mode=${draft.mode} tests=${draft.pastTests.size} vars=${draft.vars.keys}"
        }.getOrElse { "failed: ${it.message}" }
        Log.i(SETUP_TAG, result)
        report.value = result
    }

    private fun amendImport(enrollmentId: String?, testsB64: String?) {
        val c = (application as FitnessApplication).container
        c.appScope.launch {
            val result = runCatching {
                val id = enrollmentId ?: c.challenges.open().first().id
                val text = String(android.util.Base64.decode(requireNotNull(testsB64) { "tests_b64 missing" }, android.util.Base64.DEFAULT), Charsets.UTF_8)
                val drafts = Json { ignoreUnknownKeys = false }.decodeFromString(ListSerializer(DraftTest.serializer()), text)
                val def = c.challenges.folded(id).def
                val tests = drafts.map { requireNotNull(SetupImport.pastTest(def, it)) { "test ${it.date} does not parse" } }
                val out = c.challenges.amendImportedTests(id, tests)
                val s = out.folded.state
                val base = s.testBaseline.entries.joinToString("; ") { (k, b) -> "$k=${ValueJson.text(b.value)}@${b.date}" }
                "enrollment $id tests=${tests.size} lastValid=${s.lastValidTest?.date} count=${s.validTestCount} warnings=${out.warnings} baseline: $base"
            }.getOrElse { "failed: ${it.message}" }
            Log.i(SETUP_TAG, result)
            report.value = result
        }
    }

    /**
     * Events not yet on the server whose entity type the server refuses (the `rep_preference` of 0.0.87-0.0.88) become
     * `none` without an entity id, as TelemetryWriter now writes them; their dead letters go back to the outbox.
     */
    private fun repairEvents() {
        val c = (application as FitnessApplication).container
        c.appScope.launch {
            val result = runCatching {
                c.db.withTransaction {
                    val bad = c.db.events().exportAll()
                        .filter { it.server_updated_at == null && it.entity_type != null && it.entity_type !in TelemetryWriter.ENTITY_TYPES }
                    bad.forEach { c.db.events().upsert(it.copy(entity_type = "none", entity_id = null)) }
                    val ids = bad.map { it.id }.toSet()
                    val dead = c.db.deadLetters().all().filter { it.table_name == "session_events" && it.row_id in ids }
                    val now = c.writeContext.nowMs()
                    dead.forEach { c.outbox.enqueue("session_events", it.row_id, now); c.db.deadLetters().delete(it.id) }
                    "repaired ${bad.size} events, requeued ${dead.size} dead letters"
                }
            }.getOrElse { "failed: ${it.message}" }
            c.syncScheduler.request(trigger = "repair_events", immediate = true)
            Log.i(SYNC_TAG, result)
            report.value = result
        }
    }

    private fun dropDeadLetters() {
        val c = (application as FitnessApplication).container
        c.appScope.launch {
            val result = runCatching {
                c.db.withTransaction {
                    val dead = c.db.deadLetters().all()
                    dead.forEach { c.db.deadLetters().delete(it.id) }
                    val parked = c.db.openHelper.writableDatabase
                        .compileStatement("DELETE FROM outbox WHERE last_error = '23503'")
                        .executeUpdateDelete()
                    "dropped ${dead.size} dead letters, $parked parked outbox rows"
                }
            }.getOrElse { "failed: ${it.message}" }
            Log.i(SYNC_TAG, result)
            report.value = result
        }
    }

    private fun repairSets(i: android.content.Intent) {
        val c = (application as FitnessApplication).container
        val seId = i.getStringExtra("session_exercise_id")
        val mode = i.getStringExtra("load_mode") ?: "external"
        val equipmentType = i.getStringExtra("equipment_type")
        val input = i.getStringExtra("input")?.let(::BigDecimal)
        val unit = i.getStringExtra("unit") ?: "kg"
        val perSide = i.getBooleanExtra("per_side", false)
        val note = i.getStringExtra("note")
        c.appScope.launch {
            val result = runCatching {
                require(mode == "external") { "only external is supported" }
                val se = requireNotNull(c.db.sessionExercises().get(requireNotNull(seId) { "session_exercise_id missing" })) { "no session exercise $seId" }
                val session = requireNotNull(c.db.sessions().get(se.session_id))
                require(session.status == "finished") { "session ${session.id} is ${session.status}" }
                val eq = c.db.gymEquipment().all().first { it.equipment_type == requireNotNull(equipmentType) { "equipment_type missing" } && it.available }
                val native = requireNotNull(input) { "input missing" }
                val kg = (if (unit == "lb") native.multiply(BigDecimal(c.params.double("load.lb_kg").toString())) else native).setScale(2, java.math.RoundingMode.HALF_EVEN)
                val w = c.writeContext
                val now = w.nowMs()
                val sets = c.db.setLogs().bySession(se.session_id).filter { it.session_exercise_id == se.id && it.deleted_at == null }
                require(sets.none { it.side != "both" } || !perSide) { "sets already have sides" }
                val rows = sets.flatMap { s ->
                    val fixed = s.copy(
                        load_mode = mode, equipment_id = eq.id, equipment_calibration_rev = eq.calibration_rev,
                        // The engine's target was the cable step; the belt rounding only came from the wrong mode.
                        planned_input_value = s.planned_input_value?.let { kg.movePointRight(2).toLong() }, planned_input_unit = s.planned_input_unit?.let { "kg" },
                        input_value = native.movePointRight(2).toLong(), input_unit = unit, implement_count = null, load_per_implement_kg = null,
                        implement_base_kg = 0, added_kg = kg.movePointRight(2).toLong(), bodyweight_kg = null, f_bw = null,
                        effective_load_kg = kg.movePointRight(2).toLong(), deviation_note = listOfNotNull(s.deviation_note, note).joinToString("; ").ifEmpty { null },
                        side = if (perSide) "L" else s.side, client_updated_at = w.nextClientMs(), device_id = w.device.deviceId(), app_version = w.appVersion,
                    )
                    if (perSide) {
                        listOf(
                            fixed,
                            fixed.copy(
                                id = w.ids.next(), side = "R", completed_at = s.completed_at + 1, started_at = null, rest_timer_planned_s = null, rest_adjust_s = null,
                                rest_timer_overridden = false, rest_before_s = null, rest_source = null, rest_end_reason = null, client_updated_at = w.nextClientMs(),
                            ),
                        )
                    } else {
                        listOf(fixed)
                    }
                }
                c.db.withTransaction {
                    val seRow = se.copy(
                        load_mode = mode, equipment_id = eq.id, side_mode = if (perSide) "unilateral_sequential" else se.side_mode,
                        side_order = if (perSide) "LR" else se.side_order, client_updated_at = w.nextClientMs(), device_id = w.device.deviceId(), app_version = w.appVersion,
                    )
                    c.db.sessionExercises().upsert(seRow)
                    c.outbox.enqueue("session_exercises", seRow.id, now)
                    rows.forEach { r ->
                        c.db.setLogs().upsert(r)
                        c.outbox.enqueue("set_logs", r.id, now)
                    }
                    val ids = sets.map { it.id }.toSet()
                    c.db.deadLetters().all().filter { it.table_name == "set_logs" && it.row_id in ids }.forEach { c.db.deadLetters().delete(it.id) }
                }
                val o = c.challengeSessions.amend(se.session_id)
                c.syncScheduler.request()
                "repaired ${sets.size} sets of ${se.id} into ${rows.size} rows at $native $unit = $kg kg on ${eq.equipment_type}; amended=${o != null}" +
                    o?.warnings.orEmpty().joinToString("") { " warning=$it" }
            }.getOrElse { "failed: ${it.message}" }
            Log.i(SYNC_TAG, result)
            report.value = result
        }
    }

    private companion object {
        const val TAG = "DeterminismSelfTest"
        const val RESTORE_TAG = "DebugRestore"
        const val SYNC_TAG = "DebugSync"
        const val SETUP_TAG = "DebugSetup"
        val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")
    }
}

object DeterminismSelfTest {
    fun run(assets: AssetManager, params: ParamView): List<String> {
        val names = assets.list("algo").orEmpty().filter { it.endsWith(".json") }.sorted()
        var failures = 0
        val lines = names.map { name ->
            try {
                val text = assets.open("algo/$name").use { it.readBytes().toString(Charsets.UTF_8) }
                val fixture = Json.parseToJsonElement(text).jsonObject
                val expected = fixture.getValue("expected").jsonObject.getValue("outputs_jcs_sha256").jsonPrimitive.content
                val actual = GoldenRunner.run(fixture, params).outputsSha256
                if (actual == expected) "ok   ${name.removeSuffix(".json")} $actual"
                else { failures++; "FAIL ${name.removeSuffix(".json")} expected $expected got $actual" }
            } catch (e: Exception) {
                failures++
                "FAIL $name ${e::class.simpleName}"
            }
        }
        val summary = "RESULT ${if (failures == 0 && names.isNotEmpty()) "PASS" else "FAIL"} " +
            "${names.size - failures}/${names.size} engine ${EngineVersion.ENGINE_VERSION}"
        return lines + summary
    }
}
