package dev.fitnessapp.algo.testing

import dev.fitnessapp.algo.core.params.ParamView

/** The bundled defaults (params 1.0.7) as a fresh [ParamView] per call. */
object TestParams {
    val text: String = requireNotNull(TestParams::class.java.getResource("/fitness/params/default-params-1.0.7.json")) {
        "bundled params file missing"
    }.readText(Charsets.UTF_8)

    private val base = ParamView.fromParamsFile(text)

    fun defaults(): ParamView = base.fork()
}
