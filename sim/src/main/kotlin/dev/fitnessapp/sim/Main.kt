package dev.fitnessapp.sim

import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.golden.GoldenRunner
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
        "seedgen" -> {
            val parts = SeedGen.generate(File("algo/src/main/resources/fitness"), args.getOrElse(1) { "1.0.0" })
            for ((name, sql) in parts) {
                val out = File("supabase/migrations/pending/$name.sql")
                out.writeText(sql, Charsets.UTF_8)
                println("wrote ${out.path} (${sql.length} chars)")
            }
        }
        else -> {
            println("usage: sim version | sim golden [fixtures-dir] | sim seedgen [params-version]")
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
