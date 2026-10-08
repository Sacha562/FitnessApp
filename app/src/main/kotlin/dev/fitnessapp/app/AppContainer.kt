package dev.fitnessapp.app

import android.content.Context
import dev.fitnessapp.algo.core.clock.Clock
import dev.fitnessapp.data.device.DeviceIdentity
import dev.fitnessapp.data.remote.AccessCheck
import dev.fitnessapp.data.remote.ProfileSync
import dev.fitnessapp.data.remote.SupabaseFactory
import io.github.jan.supabase.SupabaseClient
import java.time.Instant

/** Manual dependency injection (ANDROID.md 7.2). One instance per process, owned by the Application. */
class AppContainer(context: Context) {
    val clock: Clock = Clock { Instant.now() }

    val supabase: SupabaseClient by lazy {
        SupabaseFactory.create(context, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY)
    }

    val device: DeviceIdentity = DeviceIdentity(context)

    val accessCheck: AccessCheck by lazy { AccessCheck(supabase) }

    val profileSync: ProfileSync by lazy { ProfileSync(supabase, device, BuildConfig.VERSION_NAME) }
}
