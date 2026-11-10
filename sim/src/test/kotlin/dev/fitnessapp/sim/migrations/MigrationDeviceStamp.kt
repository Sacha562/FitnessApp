package dev.fitnessapp.sim.migrations

/**
 * Static check of the 046 rule (DATA_MODEL 13.2): a data migration that writes rows of a synced table must stamp
 * them with the migration device (`ffffffff-ffff-ffff-ffff-ffffffffffff`). The pull skips rows whose `device_id` is
 * the pulling device, so a row a migration changes but leaves on the user's device never reaches that phone
 * (065 left the owner's calf sets on the owner's phone's device id; 068 repaired them).
 *
 * Heuristic, statement by statement, after dropping comments, `create function` / `create procedure` bodies
 * (runtime code, not migration writes) and other dollar-quoted literals, and unwrapping `do $$ ... $$` blocks:
 *  - `update fitness.<synced>`: the SET list assigns `device_id` the migration device (the literal, or a variable
 *    declared with it). Statements limited to global rows (`user_id is null`) are exempt.
 *  - `insert into fitness.<synced>`: the column list names `device_id` and the statement uses the migration device.
 */
object MigrationDeviceStamp {
    const val MIGRATION_DEVICE = "ffffffff-ffff-ffff-ffff-ffffffffffff"

    data class Violation(val kind: String, val table: String, val statement: String)

    fun check(sql: String, syncedTables: Set<String>): List<Violation> {
        val flat = flatten(sql)
        val deviceVars = Regex("""\b([a-z_][a-z0-9_]*)\s+(?:constant\s+)?uuid\s*:=\s*'$MIGRATION_DEVICE'""")
            .findAll(flat).map { it.groupValues[1] }.toSet()
        val deviceValue = (listOf("'$MIGRATION_DEVICE'(?:::uuid)?") + deviceVars.map { Regex.escape(it) }).joinToString("|")
        val out = mutableListOf<Violation>()
        for (stmt in statements(flat)) {
            val s = stmt.replace(Regex("""\s+"""), " ").trim()
            Regex("""\bupdate\s+(?:only\s+)?fitness\.([a-z_]+)\b""").findAll(s).forEach { m ->
                val table = m.groupValues[1]
                if (table !in syncedTables) return@forEach
                val rest = stripParens(s.substring(m.range.last + 1))
                val setList = Regex("""^\s*(?:[a-z_][a-z0-9_]*\s+)?set\s+(.*?)(?:\bfrom\b|\bwhere\b|\breturning\b|$)""").find(rest)?.groupValues?.get(1) ?: ""
                // Exempt only when the target's own user_id is null (bare, or through its alias), not a joined table's.
                val alias = Regex("""^\s*([a-z_][a-z0-9_]*)\s+set\b""").find(rest)?.groupValues?.get(1)
                val own = listOfNotNull("(?<![a-z0-9_.])user_id", alias?.let { Regex.escape(it) + """\.user_id""" }).joinToString("|")
                if (Regex("""(?:$own)\s+is\s+null\b""").containsMatchIn(rest)) return@forEach
                if (!Regex("""(?:^|[\s,])device_id\s*=\s*(?:$deviceValue)(?=\s*(?:,|$))""").containsMatchIn(setList.trim())) {
                    out += Violation("update", table, s.take(200))
                }
            }
            Regex("""\binsert\s+into\s+fitness\.([a-z_]+)\s*\(([^)]*)\)""").findAll(s).forEach { m ->
                val table = m.groupValues[1]
                if (table !in syncedTables) return@forEach
                val cols = m.groupValues[2].split(',').map { it.trim() }
                val stamped = "device_id" in cols && Regex("""(?:$deviceValue)""").containsMatchIn(s.substring(m.range.last + 1))
                if (!stamped) out += Violation("insert", table, s.take(200))
            }
        }
        return out
    }

    /** Lower-cased SQL without comments; function bodies dropped, `do` bodies inlined, other dollar literals emptied. */
    internal fun flatten(sql: String): String {
        val sb = StringBuilder()
        var i = 0
        val src = sql.lowercase()
        while (i < src.length) {
            val c = src[i]
            when {
                src.startsWith("--", i) -> { while (i < src.length && src[i] != '\n') i++ }
                src.startsWith("/*", i) -> { val e = src.indexOf("*/", i + 2); i = if (e < 0) src.length else e + 2 }
                c == '\'' -> {
                    val start = i
                    i++
                    while (i < src.length) {
                        if (src[i] == '\'' && i + 1 < src.length && src[i + 1] == '\'') i += 2
                        else if (src[i] == '\'') { i++; break } else i++
                    }
                    sb.append(src, start, i)
                }
                c == '$' && Regex("""^\$([a-z_]*)\$""").find(src.substring(i))?.let { true } == true -> {
                    val tag = Regex("""^\$([a-z_]*)\$""").find(src.substring(i))!!.value
                    val bodyStart = i + tag.length
                    val end = src.indexOf(tag, bodyStart).let { if (it < 0) src.length else it }
                    val body = src.substring(bodyStart, end)
                    val head = sb.substring(sb.lastIndexOf(";") + 1)
                    when {
                        Regex("""\bcreate\s+(or\s+replace\s+)?(function|procedure)\b""").containsMatchIn(head) -> sb.append("''")
                        Regex("""\bdo\s*$""").containsMatchIn(head) -> sb.append(";").append(flatten(body)).append(";")
                        else -> sb.append("''")
                    }
                    i = (end + tag.length).coerceAtMost(src.length)
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /** Replaces every parenthesised group by `()`, so a subquery's `from` / `where` does not end a SET list. */
    internal fun stripParens(s: String): String {
        val sb = StringBuilder()
        var depth = 0
        var inStr = false
        for (ch in s) {
            if (ch == '\'' ) inStr = !inStr
            if (!inStr && ch == '(') { if (depth == 0) sb.append('('); depth++; continue }
            if (!inStr && ch == ')' && depth > 0) { depth--; if (depth == 0) sb.append(')'); continue }
            if (depth == 0) sb.append(ch)
        }
        return sb.toString()
    }

    /** Splits on `;` outside single-quoted strings. */
    internal fun statements(flat: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inStr = false
        for (ch in flat) {
            if (ch == '\'') inStr = !inStr
            if (ch == ';' && !inStr) { out += cur.toString(); cur.clear() } else cur.append(ch)
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out.filter { it.isNotBlank() }
    }
}
