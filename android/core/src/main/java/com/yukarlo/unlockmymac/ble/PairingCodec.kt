package com.yukarlo.unlockmymac.ble

import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/** Contents of the QR code the Mac displays during pairing. Carries no secret key material. */
class PairingInvite(
    val macInstallationId: String,
    val token: String,
    val macName: String,
    val expiresAtMs: Long,
)

/** A `mac-ble-pair:v1` write from a central on the pairing characteristic. */
class PairingClaim(
    val macInstallationId: String,
    val token: String,
    val issuedAtMs: Long,
)

/**
 * Pairing exchange codec.
 *
 * The Mac shows a QR containing its installation id and a short-lived random token. The user
 * scans it here, which opens a time-boxed window. The Mac then writes that same token to the
 * pairing characteristic and reads our identity back. The token is a *pairing* secret only —
 * possessing it never authenticates an unlock, so its weakness is bounded by the window.
 */
object PairingCodec {
    const val CLAIM_PREFIX = "mac-ble-pair:v1"
    const val RESPONSE_PREFIX = "mac-ble-pair-resp:v1"
    const val MAX_CLAIM_BYTES = 512
    const val MIN_TOKEN_BYTES = 16
    const val MAX_CLOCK_SKEW_MS = 120_000L

    private val CLAIM_KEYS = listOf("macInstallationId", "token", "issuedAt")

    /** Parses the JSON payload of the Mac's QR code. */
    fun parseInvite(qrText: String): ParseResult<PairingInvite> {
        val json =
            try {
                JSONObject(qrText)
            } catch (_: JSONException) {
                return ParseResult.Invalid(RejectReason.BAD_STRUCTURE)
            }

        if (json.optInt("v", -1) != 1) return ParseResult.Invalid(RejectReason.BAD_STRUCTURE)

        val macId = json.optString("macInstallationId")
        if (!isUuid(macId)) return ParseResult.Invalid(RejectReason.BAD_UUID)

        val token = json.optString("token")
        if (!isValidToken(token)) return ParseResult.Invalid(RejectReason.BAD_TOKEN)

        val expiresAt = json.optLong("exp", 0L)
        if (expiresAt <= 0L) return ParseResult.Invalid(RejectReason.BAD_TIMESTAMP)

        val name = json.optString("name").ifBlank { "Mac" }

        return ParseResult.Valid(
            PairingInvite(
                macInstallationId = macId,
                token = token,
                macName = name.take(64),
                expiresAtMs = expiresAt,
            ),
        )
    }

    /**
     * Parses a claim written to the pairing characteristic:
     *
     * ```
     * mac-ble-pair:v1
     * macInstallationId=<uuid>
     * token=<base64url>
     * issuedAt=<unix-ms>
     * ```
     */
    fun parseClaim(payload: ByteArray): ParseResult<PairingClaim> {
        if (payload.isEmpty() || payload.size > MAX_CLAIM_BYTES) {
            return ParseResult.Invalid(RejectReason.TOO_LARGE)
        }
        val text = decodeStrictUtf8(payload) ?: return ParseResult.Invalid(RejectReason.NOT_UTF8)

        val lines = text.split('\n')
        if (lines.size != CLAIM_KEYS.size + 1) return ParseResult.Invalid(RejectReason.BAD_STRUCTURE)
        if (lines.any { it != it.trim() }) return ParseResult.Invalid(RejectReason.BAD_STRUCTURE)
        if (lines[0] != CLAIM_PREFIX) return ParseResult.Invalid(RejectReason.BAD_PREFIX)

        val values = ArrayList<String>(CLAIM_KEYS.size)
        for ((index, key) in CLAIM_KEYS.withIndex()) {
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

        val (macId, token, issuedAtText) = values
        if (!isUuid(macId)) return ParseResult.Invalid(RejectReason.BAD_UUID)
        if (!isValidToken(token)) return ParseResult.Invalid(RejectReason.BAD_TOKEN)

        val issuedAt = issuedAtText.toLongOrNull()
        if (issuedAt == null || issuedAt <= 0L) return ParseResult.Invalid(RejectReason.BAD_TIMESTAMP)

        return ParseResult.Valid(PairingClaim(macId, token, issuedAt))
    }

    /** Checks a parsed claim against the invite the user actually scanned. */
    fun validateClaim(
        claim: PairingClaim,
        invite: PairingInvite,
        nowMs: Long,
        maxSkewMs: Long = MAX_CLOCK_SKEW_MS,
    ): RejectReason? {
        if (!claim.macInstallationId.equalsIgnoreAsciiCase(invite.macInstallationId)) {
            return RejectReason.UNKNOWN_MAC
        }
        if (!constantTimeEquals(claim.token, invite.token)) return RejectReason.BAD_TOKEN
        if (nowMs > invite.expiresAtMs) return RejectReason.PAIRING_WINDOW_CLOSED
        if (kotlin.math.abs(nowMs - claim.issuedAtMs) > maxSkewMs) return RejectReason.CLOCK_SKEW
        return null
    }

    /**
     * Builds the identity blob the Mac reads back:
     *
     * ```
     * mac-ble-pair-resp:v1
     * deviceId=<uuid>
     * name=<friendly name>
     * publicKey=<base64 X.509 SubjectPublicKeyInfo DER>
     * ```
     */
    fun buildResponse(
        deviceId: String,
        deviceName: String,
        publicKeyDer: ByteArray,
    ): ByteArray {
        val safeName =
            deviceName
                .replace('\n', ' ')
                .trim()
                .take(64)
                .ifEmpty { "Android" }
        val encodedKey = Base64.getEncoder().encodeToString(publicKeyDer)
        return buildString {
            append(RESPONSE_PREFIX).append('\n')
            append("deviceId=").append(deviceId).append('\n')
            append("name=").append(safeName).append('\n')
            append("publicKey=").append(encodedKey)
        }.toByteArray(Charsets.UTF_8)
    }

    private fun isValidToken(token: String): Boolean {
        val decoded = decodeBase64Url(token) ?: return false
        return decoded.size >= MIN_TOKEN_BYTES
    }
}

/**
 * Length-independent-of-content comparison. The pairing token is low-value and short-lived,
 * but comparing it in constant time costs nothing and keeps the habit.
 */
internal fun constantTimeEquals(
    a: String,
    b: String,
): Boolean {
    val left = a.toByteArray(Charsets.UTF_8)
    val right = b.toByteArray(Charsets.UTF_8)
    if (left.size != right.size) return false
    var diff = 0
    for (i in left.indices) diff = diff or (left[i].toInt() xor right[i].toInt())
    return diff == 0
}
