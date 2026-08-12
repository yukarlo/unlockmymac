package com.yukarlo.unlockmymac.ble

import java.util.Base64

/**
 * A signed offer to authorise a second device — in practice a watch — on a Mac this phone is
 * already paired with.
 *
 * [signedBytes] is the exact byte range the signature covers, kept verbatim rather than
 * re-serialised for the same reason [ChallengeRequest] keeps its raw payload: any difference in
 * normalisation, however invisible, breaks verification on the other side.
 */
class EnrolmentOffer(
    val macInstallationId: String,
    val deviceId: String,
    val deviceName: String,
    val publicKeyDer: ByteArray,
    val issuedAtMs: Long,
    val signedBytes: ByteArray,
    val signature: ByteArray,
)

/**
 * Enrolment exchange codec.
 *
 * The watch has no camera, so it cannot scan the Mac's pairing QR. Instead it hands its own public
 * key to the phone over the Wear Data Layer, and the phone — already trusted by the Mac — signs a
 * statement saying "also trust this key". The Mac reads that statement over the link it already
 * uses and verifies it against the phone's stored public key.
 *
 * No secret ever moves. The watch keeps a non-exportable key of its own, exactly as the phone
 * does, so afterwards it authenticates on its own and can be revoked on its own.
 *
 * What stops this being an open door:
 *  - only a device the Mac already trusts can produce a verifiable offer;
 *  - the offer names one Mac, so it cannot be replayed to another Mac that trusts this phone;
 *  - it expires, so a captured offer is not useful later;
 *  - the Mac only looks for one when the user asks it to, from an unlocked session.
 */
object EnrolmentCodec {
    const val OFFER_PREFIX = "mac-ble-enrol:v1"
    const val MAX_OFFER_BYTES = 1024
    const val SIGNATURE_KEY = "signature"

    /** How long an offer stays usable. Long enough to walk to the Mac, short enough to matter. */
    const val OFFER_TTL_MS = 300_000L

    /** X.509 SubjectPublicKeyInfo DER for P-256 is always exactly this long. */
    const val PUBLIC_KEY_DER_BYTES = 91

    private val BODY_KEYS = listOf("macInstallationId", "deviceId", "name", "publicKey", "issuedAt")

    private const val SIGNATURE_SEPARATOR = "\n$SIGNATURE_KEY="

    /**
     * Builds a signed offer:
     *
     * ```
     * mac-ble-enrol:v1
     * macInstallationId=<uuid>
     * deviceId=<uuid>
     * name=<friendly name>
     * publicKey=<base64 X.509 SubjectPublicKeyInfo DER>
     * issuedAt=<unix-ms>
     * signature=<base64 SHA256withECDSA over everything above>
     * ```
     *
     * The signature covers the body exactly as written, up to but excluding the newline before
     * `signature=`. Returns null when signing fails, which is the only way the keystore reports a
     * key it can no longer use.
     */
    fun buildOffer(
        macInstallationId: String,
        deviceId: String,
        deviceName: String,
        publicKeyDer: ByteArray,
        issuedAtMs: Long,
        sign: (ByteArray) -> ByteArray?,
    ): ByteArray? {
        val safeName =
            deviceName
                .replace('\n', ' ')
                .trim()
                .take(64)
                .ifEmpty { "Wear device" }

        val body =
            buildString {
                append(OFFER_PREFIX).append('\n')
                append("macInstallationId=").append(macInstallationId).append('\n')
                append("deviceId=").append(deviceId).append('\n')
                append("name=").append(safeName).append('\n')
                append("publicKey=").append(Base64.getEncoder().encodeToString(publicKeyDer)).append('\n')
                append("issuedAt=").append(issuedAtMs)
            }.toByteArray(Charsets.UTF_8)

        val signature = sign(body) ?: return null
        val suffix =
            (SIGNATURE_SEPARATOR + Base64.getEncoder().encodeToString(signature))
                .toByteArray(Charsets.UTF_8)
        return body + suffix
    }

