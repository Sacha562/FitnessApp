package dev.fitnessapp.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * `debug` and `release` must be signed with the release keystore (F17, F27, ANDROID.md 5.3). When the
 * signing properties are missing, packaging fails here instead of silently falling back to the debug
 * key, which would produce an APK that cannot update the installed app.
 */
abstract class CheckReleaseSigning : DefaultTask() {
    @get:Input
    abstract val configured: Property<Boolean>

    @TaskAction
    fun check() {
        if (!configured.get()) {
            throw GradleException(
                "Release signing is not configured. Add FITNESS_RELEASE_STORE_FILE, " +
                    "FITNESS_RELEASE_STORE_PASSWORD, FITNESS_RELEASE_KEY_ALIAS and " +
                    "FITNESS_RELEASE_KEY_PASSWORD to %USERPROFILE%/.gradle/gradle.properties" +
                    "(ANDROID.md 5.4). The sandbox build type does not need them.",
            )
        }
    }
}
