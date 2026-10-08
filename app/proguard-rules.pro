# R8 rules for the release build (ANDROID.md 5.3). kotlinx-serialization, supabase-kt, Ktor and Room ship
# consumer rules; add app-specific keeps here only when a release smoke test (ANDROID.md 21.5) needs them.

# @Serializable DTOs are looked up by generated serializers.
-keepclassmembers @kotlinx.serialization.Serializable class dev.fitnessapp.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
