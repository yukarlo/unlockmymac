package com.yukarlo.unlockmymac.pairing

/**
 * Holds the signed offer this device is currently vouching with, if any.
 *
 * The watch cannot scan the Mac's pairing QR, so it sends its public key here over the Wear Data
 * Layer and this phone — already trusted by the Mac — signs a statement saying "also trust this
 * key". The Mac reads that statement over the link it already uses.
 *
 * Deliberately in-memory and short-lived. An offer that outlived the process would be a standing
 * authorisation to add a device, sitting on disk, usable by whoever next connects.
 */
class EnrolmentCoordinator {
    class PendingOffer(
        val offerBytes: ByteArray,
        val deviceName: String,
        val deviceId: String,
        val expiresAtMs: Long,
    )

    private val lock = Any()

    private var pending: PendingOffer? = null

    /** Replaces any existing offer: only one device can be waiting to be vouched for. */
    fun stage(offer: PendingOffer) =
        synchronized(lock) {
            pending = offer
        }

    /** The current offer, or null when there is none or it has expired. */
    fun current(nowMs: Long): PendingOffer? =
        synchronized(lock) {
            val offer = pending ?: return null
            if (nowMs > offer.expiresAtMs) {
                pending = null
                return null
            }
            offer
        }

    fun clear() =
        synchronized(lock) {
            pending = null
        }
}
