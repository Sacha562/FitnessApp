package dev.fitnessapp.data.sync

/** Error classes of ANDROID.md 9.5 / DATA_MODEL 11.8. [code] is the `sync_error.error_class` value. */
enum class SyncErrorClass(val code: String) {
    TRANSIENT("transient"),
    AUTH("auth"),
    SCHEMA_NOT_EXPOSED("schema_not_exposed"),
    SCHEMA_DRIFT("schema_drift"),
    ACCESS("access"),
    FK_ORDER("fk_order"),
    ROW_PERMANENT("row_permanent"),
    SERVER_READ_ONLY("server_read_only"),
    PAYLOAD_TOO_LARGE("payload_too_large"),
}

/** A failed request as seen by the classifier; [network] = no HTTP response at all. */
data class SyncFailure(val httpStatus: Int?, val code: String?, val network: Boolean = false, val message: String? = null)

object ErrorClassifier {
    fun classify(f: SyncFailure): SyncErrorClass {
        if (f.network || f.httpStatus == null) return SyncErrorClass.TRANSIENT
        val code = f.code.orEmpty()
        return when {
            code == "PGRST106" -> SyncErrorClass.SCHEMA_NOT_EXPOSED
            code in setOf("PGRST204", "42703", "PGRST205", "42P01") -> SyncErrorClass.SCHEMA_DRIFT
            code == "25006" -> SyncErrorClass.SERVER_READ_ONLY
            code == "57014" -> SyncErrorClass.TRANSIENT
            code == "42501" -> SyncErrorClass.ACCESS
            code == "23503" -> SyncErrorClass.FK_ORDER
            code in setOf("23505", "23514", "23502") || code.startsWith("22") -> SyncErrorClass.ROW_PERMANENT
            code in setOf("PGRST301", "PGRST303") || f.httpStatus == 401 -> SyncErrorClass.AUTH
            f.httpStatus == 413 -> SyncErrorClass.PAYLOAD_TOO_LARGE
            f.httpStatus == 408 || f.httpStatus == 429 || f.httpStatus >= 500 -> SyncErrorClass.TRANSIENT
            f.httpStatus == 403 -> SyncErrorClass.ACCESS
            f.httpStatus == 400 || f.httpStatus == 409 -> SyncErrorClass.ROW_PERMANENT
            else -> SyncErrorClass.TRANSIENT
        }
    }
}
