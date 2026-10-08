package dev.fitnessapp.data.remote

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.serialization.json.Json

/** JSON settings shared by every DTO (ANDROID.md 10.1). */
val FitnessJson: Json = Json {
    explicitNulls = false
    ignoreUnknownKeys = true
}

/**
 * The single Supabase client (ANDROID.md 10.1): publishable key only, default schema `fitness`, sessions
 * encrypted with an Android Keystore key (10.3). Only auth and postgrest are installed.
 */
object SupabaseFactory {
    fun create(context: Context, url: String, publishableKey: String): SupabaseClient {
        require(publishableKey.startsWith("sb_publishable_")) { "only a publishable key may be used by the app" }
        return createSupabaseClient(supabaseUrl = url, supabaseKey = publishableKey) {
            defaultSerializer = KotlinXSerializer(FitnessJson)
            install(Auth) {
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                sessionManager = KeystoreSessionManager(context.applicationContext)
            }
            install(Postgrest) {
                defaultSchema = "fitness"
                propertyConversionMethod = PropertyConversionMethod.SERIAL_NAME
            }
        }
    }
}
