plugins {
    id("fitness.jvm.library")
    application
}

dependencies {
    implementation(project(":algo"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotest.assertions)
}

application {
    mainClass.set("dev.fitnessapp.sim.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.withType<Test>().configureEach {
    // ChallengePersonaGoldenTest compares fixtures/golden/challenge; -PupdateGoldens rewrites them.
    systemProperty("fitness.repoRoot", rootDir.absolutePath)
    systemProperty("fitness.updateGoldens", providers.gradleProperty("updateGoldens").isPresent.toString())
    inputs.dir(rootDir.resolve("fixtures/golden")).withPropertyName("goldenFixtures").optional()
    // MigrationDeviceStampTest lints the migrations against the synced tables of SyncTables.kt.
    inputs.dir(rootDir.resolve("supabase/migrations")).withPropertyName("migrations")
    inputs.file(rootDir.resolve("data/src/main/kotlin/dev/fitnessapp/data/sync/SyncTables.kt")).withPropertyName("syncTables")
    maxHeapSize = "2g"
}
