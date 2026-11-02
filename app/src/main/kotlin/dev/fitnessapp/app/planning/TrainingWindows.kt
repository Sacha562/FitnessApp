package dev.fitnessapp.app.planning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalTime

/**
 * The user's usual training times, `profiles.availability` (DATA_MODEL 9.3.1): weekday -> [[from, to]], one window a
 * day. `{}` means not set yet: the planner then uses [DEFAULT] (D75), never the whole opening day. "Any time" is stored
 * explicitly as [ANY_TIME], which the gym's opening hours then narrow.
 */
object TrainingWindows {
    val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    /** UX 8.11 default windows: weekdays 19:45-21:15, weekends 09:00-16:00 (planning lens facts, editable). */
    val DEFAULT: Map<String, Pair<String, String>> = DAYS.associateWith { d -> if (d == "sat" || d == "sun") "09:00" to "16:00" else "19:45" to "21:15" }

    /** "Any time the gym is open": every day, the whole day. */
    val ANY_TIME: Map<String, Pair<String, String>> = DAYS.associateWith { "00:00" to "23:59" }

    fun parse(json: String?): Map<String, Pair<String, String>> {
        val o = runCatching { Json.parseToJsonElement(json ?: "{}").jsonObject }.getOrNull() ?: return emptyMap()
        return o.mapNotNull { (d, v) ->
            val w = runCatching { v.jsonArray.first().jsonArray }.getOrNull() ?: return@mapNotNull null
            d to (w[0].jsonPrimitive.content to w[1].jsonPrimitive.content)
        }.toMap()
    }

    fun json(w: Map<String, Pair<String, String>>): String = JsonObject(
        DAYS.mapNotNull { d -> w[d]?.let { (a, b) -> d to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(a), JsonPrimitive(b))))) } }.toMap(),
    ).toString()

    /** Whether the user has saved times (or "Any time"); false while the default applies. */
    fun isSet(json: String?): Boolean = parse(json).isNotEmpty()

    /** The windows the planner uses (D75): the saved ones, else [DEFAULT]. */
    fun effective(json: String?): Map<String, Pair<LocalTime, LocalTime>> =
        parse(json).ifEmpty { DEFAULT }.mapValues { (_, w) -> LocalTime.parse(w.first) to LocalTime.parse(w.second) }

    fun summary(json: String?): String {
        val w = parse(json)
        if (w.isEmpty()) return "Not set: weekday evenings 19:45-21:15, weekends 09:00-16:00"
        if (w == ANY_TIME) return "Any time the gym is open"
        return DAYS.filter { it in w }.joinToString(" · ") { d -> d.replaceFirstChar(Char::uppercase) + " " + w.getValue(d).let { "${it.first}-${it.second}" } }
    }
}
