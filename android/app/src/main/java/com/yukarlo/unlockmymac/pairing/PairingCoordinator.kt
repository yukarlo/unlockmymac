package com.yukarlo.unlockmymac.pairing

import com.yukarlo.unlockmymac.ble.PairingClaim
import com.yukarlo.unlockmymac.ble.PairingCodec
import com.yukarlo.unlockmymac.ble.PairingInvite
import com.yukarlo.unlockmymac.ble.RejectReason
import com.yukarlo.unlockmymac.data.Timeouts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

/** What the pairing screen is currently doing. */
sealed interface PairingWindow {
    data object Closed : PairingWindow

    /** QR scanned and accepted; the Mac may now claim the pairing until [expiresAtMs]. */
    class Open(
        val invite: PairingInvite,
        val expiresAtMs: Long,
    ) : PairingWindow

    /** The Mac read our identity. Waiting for the user to confirm the fingerprint. */
    class Claimed(
        val invite: PairingInvite,
        val atMs: Long,
    ) : PairingWindow
}

/**
 * Owns the short pairing window opened by scanning the Mac's QR.
 *
 * Pairing is deliberately the only path that writes a paired-Mac record, and it only exists
 * for 60 seconds after a deliberate user action. Outside the window the pairing characteristic
 * refuses every write, so an attacker who copies the service UUID has nothing to talk to.
 */
class PairingCoordinator {
    private val _window = MutableStateFlow<PairingWindow>(PairingWindow.Closed)
    val window: StateFlow<PairingWindow> = _window.asStateFlow()

    /**
     * Opens the window for the scanned invite. The window never outlives the QR's own
     * expiry, so a stale screenshot of an old QR cannot be used later.
     */
    fun open(
        invite: PairingInvite,
        nowMs: Long,
    ): Long? {
        if (nowMs >= invite.expiresAtMs) return null
        val expiresAt = min(nowMs + Timeouts.PAIRING_WINDOW_MS, invite.expiresAtMs)
        _window.value = PairingWindow.Open(invite, expiresAt)
        return expiresAt
    }

    fun close() {
        _window.value = PairingWindow.Closed
    }

    /** Expires the window if its deadline has passed. Cheap to call from any code path. */
    fun sweep(nowMs: Long) {
        val open = _window.value as? PairingWindow.Open ?: return
        if (nowMs >= open.expiresAtMs) _window.value = PairingWindow.Closed
    }

    /**
     * Validates a claim written to the pairing characteristic.
     *
     * @return null if the claim is good, otherwise why it was refused.
     */
    fun acceptClaim(
        claim: PairingClaim,
        nowMs: Long,
    ): RejectReason? {
        sweep(nowMs)
        val open =
            _window.value as? PairingWindow.Open
                ?: return RejectReason.PAIRING_WINDOW_CLOSED
        return PairingCodec.validateClaim(claim, open.invite, nowMs)
    }

    /**
     * Marks the identity as delivered. Called after the Mac has read the whole response, which
     * closes the window: one Mac, one claim.
     */
    fun markClaimed(nowMs: Long): PairingInvite? {
        val open = _window.value as? PairingWindow.Open ?: return null
        _window.value = PairingWindow.Claimed(open.invite, nowMs)
        return open.invite
    }

    fun activeInvite(nowMs: Long): PairingInvite? {
        sweep(nowMs)
        return (_window.value as? PairingWindow.Open)?.invite
    }
}
