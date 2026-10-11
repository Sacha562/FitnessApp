package dev.fitnessapp.app.format

import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.SetLogEntity

/** Text of logged sets (UX 5, D12) for the Summary, History and exercise detail. */
object SetLines {
    /** The logged load in its native unit: "BW", "BW + 22.5 kg", "Pin 40 lb", "60 lb", "22.5 kg"; null when none. */
    fun load(s: SetLogEntity): String? {
        val pin = s.assist_pin_native
        val input = s.input_value
        return when {
            s.load_mode == "bodyweight" -> "BW"
            pin != null -> "Pin " + Numbers.num(Scaled.toDouble(pin, 2)) + (s.assist_unit?.let { " $it" } ?: "")
            s.load_mode == "bodyweight_plus_external" -> "BW + " + Numbers.kg(Scaled.toDouble(s.added_kg, 2))
            input != null -> Numbers.num(Scaled.toDouble(input, 2)) + " " + (s.input_unit ?: "kg")
            s.added_kg != 0L -> Numbers.kg(Scaled.toDouble(s.added_kg, 2))
            else -> null
        }
    }

    /** Reps, or seconds for a hold. */
    fun perf(s: SetLogEntity): String = s.reps?.toString() ?: s.duration_s?.let { Numbers.oneDecimal(Scaled.toDouble(it, 1)) + " s" } ?: "-"

    /** "L 8 @ 22.5 kg" (the Summary line). */
    fun line(s: SetLogEntity): String {
        val side = if (s.side == "L" || s.side == "R") "${s.side} " else ""
        val load = load(s)?.takeIf { s.load_mode != "bodyweight" }?.let { " @ $it" } ?: ""
        return side + perf(s) + load
    }

    /** "BW + 22.5 kg × 6 6 5", or one "load × reps" item per set when the load changed. */
    fun compact(sets: List<SetLogEntity>): String {
        if (sets.isEmpty()) return "warm-ups only"
        val loads = sets.map { load(it) }
        fun perfOf(s: SetLogEntity) = (if (s.side == "L" || s.side == "R") s.side else "") + perf(s)
        return if (loads.distinct().size == 1) {
            listOfNotNull(loads.first(), sets.joinToString(" ") { perfOf(it) }).joinToString(" × ")
        } else {
            sets.indices.joinToString(", ") { i -> listOfNotNull(loads[i], perfOf(sets[i])).joinToString(" × ") }
        }
    }

    /** Effort of a set: "2 in reserve", "5+ in reserve", "hold: hard", or null. */
    fun effort(s: SetLogEntity): String? {
        val rir = s.rir_reported
        val reserve = s.hold_reserve_s
        return when {
            rir != null -> Numbers.num(Scaled.toDouble(rir, 1)) + (if (s.rir_is_lower_bound) "+" else "") + " in reserve"
            reserve != null -> "felt " + when (Scaled.toDouble(reserve, 1)) {
                0.0 -> "max"
                2.0 -> "hard"
                5.0 -> "ok"
                else -> "easy"
            }
            else -> null
        }
    }
}
