package dev.fitnessapp.data.db

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

/** ANDROID.md 27.3: Room must never fall back to a destructive migration; a lost local DB loses unsynced sets. */
class NoDestructiveMigrationTest {
    @Test
    fun noDestructiveFallbackInMainSources() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/") }
            .filter { "fallbackToDestructiveMigration" in it.readText() }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        offenders.shouldBeEmpty()
    }
}
