package dev.fitnessapp.app.ui.equipment

import dev.fitnessapp.data.repo.EquipmentRepository

/**
 * Adds rows for equipment types that a newer catalog introduced after the checklist was saved (catalog 1.0.1:
 * preacher curl, triceps extension, dip and ab crunch machines). Each new row is built from the saved
 * checklist (stack unit and step, availability default), so the user's settings carry over; the rows stay
 * unconfirmed. Nothing happens before the first checklist save.
 */
object EquipmentUpgrade {
    suspend fun addMissingTypes(equipment: EquipmentRepository) {
        val rows = equipment.equipment()
        if (rows.isEmpty()) return
        val present = rows.map { it.equipment_type }.toSet()
        val types = equipment.types
        val missing = types.map { it.key }.filter { it != "home_bar" && it !in present }.toSet()
        if (missing.isEmpty()) return
        val checklist = Checklist.fromRows(types, equipment.defaultGym()?.name, rows)
        equipment.saveChecklist(checklist.gym(), checklist.drafts(types).filter { it.equipmentType in missing }, confirmed = false)
    }
}
