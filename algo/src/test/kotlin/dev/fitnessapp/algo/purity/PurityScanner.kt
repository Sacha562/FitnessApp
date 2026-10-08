package dev.fitnessapp.algo.purity

/**
 * Line-based source scanner for the purity rules of ANDROID.md 6.2/6.3 (P1-P8, P11, P13, P16).
 * Comments and string literals are blanked out first (keeping line numbers), except for the raw-line
 * P13 check of floating-point `.format(` conversions. There are no escape comments: changing a rule
 * means editing this file, which is visible in review.
 */
object PurityScanner {
    data class Violation(val path: String, val line: Int, val rule: String, val text: String) {
        override fun toString(): String = "$path:$line [$rule] ${text.trim()}"
    }

    private class Rule(val id: String, val regex: Regex, val exemptDet: Boolean = false, val needsNoLocaleRoot: Boolean = false)

    private const val TRANSCENDENTAL =
        "exp|ln|log|log10|log2|ln1p|log1p|expm1|pow|sin|cos|tan|asin|acos|atan|atan2|sinh|cosh|tanh|cbrt|hypot"

    private val RULES = listOf(
        Rule("P1", Regex("""^\s*import\s+(android|androidx|com\.google\.android)\.""")),
        Rule("P2/P4", Regex("""System\.(currentTimeMillis|nanoTime|getenv|getProperty)\b""")),
        Rule("P2", Regex("""\b(Instant|LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|Year|YearMonth)\.now\s*\(""")),
        Rule("P2", Regex("""\bClock\.system\w*\(|\bSystemClock\b""")),
        Rule("P3", Regex("""\bjava\.util\.Random\b|\bkotlin\.random\b|\bRandom\s*\(|Math\.random|SecureRandom|UUID\.randomUUID|SplittableRandom""")),
        Rule("P4/P6", Regex("""^\s*import\s+(java\.io|java\.nio\.file|java\.net|kotlinx\.coroutines|java\.util\.concurrent)\b""")),
        Rule("P4", Regex("""\bThread\b|Locale\.getDefault|TimeZone\.getDefault|ZoneId\.systemDefault""")),
        Rule("P5", Regex("""\b(Math|kotlin\.math)\.($TRANSCENDENTAL)\s*\(""")),
        Rule("P5", Regex("""^\s*import\s+kotlin\.math\.(\*|($TRANSCENDENTAL)\b)""")),
        // Double.pow / Int.pow extension calls; StrictMath.pow is the sanctioned route (Det.pow).
        Rule("P5", Regex("""(?<!\bStrictMath)\.pow\s*\(""")),
        Rule("P6", Regex("""\b(HashMap|HashSet|hashMapOf|hashSetOf)\b""")),
        Rule("P7", Regex("""^((private|internal|public)\s+)?(lateinit\s+)?var\s""")),
        Rule("P8", Regex("""String\.format\s*\(|\.format\s*\("""), needsNoLocaleRoot = true),
        Rule("P11", Regex("""\.parallelStream\s*\(|\.parallel\s*\(\s*\)|\bparallelSort\b""")),
        Rule("P13", Regex("""\bDouble\.toString\s*\(|\bjava\.lang\.Double\.toString\b|\bString\.valueOf\s*\("""), exemptDet = true),
        Rule("P13", Regex("""\bBigDecimal\.valueOf\s*\(|\.toBigDecimal\s*\("""), exemptDet = true),
    )

    private val RAW_FLOAT_FORMAT = Regex("""\.format\s*\(.*%[-#+ 0,(]*\d*(\.\d+)?[eEfgGaA]|"[^"]*%[-#+ 0,(]*\d*(\.\d+)?[eEfgGaA][^"]*"\s*\.format\s*\(""")
    private val IMPL_IMPORT = Regex("""^\s*import\s+dev\.fitnessapp\.algo\.(\S+?)\.impl\.""")
    private val PACKAGE = Regex("""^\s*package\s+(\S+)""")
    private val OBJECT_OPEN = Regex("""\bobject\b[^{]*\{""")
    private val VAR_DECL = Regex("""^\s*((private|internal|public|protected)\s+)?(lateinit\s+)?var\s""")

