package dev.fitnessapp.app.timer

import dev.fitnessapp.data.db.local.RestTimerEntity
import dev.fitnessapp.data.repo.RestColumns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** What the controller must do after a transition (ANDROID.md 12.3); the reducer itself has no side effects. */
sealed interface RestEffect {
    data class Persist(val timer: RestTimerEntity) : RestEffect
    data class ScheduleEnd(val timerId: String, val atWallMs: Long) : RestEffect
    data class ScheduleOvertime(val timerId: String, val atWallMs: Long) : RestEffect
    data class CancelAlarms(val timerId: String) : RestEffect
    data object NotifyResting : RestEffect
    data object NotifyOvertime : RestEffect
    data object NotifySession : RestEffect
    data object Alert : RestEffect
    data object ReminderBuzz : RestEffect
    data class Event(val type: String, val payload: Map<String, JsonElement>) : RestEffect
}

data class Transition(val timer: RestTimerEntity?, val effects: List<RestEffect>)

/** Constants of ANDROID.md 25.1 that the reducer needs. */
data class TimerConfig(
    val adjustStepS: Int = 15,
    val longPressStepS: Int = 60,
    val minLeftAfterAdjustS: Int = 5,
    val minTimerS: Int = 10,
    val overtimeReminderS: Int = 60,
    val catchUpAlertMaxS: Int = 60,
)

/** The rest card's display (UX 19.8). */
sealed interface RestDisplay {
    data class Countdown(val digits: String, val barFraction: Double) : RestDisplay
    data class Overtime(val digits: String) : RestDisplay
}

/**
 * The rest-timer state machine of ANDROID.md 12.3 as pure functions: state is timestamps only (start in wall and
 * elapsed time, planned end, adjustments), never a decrementing counter, so process death loses nothing.
 */
object RestTimerReducer {
    const val RUNNING = "running"
    const val OVERTIME = "overtime"
    const val ENDED = "ended"

    data class Start(
        val timerId: String,
        val sessionId: String,
        val setLogId: String,
        val exerciseId: String,
        val side: String,
        val setLoggedAtWall: Long,
        val nowElapsed: Long,
        val bootCount: Int,
        val durationS: Int,
        val hardMaxS: Int,
        val purpose: String,
        val durationSource: String,
        val alarmMode: String,
    )

    fun start(s: Start, cfg: TimerConfig): Transition {
        if (s.durationS < cfg.minTimerS) return Transition(null, emptyList())
        val d = minOf(s.durationS, s.hardMaxS)
        val t = RestTimerEntity(
            id = s.timerId, session_id = s.sessionId, set_log_id = s.setLogId, exercise_id = s.exerciseId, side = s.side,
            purpose = s.purpose, duration_source = s.durationSource, prescribed_s = d, hard_max_s = s.hardMaxS,
            start_wall_ms = s.setLoggedAtWall, start_elapsed_ms = s.nowElapsed, boot_count = s.bootCount,
            end_wall_ms = s.setLoggedAtWall + d * 1000L, state = RUNNING, alarm_mode = s.alarmMode,
        )
        return Transition(
            t,
            listOf(
                RestEffect.Persist(t), RestEffect.ScheduleEnd(t.id, t.end_wall_ms), RestEffect.NotifyResting,
                RestEffect.Event(
                    "rest_started",
                    mapOf(
                        "timer_id" to JsonPrimitive(t.id), "set_id" to JsonPrimitive(t.set_log_id), "planned_s" to JsonPrimitive(d),
                        "purpose" to JsonPrimitive(t.purpose), "duration_source" to JsonPrimitive(t.duration_source),
                        "alarm_mode" to JsonPrimitive(t.alarm_mode),
                    ),
                ),
            ),
        )
    }

