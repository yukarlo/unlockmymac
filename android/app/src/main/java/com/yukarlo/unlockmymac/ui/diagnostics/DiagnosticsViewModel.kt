package com.yukarlo.unlockmymac.ui.diagnostics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yukarlo.unlockmymac.container
import com.yukarlo.unlockmymac.crypto.KeySecurityLevel
import com.yukarlo.unlockmymac.data.LogEvent
import com.yukarlo.unlockmymac.data.PairedMac
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeyInfoUi(
    val fingerprint: String,
    val securityLevel: KeySecurityLevel,
)

class DiagnosticsUiState(
    val events: List<LogEvent>,
    val pairedMac: PairedMac?,
    val deviceId: String,
    val keyInfo: KeyInfoUi?,
)

class DiagnosticsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val container = application.container
    private val keyInfo = MutableStateFlow<KeyInfoUi?>(null)

    val uiState: StateFlow<DiagnosticsUiState> =
        combine(
            container.eventLog.events,
            container.pairing.pairedMac,
            container.pairing.deviceId,
            keyInfo,
        ) { events, paired, deviceId, key ->
            DiagnosticsUiState(events.asReversed(), paired, deviceId, key)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DiagnosticsUiState(emptyList(), null, "", null),
        )

    private val _unpairPrompt = MutableStateFlow(false)
    val unpairPrompt: StateFlow<Boolean> = _unpairPrompt.asStateFlow()

    init {
        viewModelScope.launch {
            // Keystore access can block; never touch it on the main thread.
            keyInfo.value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val identity = container.signer.identity()
                        KeyInfoUi(identity.fingerprint, identity.securityLevel)
                    }.getOrNull()
                }
        }
    }

    fun clearLog() = container.eventLog.clear()

    fun promptUnpair(show: Boolean) {
        _unpairPrompt.value = show
    }

    /**
     * Forgets the Mac and destroys the identity key, so the public key the Mac holds becomes
     * useless even if it was never deleted on that side.
     */
    fun unpair() {
        _unpairPrompt.value = false
        viewModelScope.launch {
            // Everything the teardown needs lives in `forgetPairing` — the coordinator close and the
            // session clear used to be duplicated here, which meant the watch (which has no
            // Diagnostics screen) skipped them entirely and `deleteKey` ran twice on this path.
            container.forgetPairing()
            // Regenerated only here, and only so the fingerprint below has something to show. The
            // container deliberately leaves key creation to the next `ensureKey`.
            withContext(Dispatchers.IO) {
                runCatching { container.signer.ensureKey() }
            }
            keyInfo.value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val identity = container.signer.identity()
                        KeyInfoUi(identity.fingerprint, identity.securityLevel)
                    }.getOrNull()
                }
            container.eventLog.warn("Unpaired; identity key regenerated")
        }
    }
}
