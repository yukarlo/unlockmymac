package com.yukarlo.unlockmymac.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AdvertisingState {
    STOPPED,
    STARTING,
    ADVERTISING,

    /**
     * A central is connected. The Bluetooth controller stops a connectable advertisement for
     * the duration of the connection, so we are genuinely not discoverable right now — the
     * service restarts advertising as soon as the last central disconnects.
     */
    PAUSED_CONNECTED,
    FAILED,
    BLUETOOTH_OFF,
    NO_PERMISSION,
}

enum class AuthOutcome { SUCCESS, REJECTED, DENIED, ERROR }

class LastAuth(
    val atMs: Long,
    val outcome: AuthOutcome,
    val challengeTag: String,
    val detail: String,
)

/** A challenge the user has been asked to approve. */
class ApprovalRequest(
    val id: Long,
    val challengeTag: String,
    val requestedAtMs: Long,
    val expiresAtElapsedMs: Long,
)

data class BleStatus(
    val serviceRunning: Boolean = false,
    val advertising: AdvertisingState = AdvertisingState.STOPPED,
    val advertisingError: String? = null,
    val connectedCentrals: Int = 0,
    val lastChallengeAtMs: Long? = null,
    val lastAuth: LastAuth? = null,
    val pendingApproval: ApprovalRequest? = null,
    val pairingWindowExpiresAtMs: Long? = null,
    /**
     * When the user last denied a request.
     *
     * The Mac refuses to re-challenge for [com.yukarlo.unlockmymac.data.Timeouts.DENIAL_BACKOFF_MS]
     * after a denial. The phone cannot see that timer, so it counts down from this locally —
     * otherwise two minutes of silence is indistinguishable from the app being broken.
     */
    val deniedAtMs: Long? = null,
)

/**
 * In-memory bridge between [com.yukarlo.unlockmymac.service.BleUnlockService] and the UI.
 *
 * A process-wide singleton rather than a bound service: the service and the Activity live in
 * the same process, and observing a StateFlow avoids the binder lifecycle entirely.
 */
class BleStatusRepository {
    private val _status = MutableStateFlow(BleStatus())
    val status: StateFlow<BleStatus> = _status.asStateFlow()

    fun setServiceRunning(running: Boolean) =
        _status.update {
            if (running) {
                it.copy(serviceRunning = true)
            } else {
                BleStatus(lastChallengeAtMs = it.lastChallengeAtMs, lastAuth = it.lastAuth)
            }
        }

    fun setAdvertising(
        state: AdvertisingState,
        error: String? = null,
    ) = _status.update {
        it.copy(advertising = state, advertisingError = error)
    }

    fun setConnectedCentrals(count: Int) = _status.update { it.copy(connectedCentrals = count) }

    fun recordChallenge(atMs: Long) = _status.update { it.copy(lastChallengeAtMs = atMs) }

    fun recordAuth(
        outcome: AuthOutcome,
        challengeTag: String,
        detail: String,
    ) = _status.update {
        it.copy(
            lastAuth = LastAuth(System.currentTimeMillis(), outcome, challengeTag, detail),
            pendingApproval = null,
            // A successful unlock clears any earlier refusal; the Mac's backoff is moot once
            // it has authenticated.
            deniedAtMs = if (outcome == AuthOutcome.DENIED) System.currentTimeMillis() else null,
        )
    }

    fun setPendingApproval(request: ApprovalRequest?) =
        _status.update {
            it.copy(pendingApproval = request)
        }

    fun setPairingWindow(expiresAtMs: Long?) =
        _status.update {
            it.copy(pairingWindowExpiresAtMs = expiresAtMs)
        }
}
