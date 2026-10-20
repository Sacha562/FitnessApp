package dev.fitnessapp.algo.purity

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal

/**
 * CHALLENGE_ENGINE 12: no challenge constant is hardcoded in Kotlin. The challenge package may contain the
 * numbers 0 and 1, array indices and sizes, function arities, `ParamSpec` bounds (checked against the params
 * file by ParamsCoverageTest) and documented `const val` engineering facts; every tunable lives in the params
 * JSON or the definition.
 */
class ChallengeConstantsLintTest {
    private val root = File(requireNotNull(System.getProperty("fitness.repoRoot")) { "fitness.repoRoot not set" })

    private val number = Regex("""(?<![\w.])(\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)[LlFfDd]?(?![\w.])""")
    private val allowedLine = Regex("""\bconst val\b|\bParamSpec\.|\bParamSpec\(|\bFnSpec\(""")
    private val allowedContext = Regex("""\[\s*\d+\s*]|\.size\s*==\s*\d+""")

    fun violations(path: String, source: String): List<String> {
        val lines = PurityScanner.stripCommentsAndStrings(source).lines()
        val out = ArrayList<String>()
        lines.forEachIndexed { i, raw ->
            if (allowedLine.containsMatchIn(raw)) return@forEachIndexed
            val line = allowedContext.replace(raw, " ")
            for (m in number.findAll(line)) {
                val v = BigDecimal(m.groupValues[1])
                if (v.compareTo(BigDecimal.ZERO) == 0 || v.compareTo(BigDecimal.ONE) == 0) continue
                out += "$path:${i + 1}: ${m.value} in `${raw.trim()}`"
            }
        }
        return out
    }

    @Test
    fun `the challenge package hardcodes no constants`() {
        val dir = File(root, "algo/src/main/kotlin/dev/fitnessapp/algo/challenge")
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
        files.shouldNotBeEmpty()
        files.flatMap { violations(it.relativeTo(root).invariantSeparatorsPath, it.readText(Charsets.UTF_8)) }.shouldBeEmpty()
    }

    @Test
    fun `the lint catches literals and spares the allowed forms`() {
        val src = """
            private const val NEVER = 9999L
            fun f(a: List<Int>) = a[2] + 0 + 1.0 + (if (a.size == 2) 1 else 0)
            fun g() = 0.85 * 3
            // 42 in a comment
            val s = "7 in a string"
        """.trimIndent()
        violations("x.kt", src).map { it.substringAfter(": ").substringBefore(" in") } shouldBe listOf("0.85", "3")
    }
}
