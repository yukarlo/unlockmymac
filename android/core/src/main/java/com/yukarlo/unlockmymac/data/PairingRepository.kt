package com.yukarlo.unlockmymac.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

val Context.pairingDataStore: DataStore<Preferences> by preferencesDataStore(name = "unlock_pairing")

/** The one Mac this phone will answer challenges for. */
class PairedMac(
    val installationId: String,
    val name: String,
    val pairedAtMs: Long,
)

/**
 * Stores our own random device id and the paired Mac record.
 *
 * Note what is *not* here: no BLE MAC address, no Mac password, no private key. The private key
 * never leaves `AndroidKeyStore`, and the device id is a random UUID rather than any hardware
 * identifier so it can be rotated by unpairing.
 */
class PairingRepository(
    private val context: Context,
) {
    val pairedMac: Flow<PairedMac?> =
        context.pairingDataStore.data.map { prefs ->
            val id = prefs[MAC_INSTALLATION_ID] ?: return@map null
            PairedMac(
                installationId = id,
                name = prefs[MAC_NAME] ?: "Mac",
                pairedAtMs = prefs[PAIRED_AT] ?: 0L,
            )
        }

    val deviceId: Flow<String> = context.pairingDataStore.data.map { it[DEVICE_ID] ?: "" }

    /** Returns the stable device id, generating it on first call. */
    suspend fun requireDeviceId(): String {
        val existing = context.pairingDataStore.data.first()[DEVICE_ID]
        if (existing != null) return existing
        var created = ""
        context.pairingDataStore.edit { prefs ->
            // Re-check inside the transaction: two callers can race on first launch.
            created = prefs[DEVICE_ID] ?: UUID.randomUUID().toString().also { prefs[DEVICE_ID] = it }
        }
        return created
    }

    suspend fun savePairing(
        installationId: String,
        macName: String,
        nowMs: Long,
    ) {
        context.pairingDataStore.edit { prefs ->
            prefs[MAC_INSTALLATION_ID] = installationId
            prefs[MAC_NAME] = macName
            prefs[PAIRED_AT] = nowMs
        }
    }

    /** Forgets the Mac. The device id is regenerated so the old pairing cannot be resurrected. */
    suspend fun unpair() {
        context.pairingDataStore.edit { prefs ->
            prefs.remove(MAC_INSTALLATION_ID)
            prefs.remove(MAC_NAME)
            prefs.remove(PAIRED_AT)
            prefs[DEVICE_ID] = UUID.randomUUID().toString()
        }
    }

    private companion object {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val MAC_INSTALLATION_ID = stringPreferencesKey("mac_installation_id")
        val MAC_NAME = stringPreferencesKey("mac_name")
        val PAIRED_AT = longPreferencesKey("paired_at")
    }
}
