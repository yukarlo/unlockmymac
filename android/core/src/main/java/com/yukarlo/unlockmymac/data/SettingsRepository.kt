package com.yukarlo.unlockmymac.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "unlock_settings")

enum class AdvertiseMode {
    /** Advertises about once a second. Longest discovery latency, lowest battery cost. */
    LOW_POWER,

    /**
     * Advertises about every 250 ms.
     *
     * The default on the watch, and worth the battery there. A central has to catch an advertising
     * event to open a connection, so the interval is a floor on how long establishing one takes:
     * measured against this watch, connects took ~3.0s on [LOW_POWER] against 0.67s on the phone at
     * this interval, which is over the Mac's connect watchdog. The Mac then cancels a connect that
     * was about to land, and both of its attempts die the same way.
     */
    BALANCED,
    ;

    companion object {
        /**
         * Turns a stored preference value back into a mode, falling back to [default] when absent.
         *
         * A free function rather than inline in the flow so it can be tested: the whole watch
         * connect-stall came from this resolution, and reaching it through a `DataStore` and a
         * `Context` needs an instrumented test for what is three lines of pure logic.
         *
         * `LOW_POWER` is matched explicitly rather than left to the `else`. Without that branch a
         * form factor whose default is `BALANCED` would silently override a user who had turned the
         * toggle *off*, because a stored `LOW_POWER` is indistinguishable from nothing stored.
         */
        fun fromStored(
            stored: String?,
            default: AdvertiseMode,
        ): AdvertiseMode =
            when (stored) {
                BALANCED.name -> BALANCED
                LOW_POWER.name -> LOW_POWER
                else -> default
            }
    }
}

class AppSettings(
    val serviceEnabled: Boolean,
    val paused: Boolean,
    val requireApproval: Boolean,
    val advertiseMode: AdvertiseMode,
    val deviceName: String,
    /**
     * Whether an approval request also raises the floating banner over whatever is on screen.
     *
     * Defaults on: it is the surface that makes an approval answerable in one tap without hunting
     * through the shade. Off leaves the notification, which is the surface that always exists — so
     * turning this off degrades the experience rather than breaking it.
     */
    val approvalBannerEnabled: Boolean,
    /** Swipe the banner up to open the app. */
    val bannerSwipeUpOpensApp: Boolean,
    /** Swipe the banner down to put it away without answering. */
    val bannerSwipeDownDismisses: Boolean,
    /** Tap the dimmed background to put the banner away without answering. */
    val bannerScrimTapDismisses: Boolean,
) {
    /** Advertising should only run when the user has enabled the service and not paused it. */
    val shouldAdvertise: Boolean get() = serviceEnabled && !paused
}

class SettingsRepository(
    private val context: Context,
    /**
     * What [AppSettings.advertiseMode] reads as before the user has ever chosen.
     *
     * Per form factor rather than global: the watch needs [AdvertiseMode.BALANCED] to be reachable
     * at all (see the KDoc there), while the phone is found quickly either way and keeps the
     * cheaper default. Threaded in from [com.yukarlo.unlockmymac.AppContainer] so this class stays
     * unaware of which app it is serving.
     */
    private val defaultAdvertiseMode: AdvertiseMode = AdvertiseMode.LOW_POWER,
) {
    val settings: Flow<AppSettings> =
        context.settingsDataStore.data.map { prefs ->
            AppSettings(
                serviceEnabled = prefs[SERVICE_ENABLED] ?: false,
                paused = prefs[PAUSED] ?: false,
                requireApproval = prefs[REQUIRE_APPROVAL] ?: false,
                advertiseMode = AdvertiseMode.fromStored(prefs[ADVERTISE_MODE], defaultAdvertiseMode),
                deviceName = prefs[DEVICE_NAME] ?: android.os.Build.MODEL ?: "Android",
                approvalBannerEnabled = prefs[APPROVAL_BANNER] ?: true,
                bannerSwipeUpOpensApp = prefs[BANNER_SWIPE_UP] ?: true,
                bannerSwipeDownDismisses = prefs[BANNER_SWIPE_DOWN] ?: true,
                bannerScrimTapDismisses = prefs[BANNER_SCRIM_TAP] ?: true,
            )
        }

    suspend fun setServiceEnabled(enabled: Boolean) =
        edit {
            it[SERVICE_ENABLED] = enabled
            // Enabling the service always clears a stale pause so the switch means what it says.
            if (enabled) it[PAUSED] = false
        }

    suspend fun setPaused(paused: Boolean) = edit { it[PAUSED] = paused }

    suspend fun setRequireApproval(required: Boolean) = edit { it[REQUIRE_APPROVAL] = required }

    suspend fun setAdvertiseMode(mode: AdvertiseMode) = edit { it[ADVERTISE_MODE] = mode.name }

    suspend fun setApprovalBannerEnabled(enabled: Boolean) = edit { it[APPROVAL_BANNER] = enabled }

    suspend fun setBannerSwipeUpOpensApp(enabled: Boolean) = edit { it[BANNER_SWIPE_UP] = enabled }

    suspend fun setBannerSwipeDownDismisses(enabled: Boolean) = edit { it[BANNER_SWIPE_DOWN] = enabled }

    suspend fun setBannerScrimTapDismisses(enabled: Boolean) = edit { it[BANNER_SCRIM_TAP] = enabled }

    suspend fun setDeviceName(name: String) =
        edit {
            it[DEVICE_NAME] = name.trim().take(64).ifEmpty { android.os.Build.MODEL ?: "Android" }
        }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    private companion object {
        val SERVICE_ENABLED = booleanPreferencesKey("service_enabled")
        val PAUSED = booleanPreferencesKey("paused")
        val REQUIRE_APPROVAL = booleanPreferencesKey("require_approval")
        val APPROVAL_BANNER = booleanPreferencesKey("approval_banner_enabled")
        val BANNER_SWIPE_UP = booleanPreferencesKey("banner_swipe_up_opens_app")
        val BANNER_SWIPE_DOWN = booleanPreferencesKey("banner_swipe_down_dismisses")
        val BANNER_SCRIM_TAP = booleanPreferencesKey("banner_scrim_tap_dismisses")
        val ADVERTISE_MODE = stringPreferencesKey("advertise_mode")
        val DEVICE_NAME = stringPreferencesKey("device_name")
    }
}

/** Challenge lifetimes. Approval mode needs a human-scale window, not a machine-scale one. */
object Timeouts {
    /**
     * How long the Mac refuses to re-challenge after the user denies one.
     *
     * Mirrors `deniedBackoffSeconds` in the macOS `PresenceStateMachine`. The phone cannot
     * observe the Mac's timer, so it counts down locally from the moment of denial purely to
     * tell the user when to expect the next prompt — without it, two minutes of silence is
     * indistinguishable from a broken app. Kept in `protocol-vectors.json` so the two sides
     * cannot drift apart unnoticed.
     */
    const val DENIAL_BACKOFF_MS = 120_000L

    const val CHALLENGE_TTL_MS = 10_000L
    const val CHALLENGE_TTL_WITH_APPROVAL_MS = 60_000L
    const val PAIRING_WINDOW_MS = 60_000L

    fun challengeTtlMs(requireApproval: Boolean): Long = if (requireApproval) CHALLENGE_TTL_WITH_APPROVAL_MS else CHALLENGE_TTL_MS
}
