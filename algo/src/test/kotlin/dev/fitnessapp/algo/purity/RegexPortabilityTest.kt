package dev.fitnessapp.algo.purity

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Android compiles regular expressions with ICU, which rejects a literal `}` or `]` that the JVM accepts (a
 * placeholder pattern `\{(\w+)}` failed at app start on the phone, M2b.3). Every regex literal in the `:algo`,
 * `:data` and `:app` main sources must escape them.
 */
class RegexPortabilityTest {
    private val root = File(requireNotNull(System.getProperty("fitness.repoRoot")) { "fitness.repoRoot not set" })

    /** `Regex("...")`, `Regex("""...""")` and `Pattern.compile("...")` literals. */
    private val literal = Regex("(?:Regex\\(|Pattern\\.compile\\()\\s*(\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\")")

    private fun patterns(text: String): List<String> = literal.findAll(text).map { m ->
        m.groups[2]?.value ?: unescapeKotlin(m.groups[3]!!.value)
    }.toList()

    private fun unescapeKotlin(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                append(if (s[i + 1] == '\\') '\\' else s[i + 1].let { n -> if (n == '"' || n == '$') n else { append('\\'); n } })
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    /** The literal `}` and `]` of [p] that are not escaped (outside a class and a `{n,m}` quantifier). */
    fun unescaped(p: String): List<Int> {
        val out = ArrayList<Int>()
        var inClass = false
        var classStart = -1
        var quant = false
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when {
                // \p{..} and \P{..} name a property; \x{..} a code point.
                c == '\\' && p.getOrNull(i + 1) in setOf('p', 'P', 'x') && p.getOrNull(i + 2) == '{' -> i = p.indexOf('}', i).let { if (it < 0) p.length else it }
                c == '\\' -> i++
                inClass && c == ']' && i > classStart + 1 + (if (p.getOrNull(classStart + 1) == '^') 1 else 0) -> inClass = false
                inClass -> {}
                c == '[' -> { inClass = true; classStart = i }
                c == '{' -> quant = Regex("^\\{\\d+(,\\d*)?\\}").containsMatchIn(p.substring(i))
                c == '}' -> if (quant) quant = false else out += i
                c == ']' -> out += i
            }
            i++
        }
        return out
    }

    @Test
    fun `regex literals escape braces and brackets for ICU`() {
        val files = listOf("algo", "data", "app").flatMap { m ->
            File(root, "$m/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        files.shouldNotBeEmpty()
        val bad = files.flatMap { f ->
            patterns(f.readText(Charsets.UTF_8)).filter { unescaped(it).isNotEmpty() }.map { "${f.relativeTo(root).invariantSeparatorsPath}: $it" }
        }
        bad.shouldBeEmpty()
    }

    @Test
    fun `the check finds the patterns ICU rejects`() {
        unescaped("\\{([A-Za-z_][A-Za-z0-9_.]*)}") shouldBe listOf(27) // ICU: "near index 28"
        unescaped("\\[(\\d+)]") shouldBe listOf(7)
        unescaped("\\{([A-Za-z_][A-Za-z0-9_.]*)\\}") shouldBe emptyList()
        unescaped("^[a-z][a-z0-9_]{0,63}$") shouldBe emptyList()
        unescaped("[]a]x") shouldBe emptyList()
        unescaped("\\p{M}+") shouldBe emptyList()
        patterns("val r = Regex(\"\\\\{(\\\\w+)}\")") shouldBe listOf("\\{(\\w+)}")
    }
}
