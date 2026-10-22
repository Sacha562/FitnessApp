package dev.fitnessapp.algo.core.calendar

import dev.fitnessapp.algo.core.det.Sha256Hex
import java.text.Normalizer
import java.util.Locale

/**
 * Normalisation `N(s)` of ALGORITHMS_PLANNING 1.4, identical for titles, descriptions, locations, calendar names
 * and keyword lists: NFKD without combining marks, lower case (`Locale.ROOT`), the Unicode dashes as `-`, and
 * every whitespace run as one space. `"Gym — sessie A (één arm zwaar)"` becomes `"gym - sessie a (een arm zwaar)"`.
 */
object CalendarText {
    private val DASHES = charArrayOf('‐', '‑', '‒', '–', '—', '―', '−')

    fun normalize(s: String?): String {
        if (s == null) return ""
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        var space = false
        for (ch in decomposed) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                space = true
                continue
            }
            if (space && out.isNotEmpty()) out.append(' ')
            space = false
            out.append(if (ch in DASHES) '-' else ch)
        }
        return out.toString().lowercase(Locale.ROOT)
    }
}

/**
 * The instance and series keys of DATA_MODEL 9.7.3: SHA-256 hex of
 * `account_type|account_name|calendar_sync_id|event_sync_id (or _ID)|instance_begin_ms`, and the same without the
 * begin for the whole series. Stable across devices for Google-synced events; no readable personal data.
 */
object CalendarKeys {
    fun instanceKey(accountType: String, accountName: String, calendarSyncId: String, eventSyncId: String, beginUtcMs: Long): String =
        Sha256Hex.ofUtf8(listOf(accountType, accountName, calendarSyncId, eventSyncId, beginUtcMs.toString()).joinToString("|"))

    fun seriesKey(accountType: String, accountName: String, calendarSyncId: String, eventSyncId: String): String =
        Sha256Hex.ofUtf8(listOf(accountType, accountName, calendarSyncId, eventSyncId).joinToString("|"))

    /** `sha256(ACCOUNT_NAME|ACCOUNT_TYPE|OWNER_ACCOUNT)`: the calendar identity that logs and synced rows carry (ANDROID 16.3). */
    fun calendarIdentity(accountName: String, accountType: String, ownerAccount: String): String =
        Sha256Hex.ofUtf8(listOf(accountName, accountType, ownerAccount).joinToString("|"))
}
