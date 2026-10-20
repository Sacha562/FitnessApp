package dev.fitnessapp.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.fitnessapp.app.FitnessApplication
import kotlinx.coroutines.launch
import java.time.LocalDate

/** N-HABIT alarms and actions (ANDROID.md 17): fire, the snoozed fire, Snooze 30 min and Skip today. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as FitnessApplication).container
        val habit = intent.getStringExtra(EXTRA_HABIT) ?: return
        val date = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val rc = HabitReminders.requestCode(HabitReminders.id(habit, date), emptySet())
        val code = intent.getIntExtra(EXTRA_RC, -1).takeIf { it >= 0 } ?: rc ?: return
        val pending = goAsync()
        c.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_FIRE, ACTION_SNOOZE_FIRE -> c.reminders.onFire(code, habit, date, intent.getLongExtra(EXTRA_DUE, System.currentTimeMillis()))
                    ACTION_SNOOZE -> c.reminders.snooze(code, habit, date)
                    ACTION_SKIP -> c.reminders.skipToday(code, habit, date)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.fitnessapp.reminder.FIRE"
        const val ACTION_SNOOZE_FIRE = "dev.fitnessapp.reminder.SNOOZE_FIRE"
        const val ACTION_SNOOZE = "dev.fitnessapp.reminder.SNOOZE"
        const val ACTION_SKIP = "dev.fitnessapp.reminder.SKIP_TODAY"
        const val EXTRA_HABIT = "habit_id"
        const val EXTRA_DATE = "date"
        const val EXTRA_DUE = "due_ms"
        const val EXTRA_RC = "request_code"
    }
}
