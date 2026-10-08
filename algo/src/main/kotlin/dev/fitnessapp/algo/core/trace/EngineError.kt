package dev.fitnessapp.algo.core.trace

/**
 * Typed module errors (ALGORITHMS.md P14): a module that cannot produce a valid output returns one of these
 * instead of throwing or emitting NaN. The gateway logs them; they never reach the UI as exceptions.
 */
sealed class EngineError(val code: String) {
    data class InvalidInput(val detail: String) : EngineError("invalid_input")
    data class NotLoadable(val detail: String) : EngineError("not_loadable")
}

/** Result or [EngineError] (A6 `Outcome`). */
sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Err(val error: EngineError) : Outcome<Nothing>()

    fun getOrNull(): T? = (this as? Ok)?.value
}
