package dev.fitnessapp.sim

import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.golden.GoldenRunner
import dev.fitnessapp.sim.challenge.ChallengeCli
import dev.fitnessapp.sim.seedgen.CatalogSeedGen
import dev.fitnessapp.sim.seedgen.ChallengeSeedGen
import dev.fitnessapp.sim.seedgen.SeedGen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.system.exitProcess

/**
 * Simulation and replay CLI (PLAN.md section 10). M0 ships the `golden` command, which runs the golden
 * fixtures through the same `:algo` code the phone runs and prints each output hash. Replay,
 * counterfactuals, personas and seedgen arrive in later milestones.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "version" -> println("engine ${EngineVersion.ENGINE_VERSION}")
        "golden" -> exitProcess(golden(File(args.getOrElse(1) { "fixtures/golden/algo" })))
        "challenge" -> exitProcess(ChallengeCli.run(args.drop(1)))
        "seedgen" -> {
            val parts = SeedGen.generate(File("algo/src/main/resources/fitness"), args.getOrElse(1) { "1.0.1" })
            for ((name, sql) in parts) {
                val out = File("supabase/migrations/pending/$name.sql")
                out.writeText(sql, Charsets.UTF_8)
                println("wrote ${out.path} (${sql.length} chars)")
            }
        }
        "seedgen-catalog" -> {
            val version = args.getOrElse(1) { "1.0.1" }
            val result = CatalogSeedGen.generate(
                File("algo/src/main/resources/fitness"), listOf(File("docs/challenges/oap.challenge.v1.json")), version,
            )
            for ((name, sql) in result.parts) {
                val out = File("supabase/migrations/pending/$name.sql")
                out.writeText(sql, Charsets.UTF_8)
                println("wrote ${out.path} (${sql.length} chars)")
            }
            println("catalog $version: ${result.exercises} exercises, ${result.stimulus} stimulus rows, " +
                "${result.ratios} transfer ratios, jcs_sha256 ${result.jcsSha256}")
        }
        "seedgen-challenge" -> {
            val file = File(args.getOrElse(1) { "docs/challenges/oap.challenge.v1.json" })
            val text = file.readText(Charsets.UTF_8)
            val def = dev.fitnessapp.sim.challenge.SimResources.load(text).orThrow()
            val result = ChallengeSeedGen.generate(def, Json.parseToJsonElement(text).jsonObject, args.getOrElse(2) { "fitness_016_oap_definition_v1" })
            for ((name, sql) in result.parts) {
                val out = File("supabase/migrations/pending/$name.sql")
                out.parentFile.mkdirs()
                out.writeText(sql, Charsets.UTF_8)
                println("wrote ${out.path} (${sql.length} chars)")
            }
            println("${def.slug}@${def.version}: id ${result.id}, ${result.bytes} bytes, jcs_sha256 ${result.jcsSha256}, server_checksum ${result.serverChecksum}")
        }
        else -> {
            println("usage: sim version | sim golden [fixtures-dir] | sim seedgen [params-version] | sim seedgen-catalog [catalog-version] | sim seedgen-challenge [definition-file] [migration-name] | sim challenge validate|simulate ...")
            exitProcess(2)
        }
    }
}

private fun golden(dir: File): Int {
    val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
    var failures = 0
    for (file in files) {
        val fixture = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        val expected = fixture["expected"]?.jsonObject?.get("outputs_jcs_sha256")?.jsonPrimitive?.content
        val result = GoldenRunner.run(fixture)
        val ok = expected == result.outputsSha256
        if (!ok) failures++
        println("${if (ok) "ok  " else "FAIL"} ${file.nameWithoutExtension} ${result.outputsSha256}")
    }
    println("${files.size - failures}/${files.size} golden fixtures match (engine ${EngineVersion.ENGINE_VERSION})")
    return if (failures == 0) 0 else 1
}
