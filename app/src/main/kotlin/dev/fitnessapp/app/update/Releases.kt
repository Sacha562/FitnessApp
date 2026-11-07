package dev.fitnessapp.app.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** A published GitHub release that carries an installable APK (D98 (1)). */
data class Release(
    val tag: String,
    /** The tag without its `v`, for example `0.0.170`. */
    val versionName: String,
    /** The tag's last number: the release build's `versionCode` (ANDROID.md 5.5). */
    val versionCode: Int,
    /** The release notes as plain text (markdown markers removed). */
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 GitHub lists for the asset, null when it lists none. */
    val sha256: String?,
)

/** Parsing and comparison of the GitHub `/releases/latest` answer (D98), pure for tests. */
object Releases {
    private val TAG = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val json = Json { ignoreUnknownKeys = true }

    /** The version code a tag `v0.<milestone>.<versionCode>` stands for, null for any other tag. */
    fun versionCode(tag: String): Int? = TAG.matchEntire(tag.trim())?.groupValues?.get(3)?.toIntOrNull()

    /**
     * The release of a `/releases/latest` body, or null when it is a draft or pre-release, its tag does not follow
     * D98 (1), or it has no HTTPS `.apk` asset (the first by name when there are several).
     */
    fun parseLatest(body: String): Release? {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if (o.bool("draft") || o.bool("prerelease")) return null
        val tag = o.str("tag_name")?.trim() ?: return null
        val code = versionCode(tag) ?: return null
        val asset = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { a -> a.str("name")?.endsWith(".apk", ignoreCase = true) == true && a.str("browser_download_url")?.startsWith("https://") == true }
            .minByOrNull { it.str("name").orEmpty() } ?: return null
        val digest = asset.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()?.takeIf(HEX64::matches)
        return Release(
            tag = tag,
            versionName = tag.removePrefix("v"),
            versionCode = code,
            notes = plainNotes(o.str("body").orEmpty()),
            apkUrl = asset.str("browser_download_url")!!,
            apkName = asset.str("name")!!,
            apkSize = (asset["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
            sha256 = digest,
        )
    }

    /** Whether [release] should be offered over the installed [installedCode]. */
    fun isNewer(release: Release?, installedCode: Int): Boolean = release != null && release.versionCode > installedCode

    /** Version code of a downloaded `FitnessApp-<code>.apk` (or `.part`), null for other names. */
    fun cachedCode(fileName: String): Int? = Regex("""^FitnessApp-(\d+)\.apk(\.part)?$""").matchEntire(fileName)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Release notes markdown as plain text for the prompt: code blocks dropped, headings, emphasis and backticks
     * removed, links reduced to their text, bullets shown as "•", at most one blank line in a row, at most
     * [maxChars] characters.
     */
    fun plainNotes(markdown: String, maxChars: Int = 4_000): String {
        val out = mutableListOf<String>()
        var fence = false
        for (raw in markdown.replace("\r\n", "\n").split('\n')) {
            if (raw.trimStart().startsWith("```")) {
                fence = !fence
                continue
            }
            if (fence) continue
            var l = raw.trimEnd()
            l = l.replace(Regex("^#{1,6}\\s+"), "")
            l = l.replace(Regex("^(\\s*)[-*]\\s+"), "$1• ")
            l = LINK.replace(l) { it.groupValues[1] }
            l = l.replace("**", "").replace("__", "").replace("`", "")
            if (l.isBlank() && (out.isEmpty() || out.last().isBlank())) continue
            out += l
        }
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.lastIndex)
        val text = out.joinToString("\n")
        return if (text.length <= maxChars) text else text.take(maxChars - 1).trimEnd() + "…"
    }

    /** "5.0 MB" for an APK size; empty when the size is unknown. */
    fun sizeLabel(bytes: Long): String = if (bytes <= 0) "" else String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull == true
}
