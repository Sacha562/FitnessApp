// verifyAlgoDependencies (ANDROID.md 6.3 item 1): :algo's runtime classpath may contain only the Kotlin
// standard library, JetBrains annotations and kotlinx-serialization core/json. Wired into `check`.
import dev.fitnessapp.buildlogic.VerifyAlgoDependencies
import dev.fitnessapp.buildlogic.collectModules

val verifyAlgoDependencies = tasks.register<VerifyAlgoDependencies>("verifyAlgoDependencies") {
    group = "verification"
    description = "Fails if :algo's runtime classpath contains a module outside the purity allowlist."
    allowedPatterns.set(
        listOf(
            """org\.jetbrains\.kotlin:kotlin-stdlib.*""",
            """org\.jetbrains:annotations""",
            """org\.jetbrains\.kotlinx:kotlinx-serialization-core.*""",
            """org\.jetbrains\.kotlinx:kotlinx-serialization-json.*""",
            // Version-alignment platform (a POM without classes), pulled in by kotlinx-serialization.
            """org\.jetbrains\.kotlinx:kotlinx-serialization-bom""",
        ),
    )
    resolvedModules.set(
        configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
            .map(::collectModules),
    )
}

tasks.named("check") {
    dependsOn(verifyAlgoDependencies)
}
