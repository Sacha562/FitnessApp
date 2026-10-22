package dev.fitnessapp.algo.core.calendar

/**
 * The one marker line of an app-written calendar event (C37, C68; ANDROID 16.5): `[fitapp:v1 <uuid> <slotKey>]`,
 * the last non-empty line of the event description. The classifier, the write planner and the app's executor all
 * render, parse and guard through this object, so detection, rendering and the write guard share one definition.
 * The pair (uuid, slot key) identifies one event: every event of one enrollment carries the same uuid.
 */
data class Marker(val uuid: String, val slotKey: String) {
    init {
        require(UUID_TEXT.matches(uuid)) { "marker uuid is not a lower-case uuid: $uuid" }
        require(SLOT_KEY.matches(slotKey)) { "invalid marker slot key: $slotKey" }
    }

    /** The exact line written as the last description line. */
    fun render(): String = "$PREFIX$uuid $slotKey]"

    /** The slot key's shape: a challenge slot, a general "Plan it" day, or a foreign key the app never edits. */
    fun parsedKey(): SlotKey = SlotKey.parse(slotKey)

    /** The provider selection argument of the atomic marker guard (ANDROID 16.7 guard 3), used with `LIKE ? ESCAPE '\'`. */
    fun likePattern(): String = "%" + likeEscape(render()) + "%"

    companion object {
        private const val PREFIX = "[fitapp:v1 "

        /** The C37 grammar, anchored (the same pattern as the `calendar_events.marker` CHECK, apart from the groups). */
        val PATTERN = Regex("^\\[fitapp:v1 ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) ([A-Za-z0-9_./-]{1,64})\\]$")
        private val UUID_TEXT = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val SLOT_KEY = Regex("^[A-Za-z0-9_./-]{1,64}$")

        /** The provider selection of Adopt (guard 5): a description without any fitapp marker. */
        const val NO_MARKER_LIKE = "%[fitapp:%"

        /** Parses one line (trimmed first); null when it is not a marker. */
        fun parseLine(line: String): Marker? {
            val m = PATTERN.matchEntire(line.trim()) ?: return null
            return Marker(m.groupValues[1], m.groupValues[2])
        }

        /** The marker of a description: its last non-empty line, or null. A marker on any other line does not count. */
        fun ofDescription(description: String?): Marker? {
            if (description == null) return null
            val last = description.lines().lastOrNull { it.isNotBlank() } ?: return null
            return parseLine(last)
        }

        /** True when the description contains any fitapp marker text (the Adopt guard refuses those). */
        fun containsAnyMarker(description: String?): Boolean = description != null && description.contains("[fitapp:")

        /** Escapes `\`, `%` and `_` for SQLite `LIKE ... ESCAPE '\'` (the slot-key class allows `_`). */
        fun likeEscape(text: String): String {
            val out = StringBuilder(text.length + 8)
            for (ch in text) {
                if (ch == '\\' || ch == '%' || ch == '_') out.append('\\')
                out.append(ch)
            }
            return out.toString()
        }

        /** `description` with its marker line replaced by [marker] (C36 slot-key rewrite) or appended after a blank line (Adopt). */
        fun withMarker(description: String?, marker: Marker): String {
            val text = description.orEmpty()
            val lines = text.lines()
            val lastIdx = lines.indexOfLast { it.isNotBlank() }
            if (lastIdx >= 0 && parseLine(lines[lastIdx]) != null) {
                return (lines.subList(0, lastIdx) + marker.render()).joinToString("\n")
            }
            return if (text.isBlank()) marker.render() else text.trimEnd() + "\n\n" + marker.render()
        }
    }
}

/** The two slot-key shapes of C37 and everything else (foreign: never edited). */
sealed interface SlotKey {
    /** `<definitionSlug>/<slot>/<isoYear>-W<ww>`, for example `oap/C/2026-W42`. */
    data class Challenge(val slug: String, val slot: String, val isoWeek: String) : SlotKey {
        fun render(): String = "$slug/$slot/$isoWeek"
    }

    /** `gen/<isoYear>-W<ww>/<isoDow>`, for example `gen/2026-W42/5`. */
    data class General(val isoWeek: String, val isoDow: Int) : SlotKey {
        fun render(): String = "gen/$isoWeek/$isoDow"
    }

    data class Foreign(val text: String) : SlotKey

    companion object {
        private val GENERAL = Regex("^gen/([0-9]{4}-W[0-9]{2})/([1-7])$")
        private val CHALLENGE = Regex("^([a-z0-9_-]+)/([A-Za-z0-9_]+)/([0-9]{4}-W[0-9]{2})$")

        fun parse(text: String): SlotKey {
            GENERAL.matchEntire(text)?.let { return General(it.groupValues[1], it.groupValues[2].toInt()) }
            CHALLENGE.matchEntire(text)?.let {
                if (it.groupValues[1] != "gen") return Challenge(it.groupValues[1], it.groupValues[2], it.groupValues[3])
            }
            return Foreign(text)
        }
    }
}