    /** ±15 s taps and notification actions, ±60 s long-press repeats (UX 19.8). */
    fun adjust(t: RestTimerEntity, deltaS: Int, via: String, nowWall: Long, cfg: TimerConfig): Transition {
        require(deltaS != 0 && deltaS % cfg.adjustStepS == 0 && kotlin.math.abs(deltaS) <= cfg.longPressStepS) { "bad adjust $deltaS" }
        if (t.state != RUNNING) return Transition(t, emptyList())
        val wanted = t.end_wall_ms + deltaS * 1000L
        val cap = t.start_wall_ms + t.hard_max_s * 1000L
        var end = maxOf(wanted, nowWall + cfg.minLeftAfterAdjustS * 1000L)
        end = minOf(end, cap)
        val capped = end != wanted
        val adj = adjustments(t) + JsonObject(mapOf("at_wall_ms" to JsonPrimitive(nowWall), "delta_s" to JsonPrimitive(deltaS), "via" to JsonPrimitive(via)))
        val next = t.copy(end_wall_ms = end, adjustments_json = JsonArray(adj).toString())
        return Transition(
            next,
            listOf(
                RestEffect.Persist(next), RestEffect.ScheduleEnd(next.id, end), RestEffect.NotifyResting,
                RestEffect.Event(
                    "rest_adjusted",
                    mapOf(
                        "timer_id" to JsonPrimitive(t.id), "delta_s" to JsonPrimitive(deltaS), "new_end_wall_ms" to JsonPrimitive(end),
                        "capped" to JsonPrimitive(capped), "via" to JsonPrimitive(via),
                    ),
                ),
            ),
        )
    }

    fun skip(t: RestTimerEntity, nowWall: Long, via: String): Transition {
        if (t.state == ENDED) return Transition(t, emptyList())
        val next = t.copy(
            state = ENDED, ended_by = if (t.state == RUNNING) "skipped" else t.ended_by ?: "elapsed", skip_at_ms = nowWall,
        )
        return Transition(
            next,
            listOf(
                RestEffect.Persist(next), RestEffect.CancelAlarms(t.id), RestEffect.NotifySession,
                RestEffect.Event(
                    "rest_skipped",
                    mapOf("timer_id" to JsonPrimitive(t.id), "remaining_ms" to JsonPrimitive(maxOf(0L, t.end_wall_ms - nowWall)), "via" to JsonPrimitive(via)),
                ),
            ),
        )
    }

    fun onEndAlarm(t: RestTimerEntity?, timerId: String, firedWall: Long, cfg: TimerConfig): Transition {
        if (t == null || t.id != timerId || t.state != RUNNING) {
            return Transition(t, listOf(RestEffect.Event("rest_alarm_stale", mapOf("timer_id" to JsonPrimitive(timerId)))))
        }
        val next = t.copy(state = OVERTIME, ended_by = "elapsed", fired_wall_ms = firedWall, latency_ms = firedWall - t.end_wall_ms)
        val effects = mutableListOf<RestEffect>(RestEffect.Persist(next), RestEffect.Alert, RestEffect.NotifyOvertime)
        if (cfg.overtimeReminderS > 0) effects += RestEffect.ScheduleOvertime(t.id, t.end_wall_ms + cfg.overtimeReminderS * 1000L)
        return Transition(next, effects)
    }

    fun onOvertimeAlarm(t: RestTimerEntity?, timerId: String, nowWall: Long): Transition {
        if (t == null || t.id != timerId || t.state != OVERTIME || t.overtime_reminder_sent) return Transition(t, emptyList())
        val next = t.copy(overtime_reminder_sent = true)
        return Transition(
            next,
            listOf(
                RestEffect.Persist(next), RestEffect.ReminderBuzz,
                RestEffect.Event("rest_overtime_reminder", mapOf("timer_id" to JsonPrimitive(t.id), "overtime_ms" to JsonPrimitive(nowWall - t.end_wall_ms))),
            ),
        )
    }

    data class NextSet(
        val completedAtWall: Long,
        val firstTouchAfterRestWall: Long?,
        val holdStartWall: Long?,
        val estSetDurationS: Double,
        val nowWall: Long,
    )

