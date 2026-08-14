package com.yukarlo.unlockmymac.ble

import java.util.concurrent.ConcurrentHashMap

/**
 * Which centrals count as connected, and when that is worth telling anyone about.
 *
 * Extracted from [GattServerController] purely so this can be tested. The decisions here are the
 * ones that have gone wrong twice — a stale address stranded in the set, and a link that came and
 * went without ever being reported — and both are pure logic that needed a `Context`, a
 * `BluetoothManager` and a live radio to reach. Now they need none of those.
 *
 * Membership is earned by *using* the service, not by opening an LE connection: the ACL callback
 * fires for every peer the phone talks to, so a smart ring talking to its own app was once counted
 * as a connected Mac. The caller decides what counts as use; this only tracks the answer.
 *
 * Thread-safe. The GATT callback thread and the periodic sweep both reach it.
 */
class ConnectedCentrals {
    private val connected: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Last count handed out, so an unchanged sweep does not look like an event.
     *
     * Starts at -1 rather than 0 so the very first publish always gets through, including the one
     * that reports zero after the server opens.
     */
    @Volatile
    private var lastPublishedCount = -1

    val count: Int get() = connected.size

    /** A stable copy, for callers that iterate while the set may change under them. */
    fun snapshot(): List<String> = connected.toList()

    /** True when this address was not already present. */
    fun add(address: String): Boolean = connected.add(address)

    fun remove(address: String): Boolean = connected.remove(address)

    fun clear() {
        connected.clear()
        lastPublishedCount = -1
    }

    /**
     * Drops anything the Bluetooth stack no longer holds, returning what was dropped.
     *
     * `null` means the stack could not be asked — keep what we have rather than inventing an answer.
     * The set otherwise only ever grew and shrank from callbacks, so one missed `STATE_DISCONNECTED`
     * stranded an address for the life of the GATT server, and because the advertising restart is
     * gated on reaching zero, that restart then silently never ran again.
     *
     * No lock: the set is concurrent, and racing an [add] can only drop a central that is about to
     * re-add itself on its next read or write, which the following sweep reconciles anyway.
     */
    fun reconcile(live: Set<String>?): Set<String> {
        if (live == null) return emptySet()
        val stale = connected - live
        if (stale.isNotEmpty()) connected.removeAll(stale)
        return stale
    }

    /**
     * The count to hand the listener, or `null` when it should not be told.
     *
     * Unchanged counts are swallowed so the periodic sweep cannot keep firing: the listener reacts
     * to zero by restarting the advertisement, and Android mints a new resolvable address every time
     * it does. Measured with the sweep publishing unconditionally: a fresh address every 15.00s,
     * which left the Mac dialling handles that had already rotated away and took an unlock from ~7s
     * to 39-48s.
     *
     * [force] exists because a connection change must never be swallowed. Since membership is earned
     * by using the service, a central that connects and drops without touching a characteristic
     * leaves the count at zero throughout — so the gate alone hid both transitions, while the radio
     * had already stopped advertising when the link came up. Nothing was left to restart it.
     */
    fun countToPublish(force: Boolean): Int? {
        val current = connected.size
        if (!force && current == lastPublishedCount) return null
        lastPublishedCount = current
        return current
    }
}
