package dev.fitnessapp.sim.seedgen

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.det.Sha256Hex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * Migration 016 (DATA_MODEL 13.2): publishes a validated challenge definition as a global row of
 * `fitness.challenge_definitions`. The definition text is too large for one MCP call, so the parts stage
 * ~30 KB chunks of the compact JSON in `fitness_private.challenge_definition_staging` and the last part
 * inserts the row, asserts both hashes and drops the staging table:
 *
 * - `jcs_sha256` is the client's RFC 8785 hash (one producer, the authoring tool);
 * - `server_checksum` is set by the guard trigger from `definition::text`; the expected value is computed
 *   here with [PgJsonbText], so a byte-level difference between the file and the stored jsonb fails the
 *   migration. The two hashes are never compared with each other (DATA_MODEL 8).
 */
object ChallengeSeedGen {
    private const val SERVER_DEVICE = "ffffffff-ffff-ffff-ffff-ffffffffffff"
    private const val CHUNK_CHARS = 30_000
    private const val QUOTE = "\$def\$"

    data class Result(val parts: List<Pair<String, String>>, val id: String, val jcsSha256: String, val serverChecksum: String, val bytes: Int)

    fun generate(def: CompiledDefinition, element: JsonObject, migration: String): Result {
        val compact = element.toString()
        require(!compact.contains(QUOTE)) { "definition contains the dollar-quote tag" }
        val id = Ids.fitness("challenge_definition:${def.slug}@${def.version}").toString()
        val checksum = Sha256Hex.ofUtf8(PgJsonbText.render(element))
        val chunks = compact.chunked(CHUNK_CHARS)
        val total = chunks.size + 1
        val title = def.doc.title
        val parts = ArrayList<Pair<String, String>>()
        chunks.forEachIndexed { i, chunk ->
            val n = i + 1
            val create = if (n == 1) {
                """
                |create table if not exists fitness_private.challenge_definition_staging (
                |  part int primary key,
                |  txt  text not null
                |);
                |revoke all on table fitness_private.challenge_definition_staging from public, anon, authenticated;
                |
                """.trimMargin()
            } else {
                ""
            }
            val sql = header(migration, n, total, def, id) + create +
                "insert into fitness_private.challenge_definition_staging (part, txt) values ($n, $QUOTE$chunk$QUOTE)\n" +
                "on conflict (part) do update set txt = excluded.txt;\n"
            parts += "${migration}_part$n" to sql
        }
        val finalSql = header(migration, total, total, def, id) + """
            |insert into fitness.challenge_definitions
            |  (id, user_id, slug, version, status, schema_version, engine_range, title_en, title_nl, definition,
            |   jcs_sha256, server_checksum, client_updated_at, device_id, app_version)
            |select '$id'::uuid, null, '${def.slug}', '${def.version}', 'published', '${def.doc.schema}', '${def.doc.engine}',
            |       ${lit(title.getValue("en"))}, ${title["nl"]?.let(::lit) ?: "null"}, string_agg(txt, '' order by part)::jsonb,
            |       '${def.jcsSha256}', repeat('0', 64), now(), '$SERVER_DEVICE'::uuid, 'migration'
            |  from fitness_private.challenge_definition_staging
            |having count(*) = ${chunks.size}
            |on conflict (id) do nothing;
            |
            |do $$
            |declare
            |  r fitness.challenge_definitions;
            |begin
            |  select * into r from fitness.challenge_definitions where id = '$id';
            |  if r.id is null then
            |    raise exception '016: ${def.slug}@${def.version} was not inserted (staging incomplete?)';
            |  end if;
            |  if r.status <> 'published' or r.jcs_sha256 <> '${def.jcsSha256}' then
            |    raise exception '016: unexpected status % or jcs_sha256 %', r.status, r.jcs_sha256;
            |  end if;
            |  if r.server_checksum <> '$checksum' then
            |    raise exception '016: stored definition differs from the file (server_checksum %)', r.server_checksum;
            |  end if;
            |end;
            |$$;
            |
            |drop table fitness_private.challenge_definition_staging;
            |
        """.trimMargin()
        parts += "${migration}_part$total" to finalSql
        return Result(parts, id, def.jcsSha256, checksum, compact.toByteArray(Charsets.UTF_8).size)
    }

    private fun header(migration: String, n: Int, total: Int, def: CompiledDefinition, id: String): String = """
        |-- ${migration}_part$n (M2b, data, part $n of $total): challenge definition ${def.slug}@${def.version} — DATA_MODEL.md 9.4.1, 13.2
        |--
        |-- GENERATED by `gradlew :sim:run --args="seedgen-challenge docs/challenges/${def.slug}.challenge.v1.json"` from the
        |-- validated definition (CDS stages 1 and 2). Do not edit by hand. Apply the parts in order; the last part
        |-- inserts id $id as published and checks jcs_sha256 and the server checksum.
        |-- Manual rollback (only with explicit user approval): delete the row before any enrollment references it.
        |
        |
    """.trimMargin()

    private fun lit(s: String): String = "'" + s.replace("'", "''") + "'"
}

/**
 * PostgreSQL's `jsonb::text` rendering (verified against the server in research, data_model "Hashing"):
 * object keys ordered by byte length, then bytes; `", "` and `": "` separators; numbers as `numeric` prints
 * them (scale kept, no exponent); strings escaped like `escape_json` (quote, backslash, `\b \f \n \r \t`,
 * other control characters as `\u00XX`), every other character raw.
 */
object PgJsonbText {
    fun render(e: JsonElement): String = StringBuilder().also { write(e, it) }.toString()

    private fun write(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> when {
                e.isString -> string(e.content, out)
                e.content == "true" || e.content == "false" -> out.append(e.content)
                else -> out.append(BigDecimal(e.content).toPlainString())
            }
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, x ->
                    if (i > 0) out.append(", ")
                    write(x, out)
                }
                out.append(']')
            }
            is JsonObject -> {
                out.append('{')
                val keys = e.keys.sortedWith(compareBy<String> { it.toByteArray(Charsets.UTF_8).size }.thenComparator { a, b -> compareBytes(a, b) })
                keys.forEachIndexed { i, k ->
                    if (i > 0) out.append(", ")
                    string(k, out)
                    out.append(": ")
                    write(e.getValue(k), out)
                }
                out.append('}')
            }
        }
    }

    private fun compareBytes(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return x.size - y.size
    }

    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