    /** The rest columns for the next set and the `rest_ended` event (ANDROID.md 12.3, UX 19.8). */
    fun onNextSetLogged(t: RestTimerEntity, n: NextSet): Pair<Transition, RestColumns> {
        val endedBy = when {
            t.state == RUNNING && n.nowWall < t.end_wall_ms -> "next_set_early"
            t.state == RUNNING -> "elapsed" // the display reached 0 before the alarm was delivered
            else -> t.ended_by ?: "elapsed"
        }
        val start = setStartEstimate(t.start_wall_ms, n.firstTouchAfterRestWall, t.skip_at_ms, n.completedAtWall, n.estSetDurationS, n.holdStartWall)
        val beforeS = round1((start - t.start_wall_ms) / 1000.0)
        val source = if (n.holdStartWall != null) "measured" else "estimated"
        val adj = adjustments(t)
        val adjustS = adj.sumOf { it.jsonObject.getValue("delta_s").jsonPrimitive.long.toInt() }
        val overridden = adj.isNotEmpty() || endedBy == "skipped"
        val next = t.copy(state = ENDED, ended_by = endedBy)
        val cols = RestColumns(t.prescribed_s, if (adj.isEmpty()) 0 else adjustS, overridden, beforeS, source, endedBy)
        val restEndAt = if (t.state != RUNNING || n.nowWall >= t.end_wall_ms) t.end_wall_ms else null
        val event = RestEffect.Event(
            "rest_ended",
            mapOf(
                "timer_id" to JsonPrimitive(t.id), "end_reason" to JsonPrimitive(endedBy), "planned_s" to JsonPrimitive(t.prescribed_s),
                "adjust_s" to JsonPrimitive(adjustS), "actual_s" to JsonPrimitive(beforeS), "rest_source" to JsonPrimitive(source),
                "prev_completed_at" to JsonPrimitive(t.start_wall_ms),
                "rest_end_at" to (restEndAt?.let(::JsonPrimitive) ?: JsonNull),
                "first_touch_at" to (n.firstTouchAfterRestWall?.let(::JsonPrimitive) ?: JsonNull),
                "skip_at" to (t.skip_at_ms?.let(::JsonPrimitive) ?: JsonNull),
                "completed_at" to JsonPrimitive(n.completedAtWall),
            ).filterValues { it !is JsonNull },
        )
        val effects = if (t.state == ENDED) {
            listOf(RestEffect.Persist(next), event) // already skipped: alarms are gone
        } else {
            listOf(RestEffect.Persist(next), RestEffect.CancelAlarms(t.id), RestEffect.NotifySession, event)
        }
        return Transition(next, effects) to cols
    }

    /** The session ended while a rest was running: nothing is copied to set_logs (there is no next set). */
    fun sessionFinished(t: RestTimerEntity, nowWall: Long): Transition {
        if (t.state == ENDED) return Transition(t, emptyList())
        val next = t.copy(state = ENDED, ended_by = "session_finished")
        val adjustS = adjustments(t).sumOf { it.jsonObject.getValue("delta_s").jsonPrimitive.long.toInt() }
        return Transition(
            next,
            listOf(
                RestEffect.Persist(next), RestEffect.CancelAlarms(t.id),
                RestEffect.Event(
                    "rest_ended",
                    mapOf(
                        "timer_id" to JsonPrimitive(t.id), "end_reason" to JsonPrimitive("session_finished"),
                        "planned_s" to JsonPrimitive(t.prescribed_s), "adjust_s" to JsonPrimitive(adjustS),
                        "actual_s" to JsonPrimitive(round1((nowWall - t.start_wall_ms) / 1000.0)), "rest_source" to JsonPrimitive("measured"),
                        "prev_completed_at" to JsonPrimitive(t.start_wall_ms),
                    ) + (if (t.state == OVERTIME) mapOf("rest_end_at" to JsonPrimitive(t.end_wall_ms)) else emptyMap()),
                ),
            ),
        )
    }

