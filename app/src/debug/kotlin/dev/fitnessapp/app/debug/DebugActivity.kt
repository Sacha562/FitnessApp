package dev.fitnessapp.app.debug

import android.content.res.AssetManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fitnessapp.algo.EngineVersion
import dev.fitnessapp.algo.golden.GoldenRunner
import dev.fitnessapp.app.ui.theme.FitnessTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Debug menu (debug and sandbox builds only). "Run determinism self-test" runs every bundled golden
 * fixture through :algo on ART and compares the output hash with the one :algo:test produced on the JVM
 * (ANDROID.md 6.3 item 4, guarantee R2). Results go to logcat with the tag DeterminismSelfTest:
 *   adb shell am start -n dev.fitnessapp/dev.fitnessapp.app.debug.DebugActivity --es action selftest
 *   adb logcat -d -s DeterminismSelfTest
 */
class DebugActivity : ComponentActivity() {
    private val report = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getStringExtra("action") == "selftest") runSelfTest()
        setContent {
            FitnessTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Debug menu", style = MaterialTheme.typography.headlineSmall)
                        Button(onClick = ::runSelfTest) { Text("Run determinism self-test") }
                        Text(report.value, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    private fun runSelfTest() {
        val lines = DeterminismSelfTest.run(assets)
        lines.forEach { Log.i(TAG, it) }
        report.value = lines.joinToString("\n")
    }

    private companion object {
        const val TAG = "DeterminismSelfTest"
    }
}

object DeterminismSelfTest {
    fun run(assets: AssetManager): List<String> {
        val names = assets.list("algo").orEmpty().filter { it.endsWith(".json") }.sorted()
        var failures = 0
        val lines = names.map { name ->
            try {
                val text = assets.open("algo/$name").use { it.readBytes().toString(Charsets.UTF_8) }
                val fixture = Json.parseToJsonElement(text).jsonObject
                val expected = fixture.getValue("expected").jsonObject.getValue("outputs_jcs_sha256").jsonPrimitive.content
                val actual = GoldenRunner.run(fixture).outputsSha256
                if (actual == expected) "ok   ${name.removeSuffix(".json")} $actual"
                else { failures++; "FAIL ${name.removeSuffix(".json")} expected $expected got $actual" }
            } catch (e: Exception) {
                failures++
                "FAIL $name ${e::class.simpleName}"
            }
        }
        val summary = "RESULT ${if (failures == 0 && names.isNotEmpty()) "PASS" else "FAIL"} " +
            "${names.size - failures}/${names.size} engine ${EngineVersion.ENGINE_VERSION}"
        return lines + summary
    }
}
