package dev.fitnessapp.app.ui.equipment

import dev.fitnessapp.algo.core.load.ExerciseLoadAttrs
import dev.fitnessapp.algo.core.load.Laterality
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.load.LoadScheme
import dev.fitnessapp.algo.core.load.Loadables
import dev.fitnessapp.algo.core.load.PlateCount
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.data.db.entity.GymEquipmentEntity
import dev.fitnessapp.data.repo.EquipmentRepository
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** F7 checklist (2026-10-09): every written scheme parses, and the engine enumerates it as the user expects. */
class ChecklistSchemeTest {
    private val params = ParamView.fromParamsFile(
        requireNotNull(javaClass.getResource("/fitness/params/default-params-1.0.2.json")).readText(Charsets.UTF_8),
    )
    private val types = EquipmentRepository.parseTypes(
        requireNotNull(javaClass.getResource("/fitness/reference/equipment_types.json")).readText(Charsets.UTF_8),
    )
    private val external = ExerciseLoadAttrs(0.0, null, Laterality.BILATERAL, listOf(LoadMode.EXTERNAL))
    private val weighted = ExerciseLoadAttrs(1.0, 0.0, Laterality.BILATERAL, listOf(LoadMode.BODYWEIGHT_PLUS_EXTERNAL))

    private fun scheme(c: Checklist, key: String): LoadScheme =
        LoadScheme.parse(c.drafts(types).first { it.equipmentType == key }.loadScheme)

    @Test
    fun `every draft scheme parses`() {
        val c = Checklist.defaults(types)
        c.drafts(types).forEach { LoadScheme.parse(it.loadScheme) }
    }

    @Test
    fun `lb stack in 10 lb steps up to the kg cap, shown with kg`() {
        val c = Checklist.defaults(types)
        val key = types.first { it.defaultSchemeType == "stack" && it.key !in Checklist.HEAVY_STACKS && it.key != "landmine" }.key
        val w = Loadables.enumerate(scheme(c, key), Loadables.Context(external, null), params)
        w.first().nativeValue shouldBe 10.0
        w.first().nativeUnit shouldBe "lb"
        w.first().addedKg shouldBe 4.54
        w.last().nativeValue shouldBe 220.0 // floor(100 kg / 0.4536 / 10) * 10
    }

    @Test
    fun `kg stack keeps the kg grid`() {
        val c = Checklist.defaults(types).copy(stackUnit = "kg", stackStep = 5.0)
        val key = types.first { it.defaultSchemeType == "stack" && it.key !in Checklist.HEAVY_STACKS && it.key != "landmine" }.key
        val w = Loadables.enumerate(scheme(c, key), Loadables.Context(external, null), params)
        w.map { it.nativeValue } shouldBe (1..20).map { it * 5.0 }
    }