    /**
     * Parses an offer without verifying the signature.
     *
     * Verification needs the vouching device's public key, which only the Mac holds, so it stays
     * with the caller. This returns the exact signed range so the caller can verify it without
     * having to reconstruct anything.
     */
    fun parseOffer(payload: ByteArray): ParseResult<EnrolmentOffer> {
        if (payload.isEmpty() || payload.size > MAX_OFFER_BYTES) {
            return ParseResult.Invalid(RejectReason.TOO_LARGE)
        }
        val text = decodeStrictUtf8(payload) ?: return ParseResult.Invalid(RejectReason.NOT_UTF8)

        // `name` is sanitised of newlines when built, so this separator can only be the real one.
        val separatorIndex = text.lastIndexOf(SIGNATURE_SEPARATOR)
        if (separatorIndex <= 0) return ParseResult.Invalid(RejectReason.BAD_STRUCTURE)

        val bodyText = text.substring(0, separatorIndex)
        val signatureB64 = text.substring(separatorIndex + SIGNATURE_SEPARATOR.length)
        if (signatureB64.isEmpty() || signatureB64 != signatureB64.trim()) {
            return ParseResult.Invalid(RejectReason.BAD_SIGNATURE)
        }
        val signature =
            try {
                Base64.getDecoder().decode(signatureB64)
            } catch (_: IllegalArgumentException) {
                return ParseResult.Invalid(RejectReason.BAD_SIGNATURE)
            }
        if (signature.isEmpty()) return ParseResult.Invalid(RejectReason.BAD_SIGNATURE)

        val lines = bodyText.split('\n')
        if (lines.size != BODY_KEYS.size + 1) return ParseResult.Invalid(RejectReason.BAD_STRUCTURE)
        if (lines.any { it != it.trim() }) return ParseResult.Invalid(RejectReason.BAD_STRUCTURE)
        if (lines[0] != OFFER_PREFIX) return ParseResult.Invalid(RejectReason.BAD_PREFIX)

        val values = ArrayList<String>(BODY_KEYS.size)
        for ((index, key) in BODY_KEYS.withIndex()) {
            val line = lines[index + 1]
            val separator = line.indexOf('=')
            if (separator <= 0) return ParseResult.Invalid(RejectReason.BAD_STRUCTURE)
            if (line.substring(0, separator) != key) {
                return ParseResult.Invalid(RejectReason.BAD_STRUCTURE)
            }
            val value = line.substring(separator + 1)
            if (value.isEmpty()) return ParseResult.Invalid(RejectReason.BAD_STRUCTURE)
            values += value
        }

        val (macId, deviceId, name, publicKeyB64, issuedAtText) = values
        if (!isUuid(macId)) return ParseResult.Invalid(RejectReason.BAD_UUID)
        if (!isUuid(deviceId)) return ParseResult.Invalid(RejectReason.BAD_UUID)

        val publicKeyDer =
            try {
                Base64.getDecoder().decode(publicKeyB64)
            } catch (_: IllegalArgumentException) {
                return ParseResult.Invalid(RejectReason.BAD_PUBLIC_KEY)
            }
        if (publicKeyDer.size != PUBLIC_KEY_DER_BYTES) {
            return ParseResult.Invalid(RejectReason.BAD_PUBLIC_KEY)
        }

        val issuedAt = issuedAtText.toLongOrNull()
        if (issuedAt == null || issuedAt <= 0L) return ParseResult.Invalid(RejectReason.BAD_TIMESTAMP)

        return ParseResult.Valid(
            EnrolmentOffer(
                macInstallationId = macId,
                deviceId = deviceId,
                deviceName = name,
                publicKeyDer = publicKeyDer,
                issuedAtMs = issuedAt,
                signedBytes = bodyText.toByteArray(Charsets.UTF_8),
                signature = signature,
            ),
        )
    }

    /**
     * Checks an offer's claims. The signature itself is the caller's job — it needs the vouching
     * device's public key.
     *
     * [vouchingDeviceId] guards the case that matters most: a device must not be able to enrol
     * itself a second time under a new key, which would let a phone whose pairing you are about
     * to revoke quietly re-authorise itself.
     */
    fun validateOffer(
        offer: EnrolmentOffer,
        expectedMacInstallationId: String,
        vouchingDeviceId: String,
        nowMs: Long,
        ttlMs: Long = OFFER_TTL_MS,
    ): RejectReason? {
        if (!offer.macInstallationId.equalsIgnoreAsciiCase(expectedMacInstallationId)) {
            return RejectReason.UNKNOWN_MAC
        }
        if (offer.deviceId.equalsIgnoreAsciiCase(vouchingDeviceId)) {
            return RejectReason.WRONG_DEVICE
        }
        val age = nowMs - offer.issuedAtMs
        if (age > ttlMs || age < -MAX_FUTURE_SKEW_MS) return RejectReason.CLOCK_SKEW
        return null
    }

    /** Tolerance for an offer minted on a phone whose clock runs slightly ahead of the Mac's. */
    private const val MAX_FUTURE_SKEW_MS = 120_000L
}
