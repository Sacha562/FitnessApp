import dev.fitnessapp.buildlogic.CheckReleaseSigning
import java.util.Properties

plugins {
    id("fitness.android.application")
}

// local.properties (git-ignored): sdk.dir, supabase.url, supabase.publishableKey, turnstile.siteKey,
// turnstile.baseUrl (ANDROID.md 5.3, 10.1).
val localProps = Properties().apply {
    val text = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull
    if (text != null) load(text.reader())
}
fun localProp(key: String): String = localProps.getProperty(key) ?: ""

// Release signing lives in the user-level %USERPROFILE%/.gradle/gradle.properties (ANDROID.md 5.4).
val releaseStorePath = providers.gradleProperty("FITNESS_RELEASE_STORE_FILE").orNull
val signingReady = releaseStorePath != null &&
    providers.gradleProperty("FITNESS_RELEASE_STORE_PASSWORD").isPresent &&
    providers.gradleProperty("FITNESS_RELEASE_KEY_ALIAS").isPresent &&
    providers.gradleProperty("FITNESS_RELEASE_KEY_PASSWORD").isPresent

// versionCode = commit count (strictly increasing); falls back to 1 without git history (ANDROID.md 5.5).
val gitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.orNull?.toIntOrNull() ?: 1
val gitSha = providers.exec {
    commandLine("git", "rev-parse", "--short=7", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.orNull?.takeIf { it.matches(Regex("[0-9a-f]{7}")) } ?: "nogit"
val milestone = 0

android {
    namespace = "dev.fitnessapp.app"

    defaultConfig {
        applicationId = "dev.fitnessapp"
        versionCode = gitCount
        versionName = "0.$milestone.$gitCount+$gitSha"
        buildConfigField("String", "SUPABASE_URL", "\"${localProp("supabase.url")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${localProp("supabase.publishableKey")}\"")
        // Turnstile CAPTCHA for password sign-in (D95): public site key; empty builds without the check.
        buildConfigField("String", "TURNSTILE_SITE_KEY", "\"${localProp("turnstile.siteKey")}\"")
        buildConfigField("String", "TURNSTILE_BASE_URL", "\"${localProp("turnstile.baseUrl").ifEmpty { "https://chessanvil.com/" }}\"")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("boolean", "SYNC_ENABLED", "true")
        buildConfigField("boolean", "USER_CALENDAR_WRITES_ENABLED", "true")
    }

    signingConfigs {
        create("release") {
            if (signingReady) {
                storeFile = file(releaseStorePath!!)
                storePassword = providers.gradleProperty("FITNESS_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("FITNESS_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("FITNESS_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        // debug and release share the application id and the release key (F17, F27, ANDROID.md 5.3).
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Experiments: own id, debug key, no sync, no writes to the user's calendars (F22, F27).
        create("sandbox") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".sandbox"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "debug"
            buildConfigField("boolean", "SYNC_ENABLED", "false")
            buildConfigField("boolean", "USER_CALENDAR_WRITES_ENABLED", "false")
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties")
    }
}

// The debug menu and the golden fixtures (assets for the on-device determinism self-test, ANDROID.md 6.3
// item 4) exist in debug and sandbox builds only. Sandbox reuses the debug sources; its manifest entry is
// in src/sandbox.
val goldenDir = rootProject.layout.projectDirectory.dir("fixtures/golden").asFile.path
val debugKotlinDir = layout.projectDirectory.dir("src/debug/kotlin").asFile.path
androidComponents {
    onVariants(selector().withBuildType("debug")) { v ->
        v.sources.assets?.addStaticSourceDirectory(goldenDir)
    }
    onVariants(selector().withBuildType("sandbox")) { v ->
        v.sources.assets?.addStaticSourceDirectory(goldenDir)
        v.sources.kotlin?.addStaticSourceDirectory(debugKotlinDir)
    }
}

val checkReleaseSigning = tasks.register<CheckReleaseSigning>("checkReleaseSigning") {
    configured.set(signingReady)
}
tasks.configureEach {
    if (name == "packageDebug" || name == "packageRelease") dependsOn(checkReleaseSigning)
}

dependencies {
    implementation(project(":data"))
    implementation(project(":algo"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.compose.material.icons.extended)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.vintage)
    testImplementation(libs.junit4)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
