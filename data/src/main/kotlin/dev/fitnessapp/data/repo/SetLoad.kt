package dev.fitnessapp.data.repo

import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.load.LoadPoint
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.db.entity.SetLogEntity

/**
 * The load columns of a set from one LOAD-1 point (DATA_MODEL 9.6.4): the engine writes the identity with the
 * session's BW snapshot [sessionBwKg] (kg x 100); the server only verifies it. Used when logging and when a
 * logged load is edited in History, so both paths write the same columns.
 */
internal fun SetLogEntity.withLoad(p: LoadPoint?, fallbackMode: String, sessionBwKg: Long?): SetLogEntity {
    val native = p?.takeIf { it.mode != LoadMode.ASSISTED_MACHINE && it.nativeValue != null }
    return copy(
        load_mode = p?.mode?.code ?: fallbackMode,
        equipment_id = p?.equipmentId, equipment_calibration_rev = p?.calibrationRev,
        input_value = native?.nativeValue?.let { Scaled.fromDouble(it, 2) },
        input_unit = native?.nativeUnit,
        implement_count = p?.implementCount,
        load_per_implement_kg = p?.perImplementKg?.let { Scaled.fromDouble(it, 2) },
        implement_base_kg = Scaled.fromDouble(p?.implementBaseKg ?: 0.0, 2),
        added_kg = Scaled.fromDouble(p?.addedKg ?: 0.0, 2),
        assist_pin_native = p?.assistPinNative?.let { Scaled.fromDouble(it, 2) },
        assist_unit = p?.assistPinNative?.let { p.nativeUnit },
        assist_kg = p?.assistKg?.let { Scaled.fromDouble(it, 2) },
        bodyweight_kg = if ((p?.fBw ?: 0.0) > 0) sessionBwKg else null,
        f_bw = p?.fBw?.takeIf { it > 0 }?.let { Scaled.fromDouble(it, 4) },
        effective_load_kg = p?.wSys?.let { Scaled.fromDouble(it, 2) },
    )
}