    /** Recovery after process death, reboot or update (ANDROID.md 12.6). */
    fun reconcile(t: RestTimerEntity, nowWall: Long, nowElapsed: Long, bootCount: Int, cfg: TimerConfig): Transition {
        val r = remainingMs(t, nowWall, nowElapsed, bootCount)
        return when {
            t.state == RUNNING && r > 0 -> Transition(t, listOf(RestEffect.ScheduleEnd(t.id, t.end_wall_ms), RestEffect.NotifyResting))
            t.state == RUNNING -> {
                // The end alarm can arrive in a cold process after reconcile already ran: a short miss still alerts.
                val next = t.copy(state = OVERTIME, ended_by = "elapsed_while_dead")
                val reminderAt = t.end_wall_ms + cfg.overtimeReminderS * 1000L
                val effects = mutableListOf<RestEffect>(RestEffect.Persist(next))
                if (-r <= cfg.catchUpAlertMaxS * 1000L) effects += RestEffect.Alert
                effects += RestEffect.NotifyOvertime
                if (cfg.overtimeReminderS > 0 && reminderAt > nowWall) effects += RestEffect.ScheduleOvertime(t.id, reminderAt)
                effects += RestEffect.Event("rest_recovered", mapOf("timer_id" to JsonPrimitive(t.id), "missed_by_ms" to JsonPrimitive(-r)))
                Transition(next, effects)
            }
            t.state == OVERTIME && !t.overtime_reminder_sent && t.end_wall_ms + cfg.overtimeReminderS * 1000L > nowWall ->
                Transition(t, listOf(RestEffect.ScheduleOvertime(t.id, t.end_wall_ms + cfg.overtimeReminderS * 1000L), RestEffect.NotifyOvertime))
            t.state == OVERTIME -> Transition(t, listOf(RestEffect.NotifyOvertime))
            else -> Transition(t, emptyList())
        }
    }

    /** Elapsed-based while the boot count is unchanged (immune to wall-clock changes), else wall-based. */
    fun remainingMs(t: RestTimerEntity, nowWall: Long, nowElapsed: Long, bootCount: Int): Long =
        if (t.boot_count == bootCount) t.start_elapsed_ms + (t.end_wall_ms - t.start_wall_ms) - nowElapsed
        else t.end_wall_ms - nowWall

    fun display(t: RestTimerEntity, nowWall: Long, nowElapsed: Long, bootCount: Int): RestDisplay {
        val r = remainingMs(t, nowWall, nowElapsed, bootCount)
        if (t.state == RUNNING && r > 0) {
            val total = (t.end_wall_ms - t.start_wall_ms).toDouble()
            val frac = if (total <= 0) 0.0 else (r / total).coerceIn(0.0, 1.0)
            return RestDisplay.Countdown(mmss((r + 999) / 1000), frac)
        }
        val over = if (t.state == RUNNING) maxOf(0L, -r) else maxOf(0L, nowWall - t.end_wall_ms)
        return RestDisplay.Overtime("+" + mmss(over / 1000))
    }

    /** UX 19.8 `setStartEstimate`. */
    fun setStartEstimate(prevCompletedAt: Long, firstTouchAfterRest: Long?, skipTapAt: Long?, completedAt: Long, estSetDurationS: Double, holdStartAt: Long?): Long {
        if (holdStartAt != null) return holdStartAt
        val candidates = listOfNotNull(firstTouchAfterRest, skipTapAt, completedAt - (estSetDurationS * 1000).toLong())
        return maxOf(prevCompletedAt, candidates.min())
    }

    fun mmss(totalS: Long): String {
        val m = totalS / 60
        val s = totalS % 60
        return m.toString() + ":" + (if (s < 10) "0" else "") + s.toString()
    }

    private fun round1(x: Double): Double = java.math.BigDecimal(x).setScale(1, java.math.RoundingMode.HALF_EVEN).toDouble()

    private fun adjustments(t: RestTimerEntity): List<JsonElement> = Json.parseToJsonElement(t.adjustments_json).jsonArray
}