    @Test
    fun `1_25 kg comes from the plate list and plates never run out`() {
        val c = Checklist.defaults(types).copy(plates = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25))
        val w = Loadables.enumerate(scheme(c, "barbell"), Loadables.Context(external, null), params)
        w.first { it.addedKg == 22.5 }.plateBreakdown shouldBe listOf(1.25)
        w.first { it.addedKg == 220.0 }.plateBreakdown shouldBe listOf(25.0, 25.0, 25.0, 25.0)
    }

    @Test
    fun `belt uses the fewest plates, limited only by items and maximum`() {
        val c = Checklist.defaults(types)
        val w = Loadables.enumerate(scheme(c, "dip_belt"), Loadables.Context(weighted, bwKg = 70.0), params)
        w.first { it.addedKg == 50.0 }.plateBreakdown shouldBe listOf(25.0, 25.0)
        w.first { it.addedKg == 60.0 }.plateBreakdown shouldBe listOf(25.0, 25.0, 10.0) // fewest items, big plates first
        w.maxOf { it.addedKg } shouldBe 60.0
    }

    @Test
    fun `stored rows round-trip the new fields`() {
        val c = Checklist.defaults(types).copy(stackUnit = "kg", stackStep = 2.5, plates = listOf(20.0, 10.0, 1.25))
        val rows = c.drafts(types).mapIndexed { i, d ->
            GymEquipmentEntity(
                id = "e$i", user_id = "u", client_updated_at = 0L, device_id = "d", app_version = "t",
                equipment_type = d.equipmentType, label = d.label, available = d.available,
                scheme_type = d.loadScheme.getValue("type").jsonPrimitive.content, load_scheme = d.loadScheme.toString(),
            )
        }
        val back = Checklist.fromRows(types, c.gymName, rows)
        back.stackUnit shouldBe "kg"
        back.stackStep shouldBe 2.5
        back.plates shouldBe listOf(20.0, 10.0, 1.25)
        back shouldBe c
    }

    @Test
    fun `a machine's own stack survives the checklist's stack setting`() {
        val c = Checklist.defaults(types)
        val own = """{"add_on_steps":[],"max":100,"ratio":1,"start":2.5,"step":2.5,"type":"stack","unit":"kg"}"""
        val rows = c.drafts(types).mapIndexed { i, d ->
            val mine = d.equipmentType == "cable_station"
            GymEquipmentEntity(
                id = "e$i", user_id = "u", client_updated_at = 0L, device_id = "d", app_version = "t",
                equipment_type = d.equipmentType, label = d.label, available = d.available,
                scheme_type = d.loadScheme.getValue("type").jsonPrimitive.content,
                load_scheme = if (mine) own else d.loadScheme.toString(), calibration_source = if (mine) "measured" else d.calibrationSource,
            )
        }
        val back = Checklist.fromRows(types, c.gymName, rows)
        back.stackUnit shouldBe "lb" // the shared setting comes from a default stack, not the measured one
        val drafts = back.copy(stackUnit = "kg", stackStep = 5.0).drafts(types).associateBy { it.equipmentType }
        drafts.getValue("cable_station").loadScheme.toString() shouldBe own
        drafts.getValue("cable_station").calibrationSource shouldBe "measured"
        LoadScheme.parse(drafts.getValue("leg_press_seated").loadScheme).let { (it as LoadScheme.Stack).unit } shouldBe "kg"
    }

    @Test
    fun `the landmine takes one sleeve of plates in kg on the smallest plate, never the lb stack setting (D66)`() {
        val c = Checklist.defaults(types).copy(plates = listOf(20.0, 10.0, 5.0, 2.5, 1.25))
        val ex = ExerciseLoadAttrs(0.0, 10.0, Laterality.BILATERAL, listOf(LoadMode.EXTERNAL))
        val w = Loadables.enumerate(scheme(c, "landmine"), Loadables.Context(ex, null), params)
        w.first().nativeValue shouldBe 0.0
        w.first { it.nativeValue == 20.0 }.let { it.nativeUnit shouldBe "kg"; it.addedKg shouldBe 20.0; it.wSys shouldBe 30.0 }
        w.map { it.nativeValue!! }.zipWithNext { a, b -> b - a }.distinct() shouldBe listOf(1.25)
        w.last().nativeValue shouldBe 100.0
    }

    @Test
    fun `both calf machines are plate-loaded with their own starting resistance (D101)`() {
        val c = Checklist.defaults(types).copy(calfStandingBaseKg = 30.4)
        val rows = c.drafts(types).mapIndexed { i, d ->
            GymEquipmentEntity(
                id = "e$i", user_id = "u", client_updated_at = 0L, device_id = "d", app_version = "t",
                equipment_type = d.equipmentType, label = d.label, available = d.available,
                scheme_type = d.loadScheme.getValue("type").jsonPrimitive.content,
                load_scheme = d.loadScheme.toString(), calibration_source = d.calibrationSource,
            )
        }
        val back = Checklist.fromRows(types, c.gymName, rows)
        back.calfStandingBaseKg shouldBe 30.4
        back.calfSeatedBaseKg shouldBe null
        val drafts = back.drafts(types)
        val standing = drafts.first { it.equipmentType == "calf_machine_standing" }
        (LoadScheme.parse(standing.loadScheme) as LoadScheme.Plates).baseKg shouldBe 30.4
        standing.calibrationSource shouldBe "measured"
        (LoadScheme.parse(drafts.first { it.equipmentType == "calf_machine" }.loadScheme) as LoadScheme.Plates).baseKg shouldBe null
        val w = Loadables.enumerate(LoadScheme.parse(standing.loadScheme), Loadables.Context(external, null), params)
        w.first { it.nativeValue == 70.0 }.wSys shouldBe 100.4 // 70 kg of plates on the 30.4 kg machine
    }

    @Test
    fun `the 45 degree leg press is plate-loaded, shows the plates and counts its sled (D64, D68)`() {
        val c = Checklist.defaults(types)
        LoadScheme.parse(c.drafts(types).first { it.equipmentType == "leg_press" }.loadScheme) shouldBe
            LoadScheme.Plates(null, listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5).map { PlateCount(it, Checklist.GYM_PLATE_COUNT) })
        val rows = c.copy(sledKg = 75.7).drafts(types).mapIndexed { i, d ->
            GymEquipmentEntity(
                id = "e$i", user_id = "u", client_updated_at = 0L, device_id = "d", app_version = "t",
                equipment_type = d.equipmentType, label = d.label, available = d.available,
                scheme_type = d.loadScheme.getValue("type").jsonPrimitive.content,
                load_scheme = d.loadScheme.toString(), calibration_source = d.calibrationSource,
            )
        }
        val back = Checklist.fromRows(types, c.gymName, rows)
        back.sledKg shouldBe 75.7
        val sled = back.drafts(types).first { it.equipmentType == "leg_press" }
        (LoadScheme.parse(sled.loadScheme) as LoadScheme.Plates).let { it.barKg shouldBe null; it.baseKg shouldBe 75.7 }
        sled.calibrationSource shouldBe "measured"
        val w = Loadables.enumerate(LoadScheme.parse(sled.loadScheme), Loadables.Context(external, null), params)
        w.first().let { it.nativeValue shouldBe 0.0; it.wSys shouldBe 75.7 } // the empty sled
        w.first { it.nativeValue == 140.0 }.let { it.nativeUnit shouldBe "kg"; it.wSys shouldBe 215.7; it.plateBreakdown.sum() shouldBe 70.0 }
    }
}
