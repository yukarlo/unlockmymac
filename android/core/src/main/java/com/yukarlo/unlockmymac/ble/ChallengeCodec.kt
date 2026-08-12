package com.yukarlo.unlockmymac.ble

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/** Why a request was refused. Never sent over the air — the peer only ever sees [GattStatus]. */
enum class RejectReason {
    TOO_LARGE,
    NOT_UTF8,
    BAD_STRUCTURE,
    BAD_PREFIX,
    BAD_UUID,
    BAD_TIMESTAMP,
    BAD_CHALLENGE,
    UNKNOWN_MAC,
    WRONG_DEVICE,
    CLOCK_SKEW,
    REPLAY,
    NOT_PAIRED,
    BUSY,
    PAIRING_WINDOW_CLOSED,
    BAD_TOKEN,
    NO_PENDING_CHALLENGE,
    EXPIRED,
    ALREADY_USED,
    PREPARED_WRITE,
    BAD_OFFSET,
    SIGNING_FAILED,
    AWAITING_APPROVAL,
    DENIED_BY_USER,
    BAD_PUBLIC_KEY,
    BAD_SIGNATURE,
}

/**
 * A structurally valid challenge from a central.
 *
 * [rawPayload] is the exact byte sequence received. It is what gets signed — never a
 * re-serialised copy, because any normalisation difference would break verification on the Mac.
 */
class ChallengeRequest(
    val rawPayload: ByteArray,
    val macInstallationId: String,
    val deviceId: String,
    val issuedAtMs: Long,
    val challenge: ByteArray,
) {
    /** Stable key for replay detection: the challenge value itself, hex encoded. */
    val challengeKey: String = challenge.toHex()
}

sealed interface ParseResult<out T> {
    class Valid<T>(
        val value: T,
    ) : ParseResult<T>

    class Invalid(
        val reason: RejectReason,
    ) : ParseResult<Nothing>
}

/**
 * Parser for the `mac-ble-unlock:v1` challenge payload:
 *
 * ```
 * mac-ble-unlock:v1
 * macInstallationId=<uuid>
 * deviceId=<uuid>
 * issuedAt=<unix-ms>
 * challenge=<base64url-32-bytes>
 * ```
 *
 * The grammar is strict on purpose: exactly five `\n`-separated lines, fixed key order, no CR,
 * no trailing newline, no surrounding whitespace. A deterministic wire format keeps the bytes
 * we sign unambiguous and gives an attacker no room to smuggle variants past the parser.
 */
object ChallengeCodec {
    const val PREFIX = "mac-ble-unlock:v1"
    const val CHALLENGE_BYTES = 32
    const val MAX_PAYLOAD_BYTES = 512

    /** How far [ChallengeRequest.issuedAtMs] may drift from our own clock, either direction. */
    const val MAX_CLOCK_SKEW_MS = 120_000L

    private val KEYS = listOf("macInstallationId", "deviceId", "issuedAt", "challenge")

    fun parse(payload: ByteArray): ParseResult<ChallengeRequest> {
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES) {
            return ParseResult.Invalid(RejectReason.TOO_LARGE)
        }
        val text = decodeStrictUtf8(payload) ?: return ParseResult.Invalid(RejectReason.NOT_UTF8)

        val lines = text.split('\n')
        if (lines.size != KEYS.size + 1) return ParseResult.Invalid(RejectReason.BAD_STRUCTURE)
        if (lines.any { it != it.trim() }) return ParseResult.Invalid(RejectReason.BAD_STRUCTURE)
        if (lines[0] != PREFIX) return ParseResult.Invalid(RejectReason.BAD_PREFIX)

        val values = ArrayList<String>(KEYS.size)
        for ((index, key) in KEYS.withIndex()) {
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

        val (macId, deviceId, issuedAtText, challengeText) = values
        if (!isUuid(macId) || !isUuid(deviceId)) return ParseResult.Invalid(RejectReason.BAD_UUID)

        val issuedAt = issuedAtText.toLongOrNull()
        if (issuedAt == null || issuedAt <= 0L) return ParseResult.Invalid(RejectReason.BAD_TIMESTAMP)

        val challenge =
            decodeBase64Url(challengeText)
                ?: return ParseResult.Invalid(RejectReason.BAD_CHALLENGE)
        if (challenge.size != CHALLENGE_BYTES) {
            return ParseResult.Invalid(RejectReason.BAD_CHALLENGE)
        }

        return ParseResult.Valid(
            ChallengeRequest(
                rawPayload = payload.copyOf(),
                macInstallationId = macId,
                deviceId = deviceId,
                issuedAtMs = issuedAt,
                challenge = challenge,
            ),
        )
    }

    /**
     * Contextual checks that [parse] cannot make on its own: is this addressed to us, by the
     * Mac we are paired with, at a plausible time?
     */
    fun validate(
        request: ChallengeRequest,
        pairedMacInstallationId: String?,
        ownDeviceId: String,
        nowMs: Long,
        maxSkewMs: Long = MAX_CLOCK_SKEW_MS,
    ): RejectReason? {
        if (pairedMacInstallationId == null) return RejectReason.NOT_PAIRED
        if (!request.macInstallationId.equalsIgnoreAsciiCase(pairedMacInstallationId)) {
            return RejectReason.UNKNOWN_MAC
        }
        if (!request.deviceId.equalsIgnoreAsciiCase(ownDeviceId)) return RejectReason.WRONG_DEVICE
        if (kotlin.math.abs(nowMs - request.issuedAtMs) > maxSkewMs) return RejectReason.CLOCK_SKEW
        return null
    }
}

private val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

internal fun isUuid(value: String): Boolean = UUID_REGEX.matches(value)

/**
 * Case-insensitive comparison. Swift's `UUID.uuidString` is uppercase while Android's is
 * lowercase, so identifiers must round-trip across the two without a case mismatch.
 */
internal fun String.equalsIgnoreAsciiCase(other: String): Boolean = lowercase(Locale.ROOT) == other.lowercase(Locale.ROOT)

/** Decodes UTF-8 with malformed input rejected rather than replaced with U+FFFD. */
internal fun decodeStrictUtf8(bytes: ByteArray): String? =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

internal fun decodeBase64Url(value: String): ByteArray? =
    try {
        Base64.getUrlDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        null
    }

internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val v = byte.toInt() and 0xFF
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
