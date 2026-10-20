plugins {
    id("fitness.jvm.library")
    id("fitness.algo.purity")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
}

tasks.withType<Test>().configureEach {
    // AlgoPurityTest scans sources; GoldenFixtureTest reads fixtures/golden/algo.
    systemProperty("fitness.repoRoot", rootDir.absolutePath)
    systemProperty("fitness.updateGoldens", providers.gradleProperty("updateGoldens").isPresent.toString())
    inputs.dir(rootDir.resolve("fixtures/golden")).withPropertyName("goldenFixtures").optional()
    inputs.files(fileTree(rootDir.resolve("app/src/main/kotlin")) { include("**/uidet/**/*.kt") })
        .withPropertyName("uidetSources")
}

// CHALLENGE_ENGINE 2.1: definitions are authored in docs/challenges and bundled under fitness/challenges.
tasks.named<ProcessResources>("processResources") {
    from(rootDir.resolve("docs/challenges")) {
        include("*.challenge.v*.json")
        into("fitness/challenges")
    }
}