    /** [path] is relative and uses '/' separators; it decides the core/det exemption. */
    fun scan(path: String, source: String): List<Violation> {
        val rawLines = source.lines()
        val lines = stripCommentsAndStrings(source).lines()
        val inDet = path.contains("/core/det/")
        val pkg = lines.firstNotNullOfOrNull { PACKAGE.find(it)?.groupValues?.get(1) }.orEmpty()
        val out = mutableListOf<Violation>()

        lines.forEachIndexed { i, line ->
            for (rule in RULES) {
                if (rule.exemptDet && inDet) continue
                if (!rule.regex.containsMatchIn(line)) continue
                if (rule.needsNoLocaleRoot && rawLines[i].contains("Locale.ROOT")) continue
                out += Violation(path, i + 1, rule.id, rawLines[i])
            }
            if (!inDet && RAW_FLOAT_FORMAT.containsMatchIn(rawLines[i])) {
                out += Violation(path, i + 1, "P13", rawLines[i])
            }
            IMPL_IMPORT.find(line)?.let { m ->
                val owner = "dev.fitnessapp.algo." + m.groupValues[1]
                if (pkg != owner && !pkg.startsWith("$owner.")) out += Violation(path, i + 1, "P16", rawLines[i])
            }
        }
        out += varsInObjects(path, lines, rawLines)
        return out
    }

    /** P7: `var` declared directly inside an `object` body (including companion objects). */
    private fun varsInObjects(path: String, lines: List<String>, rawLines: List<String>): List<Violation> {
        val out = mutableListOf<Violation>()
        var depth = 0
        val objectDepths = ArrayDeque<Int>()
        lines.forEachIndexed { i, line ->
            if (objectDepths.isNotEmpty() && depth == objectDepths.last() + 1 && VAR_DECL.containsMatchIn(line)) {
                out += Violation(path, i + 1, "P7", rawLines[i])
            }
            val objectOpenAt = OBJECT_OPEN.find(line)?.range?.last
            line.forEachIndexed { col, c ->
                if (c == '{') {
                    if (col == objectOpenAt) objectDepths.addLast(depth)
                    depth++
                } else if (c == '}') {
                    depth--
                    if (objectDepths.isNotEmpty() && objectDepths.last() == depth) objectDepths.removeLast()
                }
            }
        }
        return out
    }

    /** Replaces comments, string literals and char literals by spaces; newlines are kept. */
    fun stripCommentsAndStrings(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        fun blank(c: Char) = if (c == '\n') '\n' else ' '
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    while (i < src.length && src[i] != '\n') { sb.append(' '); i++ }
                }
                c == '/' && next == '*' -> {
                    var nesting = 0
                    while (i < src.length) {
                        if (src.startsWith("/*", i)) { nesting++; sb.append("  "); i += 2; continue }
                        if (src.startsWith("*/", i)) { nesting--; sb.append("  "); i += 2; if (nesting == 0) break; continue }
                        sb.append(blank(src[i])); i++
                    }
                }
                src.startsWith("\"\"\"", i) -> {
                    sb.append("\"\"\""); i += 3
                    while (i < src.length && !src.startsWith("\"\"\"", i)) { sb.append(blank(src[i])); i++ }
                    if (i < src.length) { sb.append("\"\"\""); i += 3 }
                }
                c == '"' -> {
                    sb.append('"'); i++
                    while (i < src.length && src[i] != '"' && src[i] != '\n') {
                        if (src[i] == '\\' && i + 1 < src.length) { sb.append("  "); i += 2 } else { sb.append(' '); i++ }
                    }
                    if (i < src.length && src[i] == '"') { sb.append('"'); i++ }
                }
                c == '\'' -> {
                    sb.append('\''); i++
                    while (i < src.length && src[i] != '\'' && src[i] != '\n') {
                        if (src[i] == '\\' && i + 1 < src.length) { sb.append("  "); i += 2 } else { sb.append(' '); i++ }
                    }
                    if (i < src.length && src[i] == '\'') { sb.append('\''); i++ }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
