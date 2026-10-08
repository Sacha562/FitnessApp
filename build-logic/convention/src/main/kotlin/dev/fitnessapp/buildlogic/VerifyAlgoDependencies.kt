package dev.fitnessapp.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class VerifyAlgoDependencies : DefaultTask() {
    @get:Input
    abstract val allowedPatterns: ListProperty<String>

    @get:Input
    abstract val resolvedModules: ListProperty<String>

    @TaskAction
    fun verify() {
        val allowed = allowedPatterns.get().map(::Regex)
        val offending = resolvedModules.get().filter { m -> allowed.none { it.matches(m) } }
        if (offending.isNotEmpty()) {
            throw GradleException(
                ":algo must stay pure (ANDROID.md 6.3). Modules outside the allowlist on its runtime " +
                    "classpath:\n" + offending.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("verifyAlgoDependencies: ${resolvedModules.get().size} modules, all allowed")
    }
}

/** All external modules reachable from [root], as sorted `group:module` strings. */
fun collectModules(root: org.gradle.api.artifacts.result.ResolvedComponentResult): List<String> {
    val seen = sortedSetOf<String>()
    val visited = mutableSetOf<org.gradle.api.artifacts.result.ResolvedComponentResult>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val c = queue.removeFirst()
        if (!visited.add(c)) continue
        val id = c.id
        if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) seen.add("${id.group}:${id.module}")
        c.dependencies
            .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
            .forEach { queue.add(it.selected) }
    }
    return seen.toList()
}
