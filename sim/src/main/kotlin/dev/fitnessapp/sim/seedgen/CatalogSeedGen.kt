package dev.fitnessapp.sim.seedgen

import dev.fitnessapp.algo.common.Ids
import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.selection.catalog.Catalog
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.algo.selection.catalog.CatalogValidator
import dev.fitnessapp.algo.selection.catalog.CatalogDomains
import dev.fitnessapp.algo.selection.catalog.Taxonomy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Migration 009 (catalog seed) from `fitness/catalog/catalog-<version>.json` (DATA_MODEL 13.2, 13.3).
 * The catalog is validated with CAT-1 first and every slug a published challenge definition references must
 * be present (C38); generation fails otherwise. Rows are global (`user_id` null) with UUIDv5 ids
 * `exercise:<slug>` and `exercise_stimulus:<slug>:<region>`, written as server rows
 * (`device_id = SERVER_DEVICE_ID`, `app_version = 'migration'`). Upserts are idempotent (7.10).
 */
object CatalogSeedGen {
    private const val SERVER_DEVICE = "ffffffff-ffff-ffff-ffff-ffffffffffff"
    private const val CHUNK_CHARS = 30_000

    data class Result(val parts: List<Pair<String, String>>, val exercises: Int, val stimulus: Int, val ratios: Int, val jcsSha256: String)

    fun generate(resources: File, definitions: List<File>, version: String): Result {
        val text = File(resources, "catalog/catalog-$version.json").readText(Charsets.UTF_8)
        val doc = CatalogDoc.parse(text)
        require(doc.catalogVersion == version) { "catalog_version ${doc.catalogVersion} != $version" }
        val taxonomy = Taxonomy.parse(
            File(resources, "reference/muscle_groups.json").readText(Charsets.UTF_8),
            File(resources, "reference/muscle_regions.json").readText(Charsets.UTF_8),
        )
        val stations = Json.parseToJsonElement(File(resources, "reference/equipment_types.json").readText(Charsets.UTF_8))
            .jsonObject.getValue("rows").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content }.toSet()
        val local = sortedSetOf<String>()
        val refs = sortedSetOf<String>()
        for (f in definitions) {
            val def = Json.parseToJsonElement(f.readText(Charsets.UTF_8)).jsonObject
            for (e in def.getValue("exercises").jsonArray.map { it.jsonObject }) {
                when (val ref = e["catalogRef"]) {
                    is JsonPrimitive -> refs += ref.content
                    is JsonObject -> refs += ref.getValue("map").jsonObject.values.map { it.jsonPrimitive.content }
                    else -> if (e["local"]?.jsonPrimitive?.content == "true") local += e.getValue("key").jsonPrimitive.content
                }
            }
        }
        val catalog = Catalog(doc, taxonomy)
        val report = CatalogValidator.validate(catalog, stations, challengeLocalSlugs = local, requiredSlugs = local + refs)
        require(report.ok) { "CAT-1 failed:\n" + report.issues.joinToString("\n") }

        val hash = Jcs.sha256Hex(Json.parseToJsonElement(text))
        val exercises = catalog.exercises
        val stimulusCount = exercises.sumOf { it.stimulus.size }

        val statements = mutableListOf<String>()
        statements += buildString {
            appendLine("-- fitness.catalog_releases $version (immutable once released)")
            appendLine("insert into fitness.catalog_releases (version, exercise_count, stimulus_count, jcs_sha256, notes)")
            appendLine("values (${q(version)}, ${exercises.size}, $stimulusCount, ${q(hash)},")
            appendLine("        'Curated Basic-Fit catalog (ALGORITHMS_PLANNING 10.8); approved by the user before seeding (D19).')")
            appendLine("on conflict (version) do nothing;")
        }
        exercises.chunked(12).forEach { statements += exerciseUpsert(it, version) }
        exercises.flatMap { e -> e.stimulus.toSortedMap().map { (r, c) -> Triple(e.slug, r, c) } }
            .chunked(60).forEach { statements += stimulusUpsert(it) }
        statements += ratioUpsert(doc, version)
        statements += buildString {
            appendLine("-- Assertions (DATA_MODEL 17.8): counts and the release hash equal the generator's values.")
            appendLine("do $$")
            appendLine("begin")
            appendLine("  if (select jcs_sha256 from fitness.catalog_releases where version = ${q(version)}) <> ${q(hash)} then")
            appendLine("    raise exception 'catalog $version: stored jcs_sha256 differs from the generated one';")
            appendLine("  end if;")
            appendLine("  if (select count(*) from fitness.exercises where user_id is null and catalog_version = ${q(version)}) <> ${exercises.size} then")
            appendLine("    raise exception 'catalog $version: expected ${exercises.size} global exercises';")
            appendLine("  end if;")
            appendLine("  if (select count(*) from fitness.exercise_stimulus s join fitness.exercises e on e.id = s.exercise_id")
            appendLine("      where s.user_id is null and s.deleted_at is null and e.catalog_version = ${q(version)}) <> $stimulusCount then")
            appendLine("    raise exception 'catalog $version: expected $stimulusCount stimulus rows';")
            appendLine("  end if;")
            appendLine("  if (select count(*) from fitness.exercise_transfer_ratios where catalog_version = ${q(version)}) <> ${doc.transferRatios.size} then")
            appendLine("    raise exception 'catalog $version: expected ${doc.transferRatios.size} transfer ratios';")
            appendLine("  end if;")
            appendLine("end;")
            appendLine("$$;")
        }

