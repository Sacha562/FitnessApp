package dev.fitnessapp.app.timer

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** ANDROID.md 27.2 `G3_RestTimerReducerTest`, `G4_RestTimerDstTest`, `G5_RestTimerRecoveryTest` and UX GF-UX-08. */
class RestTimerReducerTest {
    private val cfg = TimerConfig()
    private val logged = 1_791_482_280_000L

    private fun start(at: Long = logged, d: Int = 180, elapsed: Long = 1_000_000L) = RestTimerReducer.start(
        RestTimerReducer.Start("t1", "s", "set1", "e", "both", at, elapsed, 7, d, 900, "rest", "rest_module", "alarm_clock"), cfg,
    ).timer!!

    @Test
    fun `G3 alignment, adjustments, minimum left and cap`() {
        val t = start()
        t.end_wall_ms shouldBe 1_791_482_460_000L
        val plus = RestTimerReducer.adjust(t, 15, "button", logged + 10_000, cfg).timer!!
        val back = RestTimerReducer.adjust(plus, -15, "button", logged + 11_000, cfg).timer!!
        back.end_wall_ms shouldBe 1_791_482_460_000L
        // -15 at 6 s left: at least 5 s must remain
        val late = RestTimerReducer.adjust(t, -15, "button", t.end_wall_ms - 6_000, cfg)
        late.timer!!.end_wall_ms shouldBe 1_791_482_459_000L
        // cap at start + 900 s
        var x = t
        repeat(20) { x = RestTimerReducer.adjust(x, 60, "long_press", logged + 1_000, cfg).timer!! }
        x.end_wall_ms shouldBe 1_791_483_180_000L
    }

    @Test
    fun `short rests do not start a timer`() {
        RestTimerReducer.start(
            RestTimerReducer.Start("t", "s", "set", "e", "L", logged, 0, 1, 0, 900, "between_sides", "rest_module", "alarm_clock"), cfg,
        ).timer shouldBe null
    }

    @Test
    fun `G4 a rest across the DST change`() {
        val t = start(at = 1_792_889_940_000L) // 02:59 CEST on 2026-10-25
        t.end_wall_ms shouldBe 1_792_890_120_000L // 02:02 CET
        RestTimerReducer.display(t, 0L, 1_060_000L, 7) shouldBe RestDisplay.Countdown("2:00", 120.0 / 180.0)
    }

    @Test
    fun `GF-UX-08 display`() {
        val t = start()
        val e0 = t.start_elapsed_ms
        RestTimerReducer.display(t, 0, e0 + 42_000, 7) shouldBe RestDisplay.Countdown("2:18", 138.0 / 180.0)
        RestTimerReducer.display(t, 0, e0 + 179_999, 7).shouldBeInstanceOf<RestDisplay.Countdown>().digits shouldBe "0:01"
        RestTimerReducer.display(t, 0, e0 + 180_000, 7) shouldBe RestDisplay.Overtime("+0:00")
        RestTimerReducer.display(t, 0, e0 + 203_000, 7) shouldBe RestDisplay.Overtime("+0:23")
        RestTimerReducer.display(t, 0, e0 + 203_999, 7) shouldBe RestDisplay.Overtime("+0:23")
        // after a reboot the wall clock is used
        RestTimerReducer.display(t, t.end_wall_ms - 30_000, 5, 8) shouldBe RestDisplay.Countdown("0:30", 30.0 / 180.0)
    }

    @Test
    fun `end alarm, stale alarm and overtime reminder`() {
        val t = start()
        val fired = RestTimerReducer.onEndAlarm(t, "t1", t.end_wall_ms + 800, cfg)
        fired.timer!!.state shouldBe "overtime"
        fired.timer!!.latency_ms shouldBe 800L
        fired.effects.filterIsInstance<RestEffect.ScheduleOvertime>().single().atWallMs shouldBe t.end_wall_ms + 60_000
        RestTimerReducer.onEndAlarm(fired.timer, "t1", t.end_wall_ms + 900, cfg).effects.single()
            .shouldBeInstanceOf<RestEffect.Event>().type shouldBe "rest_alarm_stale"
        val buzz = RestTimerReducer.onOvertimeAlarm(fired.timer, "t1", t.end_wall_ms + 60_000)
        buzz.timer!!.overtime_reminder_sent shouldBe true
        RestTimerReducer.onOvertimeAlarm(buzz.timer, "t1", t.end_wall_ms + 61_000).effects shouldBe emptyList()
    }

