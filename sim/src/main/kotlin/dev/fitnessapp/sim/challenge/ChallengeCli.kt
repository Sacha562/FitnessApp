package dev.fitnessapp.sim.challenge

import dev.fitnessapp.algo.challenge.cds.LoadResult
import java.io.File

/**
 * `sim challenge validate <definition.json>` and
 * `sim challenge simulate [--definition <file>] [--persona <name|all>] [--weeks 26] [--proposals approved|declined|both] [--out <dir>]`
 * (CHALLENGE_ENGINE 16). Replay and diff of exported events arrive with the review tooling (M5).
 */
object ChallengeCli {
    fun run(args: List<String>): Int = when (args.firstOrNull()) {
        "validate" -> validate(args.getOrNull(1) ?: return usage())
        "simulate" -> simulate(args.drop(1))
        else -> usage()
    }

    private fun usage(): Int {
        println("usage: sim challenge validate <definition.json>")
        println("       sim challenge simulate [--definition <file>] [--persona <name|all>] [--weeks 26] [--proposals approved|declined|both] [--out <dir>]")
        return 2
    }

    private fun validate(path: String): Int {
        val r = SimResources.load(File(path).readText(Charsets.UTF_8))
        return when (r) {
            is LoadResult.Ok -> {
                r.warnings.forEach { println(it) }
                println("ok ${r.definition.slug}@${r.definition.version} jcs_sha256 ${r.definition.jcsSha256} (${r.definition.rules.size} rules, ${r.warnings.size} warnings)")
                0
            }
            is LoadResult.Err -> {
                r.errors.forEach { println(it) }
                println("invalid: ${r.errors.count { !it.warning }} errors")
                1
            }
        }
    }

    private fun simulate(args: List<String>): Int {
        val opts = args.chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
        val def = opts["definition"]?.let { SimResources.load(File(it).readText(Charsets.UTF_8)).orThrow() } ?: SimResources.oap
        val personas = opts["persona"].let { if (it == null || it == "all") Persona.all() else listOf(Persona.byId(it)) }
        val weeks = opts["weeks"]?.toInt() ?: 26
        val decisions = when (opts["proposals"] ?: "both") {
            "approved" -> listOf(true)
            "declined" -> listOf(false)
            else -> listOf(true, false)
        }
        val out = opts["out"]?.let(::File)
        var bad = 0
        for (p in personas) for (approve in decisions) {
            val params = SimResources.params()
            val r = ChallengeSimulator(def, params, SimResources.catalog, p, approve, weeks).run()
            val tag = if (approve) "approved" else "declined"
            println("${p.id}.$tag: ${r.events.size} events, ${r.audits.size} audit rows, final mode ${r.finalState.mode}, phase ${r.finalState.phase}, block ${r.finalState.block.index}/${r.finalState.block.week}, ${r.violations.size} violations")
            r.violations.take(10).forEach { println("  ! $it") }
            if (r.violations.isNotEmpty()) bad++
            out?.let {
                it.mkdirs()
                File(it, "${p.id}.$tag.json").writeText(Trajectory.text(def, params, r), Charsets.UTF_8)
            }
        }
        return if (bad == 0) 0 else 1
    }
}
