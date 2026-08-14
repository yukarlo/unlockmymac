package com.yukarlo.unlockmymac.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MAC = "AA:BB:CC:DD:EE:01"
private const val RING = "AA:BB:CC:DD:EE:02"

class ConnectedCentralsTest {
    @Test
    fun `first publish always gets through, even reporting zero`() {
        val centrals = ConnectedCentrals()
        // The publish right after the GATT server opens reports zero, and the service reacts to zero
        // by starting to advertise. Swallowing it would leave the device undiscoverable from boot.
        assertEquals(0, centrals.countToPublish(force = false))
    }

    @Test
    fun `an unchanged sweep is swallowed`() {
        val centrals = ConnectedCentrals()
        centrals.countToPublish(force = false)

        // This is the 15s session sweep finding nothing new. Publishing anyway restarted advertising
        // every 15s, and Android mints a fresh address on every restart.
        assertNull(centrals.countToPublish(force = false))
        assertNull(centrals.countToPublish(force = false))
    }

    @Test
    fun `a forced publish is never swallowed`() {
        val centrals = ConnectedCentrals()
        centrals.countToPublish(force = false)

        assertEquals(0, centrals.countToPublish(force = true))
        assertEquals(0, centrals.countToPublish(force = true))
    }

    /**
     * The regression this class was extracted for.
     *
     * A central that connects and drops without ever touching a characteristic leaves the count at
     * zero the whole way through. The radio stops advertising when the link comes up, so if the
     * disconnect is swallowed nothing ever restarts it and the device goes silently undiscoverable.
     */
    @Test
    fun `a link that never engaged still reports its disconnect when forced`() {
        val centrals = ConnectedCentrals()
        centrals.countToPublish(force = false) // server opened

        // Connect: not counted, because engagement is earned by using the service.
        assertNull(centrals.countToPublish(force = false))

        // Disconnect: same count, but the caller forces it.
        centrals.remove(MAC)
        assertEquals(0, centrals.countToPublish(force = true))
    }

    @Test
    fun `engagement is what raises the count`() {
        val centrals = ConnectedCentrals()
        centrals.countToPublish(force = false)

        assertTrue(centrals.add(MAC))
        assertEquals(1, centrals.count)
        assertEquals(1, centrals.countToPublish(force = false))

        // A second read or write from the same central is not a new arrival.
        assertFalse(centrals.add(MAC))
        assertNull(centrals.countToPublish(force = false))
    }

    @Test
    fun `reconcile drops what the stack no longer holds`() {
        val centrals = ConnectedCentrals()
        centrals.add(MAC)
        centrals.add(RING)

        val dropped = centrals.reconcile(live = setOf(MAC))

        assertEquals(setOf(RING), dropped)
        assertEquals(1, centrals.count)
    }

    /**
     * A missed `STATE_DISCONNECTED` used to strand an address for the life of the GATT server, which
     * pinned the count above zero and so stopped the advertising restart from ever running again.
     */
    @Test
    fun `reconcile recovers from a missed disconnect callback`() {
        val centrals = ConnectedCentrals()
        centrals.add(MAC)
        centrals.countToPublish(force = true)

        centrals.reconcile(live = emptySet())

        assertEquals(0, centrals.count)
        assertEquals(0, centrals.countToPublish(force = false))
    }

    @Test
    fun `reconcile keeps everything when the stack cannot be asked`() {
        val centrals = ConnectedCentrals()
        centrals.add(MAC)

        // null is "no answer available", not "nothing is connected". Treating it as the latter would
        // drop a live central every time the Bluetooth manager was briefly unavailable.
        assertEquals(emptySet<String>(), centrals.reconcile(live = null))
        assertEquals(1, centrals.count)
    }

    @Test
    fun `clear forgets the published count so the next publish gets through`() {
        val centrals = ConnectedCentrals()
        centrals.add(MAC)
        centrals.countToPublish(force = false)

        // Closing and reopening the server must not inherit the old count as "already published".
        centrals.clear()

        assertEquals(0, centrals.count)
        assertEquals(0, centrals.countToPublish(force = false))
    }

    @Test
    fun `snapshot is unaffected by later changes`() {
        val centrals = ConnectedCentrals()
        centrals.add(MAC)

        val snapshot = centrals.snapshot()
        centrals.add(RING)

        assertEquals(listOf(MAC), snapshot)
    }
}
