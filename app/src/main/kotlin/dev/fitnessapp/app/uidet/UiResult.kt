package dev.fitnessapp.app.uidet

import dev.fitnessapp.algo.core.det.Jcs
import kotlinx.serialization.json.JsonElement

/**
 * Envelope of every ui module result (UX 19.0): the value plus the module id, its version and the SHA-256
 * of the RFC 8785 form of the module's input, which the telemetry event that shows the result carries.
 */
data class UiResult<T>(val value: T, val moduleId: String, val moduleVersion: String, val inputsHash: String) {
    companion object {
        fun <T> of(value: T, moduleId: String, input: JsonElement, version: String = "1.0.0"): UiResult<T> =
            UiResult(value, moduleId, version, Jcs.sha256Hex(input))
    }
}
