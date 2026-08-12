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
    /** Default. Longest discovery latency, lowest battery cost. */
    LOW_POWER,

    /** Opt-in from settings if the Mac is slow to find the phone. */
    BALANCED,
}

class AppSettings(
    val serviceEnabled: Boolean,
    val paused: Boolean,
    val requireApproval: Boolean,
    val advertiseMode: AdvertiseMode,
    val deviceName: String,
) {
    /** Advertising should only run when the user has enabled the service and not paused it. */
    val shouldAdvertise: Boolean get() = serviceEnabled && !paused
}

class SettingsRepository(
    private val context: Context,
) {
    val settings: Flow<AppSettings> =
        context.settingsDataStore.data.map { prefs ->
            AppSettings(
                serviceEnabled = prefs[SERVICE_ENABLED] ?: false,
                paused = prefs[PAUSED] ?: false,
                requireApproval = prefs[REQUIRE_APPROVAL] ?: false,
                advertiseMode =
                    when (prefs[ADVERTISE_MODE]) {
                        AdvertiseMode.BALANCED.name -> AdvertiseMode.BALANCED
                        else -> AdvertiseMode.LOW_POWER
                    },
                deviceName = prefs[DEVICE_NAME] ?: android.os.Build.MODEL ?: "Android",
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
