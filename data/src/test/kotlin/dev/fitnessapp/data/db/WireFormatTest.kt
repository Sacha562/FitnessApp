package dev.fitnessapp.data.db

import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.db.entity.GymEquipmentEntity
import dev.fitnessapp.data.sync.SyncJson
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

/** ANDROID.md 8.2 representations on the wire: exact decimals, ISO timestamps, jsonb as JSON, arrays. */
class WireFormatTest {
    private val set = SetLogEntity(
        id = "s1", user_id = "u", client_updated_at = 1791482280123, device_id = "d", app_version = "0.1.0",
        session_id = "w", session_exercise_id = "se", exercise_id = "e", set_index = 1, load_mode = "bodyweight_plus_external",
        added_kg = 2250, bodyweight_kg = 7070, f_bw = 10000, effective_load_kg = 9320, reps = 4, rir_reported = 20,
        flags = listOf("grip_slip_before_last"), completed_at = 1791482280000,
    )

    @Test
    fun `scaled numerics are exact decimals and timestamps ISO with milliseconds`() {
        val json = SyncJson.encodeToJsonElement(SetLogEntity.serializer(), set).jsonObject
        json["added_kg"].toString() shouldBe "22.50"
        json["bodyweight_kg"].toString() shouldBe "70.70"
        json["f_bw"].toString() shouldBe "1.0000"
        json["effective_load_kg"].toString() shouldBe "93.20"
        json["rir_reported"].toString() shouldBe "2.0"
        json["implement_base_kg"].toString() shouldBe "0.00"
        json["client_updated_at"].toString() shouldBe "\"2026-10-08T17:58:00.123Z\""
        json["flags"].toString() shouldBe "[\"grip_slip_before_last\"]"
        json["deleted_at"].toString() shouldBe "null"
    }

    @Test
    fun `pulled rows decode server precision and ignore unknown columns`() {
        val obj = SyncJson.encodeToJsonElement(SetLogEntity.serializer(), set).jsonObject.toMutableMap()
        obj["client_updated_at"] = kotlinx.serialization.json.JsonPrimitive("2026-10-08T17:58:00.123456+00:00")
        obj["created_at"] = kotlinx.serialization.json.JsonPrimitive("2026-10-08T17:58:01.000000+00:00")
        obj["server_updated_at"] = kotlinx.serialization.json.JsonPrimitive("2026-10-08T17:58:01.654321+00:00")
        val back = SyncJson.decodeFromJsonElement(SetLogEntity.serializer(), JsonObject(obj))
        back.client_updated_at shouldBe 1791482280123
        back.server_updated_at shouldBe "2026-10-08T17:58:01.654321+00:00"
        back.copy(server_updated_at = null) shouldBe set
    }

    @Test
    fun `jsonb columns travel as JSON values and are stored canonically`() {
        val eq = GymEquipmentEntity(
            id = "q", user_id = "u", client_updated_at = 0, device_id = "d", app_version = "v", equipment_type = "assisted_pullup",
            label = "Assisted machine", scheme_type = "pin_assist",
            load_scheme = """{"type":"pin_assist","unit":"lb","step":5,"min":0,"max":null,"offset_kg":6.5,"ratio":1.0}""",
        )
        val json = SyncJson.encodeToJsonElement(GymEquipmentEntity.serializer(), eq).jsonObject
        (json["load_scheme"] is JsonObject) shouldBe true
        val back = SyncJson.decodeFromJsonElement(GymEquipmentEntity.serializer(), json)
        back.load_scheme shouldBe """{"max":null,"min":0,"offset_kg":6.5,"ratio":1,"step":5,"type":"pin_assist","unit":"lb"}"""
    }

    @Test
    fun `scaled helper rounds half even and keeps exactness`() {
        Scaled.fromDouble(45.245, 2) shouldBe 4524 // the double nearest 45.245 is below it (A3.3)
        Scaled.toDecimal(4524, 2).toPlainString() shouldBe "45.24"
        Scaled.toDouble(2250, 2) shouldBe 22.5
    }
}