        // Pack whole statements into parts that fit one MCP apply call each.
        val parts = mutableListOf<StringBuilder>()
        for (s in statements) {
            if (parts.isEmpty() || parts.last().length + s.length > CHUNK_CHARS) parts += StringBuilder()
            parts.last().append(s).appendLine()
        }
        val named = parts.mapIndexed { i, body ->
            val name = "fitness_009_catalog_seed_part" + (i + 1).toString().padStart(2, '0')
            name to header(name, i + 1, parts.size, version, exercises.size, stimulusCount, doc.transferRatios.size) + body
        }
        return Result(named, exercises.size, stimulusCount, doc.transferRatios.size, hash)
    }

    private val EXERCISE_COLUMNS = listOf(
        "id", "user_id", "client_updated_at", "device_id", "app_version", "slug", "name_en", "name_nl", "aliases", "pattern",
        "family", "equipment", "requires_station", "load_mode", "allowed_load_modes", "bw_fraction", "implement_base_kg",
        "laterality", "measure", "contraction", "hold_class", "ecc_emphasis", "mechanics", "curve_class", "rest_class",
        "safety_class", "grip", "stretch_emphasis", "stability", "skill", "axial", "systemic", "sfr", "setup_s",
        "grip_demand", "js_elbow_medial", "js_elbow_lateral", "js_shoulder_ant", "js_shoulder_overhead", "js_wrist",
        "js_lumbar", "js_knee", "js_hip", "tags", "flags_offered", "external_refs", "evidence_note", "catalog_version",
    )

    /** Columns compared by the idempotence guard (everything the seed owns except the sync bookkeeping). */
    private val EXERCISE_PAYLOAD = EXERCISE_COLUMNS.drop(5)

    private fun exerciseUpsert(rows: List<CatalogExercise>, version: String): String = buildString {
        appendLine("insert into fitness.exercises (${EXERCISE_COLUMNS.joinToString(", ")})")
        appendLine("values")
        appendLine(rows.joinToString(",\n") { e -> "  (" + exerciseValues(e, version).joinToString(", ") + ")" })
        appendLine("on conflict (id) do update")
        appendLine("  set " + (listOf("client_updated_at", "device_id", "app_version") + EXERCISE_PAYLOAD)
            .joinToString(",\n      ") { "$it = excluded.$it" })
        appendLine("  where (" + EXERCISE_PAYLOAD.joinToString(", ") { "fitness.exercises.$it" } + ")")
        appendLine("        is distinct from")
        appendLine("        (" + EXERCISE_PAYLOAD.joinToString(", ") { "excluded.$it" } + ");")
    }

    private fun exerciseValues(e: CatalogExercise, version: String): List<String> = listOf(
        q(Ids.exercise(e.slug).toString()) + "::uuid", "null", "now()", q(SERVER_DEVICE) + "::uuid", q("migration"),
        q(e.slug), q(e.nameEn), e.nameNl?.let(::q) ?: "null", arr(e.aliases), q(e.pattern), q(e.family), arr(e.equipment),
        arr(e.requiresStation), q(e.loadMode), arr(e.allowedLoadModes), dec(e.bwFraction, 4),
        e.implementBaseKg?.let { dec(it, 2) } ?: "null", q(e.laterality), q(e.measure), q(e.contraction),
        e.holdClass?.let(::q) ?: "null", e.eccEmphasis.toString(), q(e.mechanics), e.curveClass?.let(::q) ?: "null",
        q(e.restClass), q(e.safetyClass), q(e.grip), "${e.stretchEmphasis}", "${e.stability}", "${e.skill}", "${e.axial}",
        "${e.systemic}", "${e.sfr}", "${e.setupS}", "${e.gripDemand}",
    ) + CatalogDomains.JOINTS.map { "${e.js(it)}" } + listOf(
        arr(e.tags.sorted()), arr(e.flagsOffered), q(Jcs.canonicalize(e.externalRefs)) + "::jsonb",
        e.evidenceNote?.let(::q) ?: "null", q(version),
    )

    private fun stimulusUpsert(rows: List<Triple<String, String, Double>>): String = buildString {
        appendLine("insert into fitness.exercise_stimulus (id, user_id, client_updated_at, device_id, app_version, exercise_id, region_id, coeff)")
        appendLine("values")
        appendLine(rows.joinToString(",\n") { (slug, region, coeff) ->
            "  (${q(Ids.exerciseStimulus(slug, region).toString())}::uuid, null, now(), ${q(SERVER_DEVICE)}::uuid, 'migration', " +
                "${q(Ids.exercise(slug).toString())}::uuid, ${q(region)}, ${dec(coeff, 2)})"
        })
        appendLine("on conflict (id) do update")
        appendLine("  set client_updated_at = excluded.client_updated_at, coeff = excluded.coeff, deleted_at = null")
        appendLine("  where (fitness.exercise_stimulus.coeff, fitness.exercise_stimulus.deleted_at) is distinct from (excluded.coeff, null);")
    }

    private fun ratioUpsert(doc: CatalogDoc, version: String): String = buildString {
        appendLine("-- fitness.exercise_transfer_ratios (${doc.transferRatios.size} rows, expert-opinion)")
        appendLine("insert into fitness.exercise_transfer_ratios (from_exercise_id, to_exercise_id, ratio, evidence, catalog_version)")
        appendLine("values")
        appendLine(doc.transferRatios.sortedWith(compareBy({ it.fromSlug }, { it.toSlug })).joinToString(",\n") { r ->
            val evidence = (r.evidence + (r.note?.let { "; $it" } ?: "")).take(200)
            "  (${q(Ids.exercise(r.fromSlug).toString())}::uuid, ${q(Ids.exercise(r.toSlug).toString())}::uuid, " +
                "${dec(r.ratio, 4)}, ${q(evidence)}, ${q(version)})"
        })
        appendLine("on conflict (from_exercise_id, to_exercise_id) do update")
        appendLine("  set ratio = excluded.ratio, evidence = excluded.evidence, catalog_version = excluded.catalog_version")
        appendLine("  where (fitness.exercise_transfer_ratios.ratio, fitness.exercise_transfer_ratios.evidence,")
        appendLine("         fitness.exercise_transfer_ratios.catalog_version)")
        appendLine("        is distinct from (excluded.ratio, excluded.evidence, excluded.catalog_version);")
    }

    private fun header(name: String, part: Int, of: Int, version: String, ex: Int, st: Int, tr: Int): String = buildString {
        appendLine("-- $name (M1a, data, part $part of $of): catalog $version — DATA_MODEL.md 9.2, 7.10, 13.2, 13.3")
        appendLine("--")
        appendLine("-- GENERATED by `gradlew :sim:run --args=\"seedgen-catalog $version\"` from")
        appendLine("-- algo/src/main/resources/fitness/catalog/catalog-$version.json (CAT-1 validated, C38 slugs present).")
        appendLine("-- $ex exercises, $st stimulus rows, $tr transfer ratios. Do not edit by hand. Apply the parts in order.")
        appendLine("-- Manual rollback (only with explicit user approval): delete the rows of catalog_version '$version'")
        appendLine("-- from exercise_transfer_ratios, exercise_stimulus (global rows), exercises (global rows), catalog_releases.")
        appendLine()
    }

    private fun arr(items: List<String>): String =
        if (items.isEmpty()) "'{}'::text[]" else "array[" + items.joinToString(", ") { q(it) } + "]::text[]"

    private fun dec(x: Double, scale: Int): String = BigDecimal(x).setScale(scale, RoundingMode.HALF_EVEN).toPlainString()

    private fun q(s: String): String = "'" + s.replace("'", "''") + "'"
}
