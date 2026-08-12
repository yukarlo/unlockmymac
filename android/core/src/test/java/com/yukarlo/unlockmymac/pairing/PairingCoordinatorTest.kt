package com.yukarlo.unlockmymac.pairing

import com.yukarlo.unlockmymac.ble.PairingClaim
import com.yukarlo.unlockmymac.ble.PairingInvite
import com.yukarlo.unlockmymac.ble.RejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

private const val MAC_ID = "6f9619ff-8b86-d011-b42d-00cf4fc964ff"
private const val NOW = 1_760_000_000_000L

private val TOKEN: String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24) { 5 })

private fun invite(expiresAtMs: Long = NOW + 300_000) = PairingInvite(MAC_ID, TOKEN, "Karlo's MacBook", expiresAtMs)

private fun claim(
    token: String = TOKEN,
    issuedAt: Long = NOW,
) = PairingClaim(MAC_ID, token, issuedAt)

class PairingCoordinatorTest {
    private val coordinator = PairingCoordinator()

    @Test
    fun `refuses claims while the window is closed`() {
        assertEquals(
            RejectReason.PAIRING_WINDOW_CLOSED,
            coordinator.acceptClaim(claim(), NOW),
        )
    }

    @Test
    fun `accepts a matching claim while the window is open`() {
        coordinator.open(invite(), NOW)
        assertNull(coordinator.acceptClaim(claim(), NOW))
    }

    @Test
    fun `refuses a claim with the wrong token`() {
        coordinator.open(invite(), NOW)
        val wrong = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24) { 6 })
        assertEquals(RejectReason.BAD_TOKEN, coordinator.acceptClaim(claim(token = wrong), NOW))
    }

    @Test
    fun `will not open a window for an already expired invite`() {
        assertNull(coordinator.open(invite(expiresAtMs = NOW - 1), NOW))
        assertTrue(coordinator.window.value is PairingWindow.Closed)
    }

    @Test
    fun `window never outlives the invite expiry`() {
        // Invite expires in 5s even though our own window would be 60s.
        val expiresAt = coordinator.open(invite(expiresAtMs = NOW + 5_000), NOW)
        assertEquals(NOW + 5_000, expiresAt)
    }

    @Test
    fun `window closes on its own deadline`() {
        val expiresAt = requireNotNull(coordinator.open(invite(), NOW))
        coordinator.sweep(expiresAt)

        assertTrue(coordinator.window.value is PairingWindow.Closed)
        assertEquals(
            RejectReason.PAIRING_WINDOW_CLOSED,
            coordinator.acceptClaim(claim(), expiresAt),
        )
    }

    @Test
    fun `only one claim per window`() {
        coordinator.open(invite(), NOW)
        assertNotNull(coordinator.markClaimed(NOW))

        assertNull(coordinator.markClaimed(NOW))
        assertEquals(
            RejectReason.PAIRING_WINDOW_CLOSED,
            coordinator.acceptClaim(claim(), NOW),
        )
    }

    @Test
    fun `close reopens the door to a fresh scan`() {
        coordinator.open(invite(), NOW)
        coordinator.close()
        assertTrue(coordinator.window.value is PairingWindow.Closed)

        assertNotNull(coordinator.open(invite(), NOW))
        assertNull(coordinator.acceptClaim(claim(), NOW))
    }
}
