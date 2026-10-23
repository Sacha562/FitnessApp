package dev.fitnessapp.algo.challenge.cds

import dev.fitnessapp.algo.challenge.ChallengeTestSupport
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

/** GF-CE-31, 32, 39: the OAP definition passes stages 1 and 2; targeted edits fail with the expected check ids. */
class SemanticValidatorTest {
    private val base: JsonObject = Json.parseToJsonElement(ChallengeTestSupport.oapText).jsonObject

    private fun idsAfter(edit: (MutableMap<String, JsonElement>) -> Unit): List<String> {
        val m = LinkedHashMap<String, JsonElement>(base)
        edit(m)
        return when (val r = Cds.load(JsonObject(m), ChallengeTestSupport.context)) {
            is LoadResult.Ok -> emptyList()
            is LoadResult.Err -> r.errors.filter { !it.warning }.map { it.id }
        }
    }

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(LinkedHashMap(this).also { it[key] = value })

    private fun MutableMap<String, JsonElement>.editArray(key: String, index: Int, f: (JsonObject) -> JsonObject) {
        val arr = getValue(key).jsonArray.toMutableList()
        arr[index] = f(arr[index].jsonObject)
        this[key] = JsonArray(arr)
    }

    private fun MutableMap<String, JsonElement>.editExercise(k: String, f: (JsonObject) -> JsonObject) {
        val i = getValue("exercises").jsonArray.indexOfFirst { (it.jsonObject["key"] as JsonPrimitive).content == k }
        editArray("exercises", i, f)
    }

    private fun MutableMap<String, JsonElement>.editRule(id: String, f: (JsonObject) -> JsonObject) {
        val i = getValue("rules").jsonArray.indexOfFirst { (it.jsonObject["id"] as JsonPrimitive).content == id }
        editArray("rules", i, f)
    }

    @Test
    fun `GF-CE-32 the OAP definition passes stages 1 and 2`() {
        val r = ChallengeTestSupport.oapResult
        val errors = (r as? LoadResult.Err)?.errors?.filter { !it.warning }.orEmpty()
        errors.map { it.toString() }.shouldBeEmpty()
        val d = ChallengeTestSupport.oap
        d.rules.size shouldBe 47
        d.templates.keys.sorted() shouldBe listOf("A1", "A2", "A3", "B1", "B3", "C1", "C2", "C3", "T1", "T2")
        d.weekItemKeys shouldBe listOf("plank")
    }

    @Test
    fun `GF-CE-31 negative cases`() {
        idsAfter { m ->
            m.editExercise("oa_negative") { e -> e.with("regions", JsonObject(mapOf("lats" to JsonPrimitive(0.8)))) }
        } shouldContain "V08"
        idsAfter { m ->
            m.editExercise("oa_negative") { e -> e.with("regions", JsonObject(mapOf("lats" to JsonPrimitive(1.0), "core" to JsonPrimitive(0.5)))) }
        } shouldContain "V08"
        idsAfter { m ->
            m.editRule("data.test.pins") { r -> r.with("if", JsonPrimitive("test.valid && defined(t.oa_least_assist_pin)")) }
        } shouldContain "V09"
        idsAfter { m ->
            val props = m.getValue("proposals").jsonArray.toMutableList()
            val i = props.indexOfFirst { (it.jsonObject["id"] as JsonPrimitive).content == "P01" }
            props[i] = props[i].jsonObject.with("supersedes", JsonArray(emptyList()))
            m["proposals"] = JsonArray(props)
        } shouldContain "V09"
        idsAfter { m ->
            m.editRule("prog.B.wpu") { r -> r.with("if", JsonPrimitive("sess.slot == 'B' && ex.wpu.reps >= 2")) }
        } shouldContain "V01"
    }

    @Test
    fun `GF-CE-39 vocabulary`() {
        idsAfter { m ->
            m.editExercise("oa_negative") { e -> e.with("flagsOffered", JsonArray(listOf(JsonPrimitive("gripSlipBeforeLast")))) }
        } shouldContain "V18"
        idsAfter { m ->
            m.editExercise("oa_negative") { e -> e.with("loadMode", JsonPrimitive("bodyweight_plus")) }
        } shouldContain "V18"
    }

    @Test
    fun `GF-CE-38 without the test-day negative the phase-1 gate fails V07`() {
        idsAfter { m ->
            val templates = LinkedHashMap(m.getValue("templates").jsonObject)
            val t1 = templates.getValue("T1").jsonObject
            val items = t1.getValue("items").jsonArray.filterNot { (it.jsonObject["key"] as? JsonPrimitive)?.content == "neg" }
            templates["T1"] = t1.with("items", JsonArray(items))
            m["templates"] = JsonObject(templates)
        } shouldContain "V07"
    }

    @Test
    fun `stage 1 rejects unknown keys`() {
        idsAfter { m -> m["surprise"] = JsonPrimitive(1) } shouldBe listOf("S1")
        idsAfter { m -> m.editRule("prog.B.wpu") { r -> r.with("priority", JsonPrimitive(1)) } } shouldBe listOf("S1")
    }
}
