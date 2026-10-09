package dev.fitnessapp.data.sync

/** Sync platform constants (ANDROID.md 25.4, C54); part of `PlatformConfig`, outside `params_hash`. */
data class SyncConfig(
    val pushBatchRows: Int = 200,
    val pullPageRows: Int = 500,
    val overlapS: Int = 120,
    val debounceS: Int = 3,
    val backoffInitialS: Int = 30,
    val periodicH: Int = 6,
    val fkRetryAttempts: Int = 5,
    val schemaDriftRetryMin: Int = 60,
    val unreachableBannerH: Int = 24,
    /** UX 7.1 "Pending long": an outbox entry waiting this long while online is shown. */
    val pendingLongH: Int = 24,
)
