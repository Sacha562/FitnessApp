package dev.fitnessapp.algo.purity

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** ANDROID.md 6.3 item 2: scans `:algo` main sources and the `:app` uidet package. */
class AlgoPurityTest {
    private val root = File(requireNotNull(System.getProperty("fitness.repoRoot")) { "fitness.repoRoot not set" })

    private fun sources(): List<File> {
        val algo = File(root, "algo/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
        val uidet = File(root, "app/src/main/kotlin/dev/fitnessapp/app/uidet").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
        return (algo + uidet).sortedBy { it.path }.toList()
    }

    @Test
    fun `algo and uidet sources obey the purity rules`() {
        val files = sources()
        files.shouldNotBeEmpty()
        val violations = files.flatMap { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            PurityScanner.scan(rel, f.readText(Charsets.UTF_8))
        }
        violations.map { it.toString() }.shouldBeEmpty()
    }

    @Test
    fun `the scanner catches every rule`() {
        val bad = """
            package dev.fitnessapp.algo.rx
            import android.util.Log
            import java.io.File
            import kotlin.math.ln
            import dev.fitnessapp.algo.sse.impl.Kalman
            var counter = 0
            object Holder {
                var hidden = 1
                val fine = 2
            }
            fun f(x: Double): String {
                val t = java.time.Instant.now()
                val r = Random(1)
                val m = HashMap<String, Int>()
                val y = Math.exp(x) + x.pow(2.0)
                val s = String.format("%d", 1)
                val u = "%.2f".format(x)
                val v = BigDecimal.valueOf(x)
                return Double.toString(x)
            }
        """.trimIndent()
        val rules = PurityScanner.scan("algo/src/main/kotlin/dev/fitnessapp/algo/rx/Bad.kt", bad).map { it.rule }.toSet()
        rules shouldBe setOf("P1", "P4/P6", "P5", "P16", "P7", "P2", "P3", "P6", "P8", "P13")
    }

    @Test
    fun `comments and strings are ignored and core det is exempt from P13`() {
        val ok = """
            package dev.fitnessapp.algo.core.det
            // Instant.now() in a comment
            /* HashMap in a block comment */
            val label = "Random( and Thread in a string"
            fun g(x: Double) = BigDecimal.valueOf(x)
        """.trimIndent()
        PurityScanner.scan("algo/src/main/kotlin/dev/fitnessapp/algo/core/det/Ok.kt", ok).shouldBeEmpty()
    }
}
