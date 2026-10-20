package dev.fitnessapp.sim.seedgen

import dev.fitnessapp.algo.core.det.Sha256Hex
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * [PgJsonbText] reproduces `jsonb::text`. Expected values were read from the project's PostgreSQL on
 * 2026-10-20 (`select j::text, encode(sha256(convert_to(j::text, 'UTF8')), 'hex')`), so migration 016's
 * server-checksum assertion compares like with like.
 */
class PgJsonbTextTest {
    @Test
    fun `matches the server rendering`() {
        val input = """{"bb":1,"a":2,"c":[1,2.50,-0.0,1e2,0.65],"é":"x\ny\t\"q\" \\ ≥ – × \u0001","zz":{"k":true,"kk":null,"":false},"title":{"en":"One-arm","nl":"Van twaalf naar één"}}"""
        val text = PgJsonbText.render(Json.parseToJsonElement(input))
        text shouldBe """{"a": 2, "c": [1, 2.50, 0.0, 100, 0.65], "bb": 1, "zz": {"": false, "k": true, "kk": null}, "é": "x\ny\t\"q\" \\ ≥ – × \u0001", "title": {"en": "One-arm", "nl": "Van twaalf naar één"}}"""
        Sha256Hex.ofUtf8(text) shouldBe "9cb4a5cf9d38be6f52744cf464e2665ab41f02a74bc22ee761feca44d166a3c9"
    }
}
