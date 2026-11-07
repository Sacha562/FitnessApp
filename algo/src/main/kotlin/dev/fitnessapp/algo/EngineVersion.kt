package dev.fitnessapp.algo

/**
 * The engine version (C30, ALGORITHMS.md A7). Independent of the app version; bumped by the largest
 * module bump of a release. Params-only changes never bump it (they change params_hash).
 */
object EngineVersion {
    const val ENGINE_VERSION: String = "1.26.0"
}
