package dev.fitnessapp.data.repo

import dev.fitnessapp.data.db.entity.SetLogEntity
import kotlin.math.abs

object SetChecks {
    /** Mirrors of the `set_logs` CHECK constraints of migration 013 that the client can violate. */
    fun of(r: SetLogEntity): List<String> {
        val out = mutableListOf<String>()
        if ((r.input_value == null) != (r.input_unit == null)) out += "input_pair"
        if ((r.implement_count == null) != (r.load_per_implement_kg == null)) out += "implement_pair"
        if (r.implement_count != null && r.added_kg != r.implement_count * r.load_per_implement_kg!!) out += "implement_sum"
        if ((r.rest_source == null) != (r.rest_before_s == null)) out += "rest_source"
        if (!r.rest_timer_overridden && ((r.rest_adjust_s ?: 0) != 0 || r.rest_end_reason == "skipped")) out += "rest_overridden"
        val fbw = r.f_bw ?: 0
        val modeOk = when (r.load_mode) {
            "external" -> r.assist_kg == null && r.assist_ordinal == null
            "bodyweight" -> r.added_kg == 0L && r.assist_kg == null && r.assist_ordinal == null && fbw > 0
            "bodyweight_plus_external" -> r.assist_kg == null && r.assist_ordinal == null && fbw > 0
            "assisted_machine" -> r.assist_pin_native != null && r.assist_unit != null && r.assist_kg != null && r.equipment_id != null && r.assist_ordinal == null
            "assisted_ordinal" -> r.assist_ordinal != null && r.assist_kg == null
            else -> false
        }
        if (!modeOk) out += "load_mode"
        r.effective_load_kg?.let { eff ->
            val sys = (r.bodyweight_kg ?: 0) / 100.0 * (fbw / 10000.0) + r.implement_base_kg / 100.0 + r.added_kg / 100.0 - (r.assist_kg ?: 0) / 100.0
            if (abs(eff / 100.0 - sys) > 0.01 + 1e-9) out += "effective_load"
        }
        if (r.reps == null && r.duration_s == null) out += "performed"
        if (r.rir_reported != null && r.hold_reserve_s != null) out += "one_effort"
        if ((r.rir_reported == null && r.hold_reserve_s == null) != (r.rir_source == "missing")) out += "effort_source"
        r.rir_reported?.let { if (it % 5 != 0L) out += "rir_half_steps" }
        return out
    }
}
