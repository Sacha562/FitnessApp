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
