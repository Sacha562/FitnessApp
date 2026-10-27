package dev.fitnessapp.algo.core.trace

/**
 * One step of a module's decision trace (ALGORITHMS.md A6): a stable dotted code such as `sse.skip.n_eff` and the
 * entity it concerns (a set or exercise id). Numbers belong in the module's outputs, not here.
 */
data class TraceStep(val code: String, val entity: String? = null)
