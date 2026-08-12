package com.yukarlo.unlockmymac.ble

import java.util.UUID

/**
 * Fixed 128-bit UUIDs used for service discovery only. Per the threat model, the advertised
 * UUID is never treated as an authentication credential — it just tells the Mac which
 * peripheral is worth connecting to.
 */
object BleUuids {
    val SERVICE: UUID = UUID.fromString("f9a2b8e3-54cd-4e92-a123-765432198765")

    /** Mac writes the challenge payload here. WRITE. */
    val CHALLENGE: UUID = UUID.fromString("f9a2b8e3-54cd-4e92-a123-765432198766")

    /** Mac reads the DER ECDSA signature here. READ. */
    val RESPONSE: UUID = UUID.fromString("f9a2b8e3-54cd-4e92-a123-765432198767")

    /** Mac writes a pairing token and reads back our identity here. WRITE + READ. */
    val PAIRING: UUID = UUID.fromString("f9a2b8e3-54cd-4e92-a123-765432198768")

    /**
     * Mac reads a signed offer vouching for another device here. READ.
     *
     * Empty almost always — it only holds anything in the minutes between a watch asking to be
     * enrolled and the user opening "Add a device" on the Mac.
     */
    val ENROLMENT: UUID = UUID.fromString("f9a2b8e3-54cd-4e92-a123-765432198769")
}

/**
 * Application-defined ATT error codes. The Bluetooth spec reserves 0x80-0x9F for
 * application errors, so these never collide with a stack-generated status.
 */
object GattStatus {
    /**
     * The challenge is valid but the user has not approved it yet. The Mac should retry the
     * read while its own authentication timeout allows.
     */
    const val PENDING_APPROVAL = 0x80

    /**
     * Request refused: not paired, wrong Mac, replayed, malformed, expired, already used,
     * another central holds the active session, or the pairing window is closed.
     *
     * Intentionally a single opaque code — telling an unknown central *why* it was rejected
     * hands it a free oracle.
     */
    const val REJECTED = 0x81

    /**
     * The user explicitly denied this request.
     *
     * Distinct from [REJECTED] so the Mac can stop asking instead of re-challenging seconds
     * later and raising a fresh prompt. Safe to disclose: reaching the approval state already
     * requires passing every validation check — paired Mac installation id, our own device id,
     * a fresh timestamp and a non-replayed challenge — so only the genuine paired Mac ever sees
     * this code. Everything else stays behind the opaque [REJECTED].
     */
    const val DENIED = 0x82
}
