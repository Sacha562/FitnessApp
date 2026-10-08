// Room with KSP for Android library modules (:data): schemas exported per version (ANDROID.md 8.1, 8.5).
plugins {
    id("com.google.devtools.ksp")
    id("androidx.room")
}

room {
    schemaDirectory("$projectDir/schemas")
}
