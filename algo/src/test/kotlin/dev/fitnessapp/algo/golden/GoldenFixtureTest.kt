package dev.fitnessapp.algo.golden

import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.testing.TestParams
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Runs every JSON file in `fixtures/golden/algo` (ALGORITHMS.md A11 GoldenFixtureTest and DoubleRunTest).
 * `gradlew :algo:test -PupdateGoldens` rewrites the expected block; the diff is reviewed in git.
 */
class GoldenFixtureTest {
    private val root = File(requireNotNull(System.getProperty("fitness.repoRoot")) { "fitness.repoRoot not set" })
    private val update = System.getProperty("fitness.updateGoldens") == "true"
    private val pretty = Json { prettyPrint = true }

    private fun fixtures(): List<File> =
        File(root, "fixtures/golden/algo").listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

    @TestFactory
    fun golden(): List<DynamicTest> {
        val files = fixtures()
        files.shouldNotBeEmpty()
        return files.map { file ->
            DynamicTest.dynamicTest(file.nameWithoutExtension) {
                val fixture = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
                fixture.getValue("id").jsonPrimitive.content shouldBe file.nameWithoutExtension
                val first = GoldenRunner.run(fixture, TestParams.defaults())
                val second = GoldenRunner.run(fixture, TestParams.defaults())
                second.outputsJcs shouldBe first.outputsJcs // DoubleRunTest: no hidden state (P7)

                if (update) {
                    val rewritten = JsonObject(
                        fixture + ("expected" to buildJsonObject {
                            put("outputs", first.outputs)
                            put("outputs_jcs_sha256", first.outputsSha256)
                        }),
                    )
                    file.writeText(pretty.encodeToString(JsonObject.serializer(), rewritten) + "\n", Charsets.UTF_8)
                } else {
                    val expected = fixture.getValue("expected").jsonObject
                    first.outputsJcs shouldBe Jcs.canonicalize(expected.getValue("outputs"))
                    first.outputsSha256 shouldBe expected.getValue("outputs_jcs_sha256").jsonPrimitive.content
                }
            }
        }
    }
}