    @Test
    fun `rest columns for the next set`() {
        val t = start()
        // logged early, 40 s before the end, with a first touch 30 s earlier
        val (tr, cols) = RestTimerReducer.onNextSetLogged(
            t, RestTimerReducer.NextSet(t.end_wall_ms - 40_000, t.end_wall_ms - 70_000, null, 17.0, t.end_wall_ms - 40_000),
        )
        tr.timer!!.ended_by shouldBe "next_set_early"
        cols.endReason shouldBe "next_set_early"
        cols.plannedS shouldBe 180
        cols.adjustS shouldBe 0
        cols.overridden shouldBe false
        cols.beforeS shouldBe 110.0 // min(first touch, completed - 17 s) - start = 180 - 70
        cols.source shouldBe "estimated"
    }

    @Test
    fun `skipped and adjusted rests are overridden`() {
        val t = RestTimerReducer.adjust(start(), 15, "notification", logged + 5_000, cfg).timer!!
        val skipped = RestTimerReducer.skip(t, logged + 60_000, "button").timer!!
        val (_, cols) = RestTimerReducer.onNextSetLogged(skipped, RestTimerReducer.NextSet(logged + 90_000, null, null, 20.0, logged + 90_000))
        cols.endReason shouldBe "skipped"
        cols.overridden shouldBe true
        cols.adjustS shouldBe 15
        cols.beforeS shouldBe 60.0 // the skip tap is the earliest candidate
    }

    @Test
    fun `holds measure rest from the hold start`() {
        val t = start()
        val (_, cols) = RestTimerReducer.onNextSetLogged(t, RestTimerReducer.NextSet(t.end_wall_ms + 30_000, null, t.end_wall_ms + 5_000, 15.0, t.end_wall_ms + 30_000))
        cols.source shouldBe "measured"
        cols.beforeS shouldBe 185.0
        cols.endReason shouldBe "elapsed"
    }

    @Test
    fun `G5 recovery after process death`() {
        val t = start()
        RestTimerReducer.reconcile(t, 0, t.start_elapsed_ms + 10_000, 7, cfg).effects.first()
            .shouldBeInstanceOf<RestEffect.ScheduleEnd>().atWallMs shouldBe t.end_wall_ms
        val dead = RestTimerReducer.reconcile(t, t.end_wall_ms + 70_000, 1, 8, cfg)
        dead.timer!!.state shouldBe "overtime"
        dead.timer!!.ended_by shouldBe "elapsed_while_dead"
        val recovered = dead.effects.filterIsInstance<RestEffect.Event>().single()
        recovered.type shouldBe "rest_recovered"
        dead.effects.none { it is RestEffect.Alert } shouldBe true // missed by 70 s: no catch-up vibration
    }

    @Test
    fun `end alarm landing in a cold process still alerts`() {
        val t = start()
        val late = t.start_elapsed_ms + (t.end_wall_ms - t.start_wall_ms) + 400 // reconcile ran 400 ms after the end
        val tr = RestTimerReducer.reconcile(t, t.end_wall_ms + 400, late, t.boot_count, cfg)
        tr.timer!!.state shouldBe "overtime"
        tr.effects.any { it is RestEffect.Alert } shouldBe true
        tr.effects.filterIsInstance<RestEffect.ScheduleOvertime>().single().atWallMs shouldBe t.end_wall_ms + cfg.overtimeReminderS * 1000L
    }

    @Test
    fun `session finished while resting`() {
        val tr = RestTimerReducer.sessionFinished(start(), logged + 45_000)
        tr.timer!!.ended_by shouldBe "session_finished"
        tr.effects.filterIsInstance<RestEffect.Event>().single().payload["actual_s"].toString() shouldBe "45.0"
    }

}
