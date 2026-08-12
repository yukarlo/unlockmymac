package com.yukarlo.unlockmymac.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

private const val MAC_ID = "6F9619FF-8B86-D011-B42D-00CF4FC964FF"
private const val DEVICE_ID = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
private const val NOW = 1_760_000_000_000L

private fun challengeB64(seed: Byte = 7): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { seed })

private fun payload(
    prefix: String = ChallengeCodec.PREFIX,
    macId: String = MAC_ID,
    deviceId: String = DEVICE_ID,
    issuedAt: Long = NOW,
    challenge: String = challengeB64(),
): ByteArray =
    buildString {
        append(prefix).append('\n')
        append("macInstallationId=").append(macId).append('\n')
        append("deviceId=").append(deviceId).append('\n')
        append("issuedAt=").append(issuedAt).append('\n')
        append("challenge=").append(challenge)
    }.toByteArray(Charsets.UTF_8)

private fun reasonOf(bytes: ByteArray): RejectReason? = (ChallengeCodec.parse(bytes) as? ParseResult.Invalid)?.reason

private fun valueOf(bytes: ByteArray): ChallengeRequest = (ChallengeCodec.parse(bytes) as ParseResult.Valid).value

class ChallengeCodecTest {
    @Test
    fun `parses a well formed payload`() {
        val bytes = payload()
        val request = valueOf(bytes)

        assertEquals(MAC_ID, request.macInstallationId)
        assertEquals(DEVICE_ID, request.deviceId)
        assertEquals(NOW, request.issuedAtMs)
        assertEquals(32, request.challenge.size)
        // The bytes we sign must be byte-identical to what arrived on the wire.
        assertTrue(bytes.contentEquals(request.rawPayload))
    }

    @Test
    fun `rejects a wrong prefix`() {
        assertEquals(RejectReason.BAD_PREFIX, reasonOf(payload(prefix = "mac-ble-unlock:v2")))
    }

    @Test
    fun `rejects a missing line`() {
        val truncated = payload().decodeToString().substringBeforeLast('\n').toByteArray()
        assertEquals(RejectReason.BAD_STRUCTURE, reasonOf(truncated))
    }

    @Test
    fun `rejects an extra line`() {
        val extra = (payload().decodeToString() + "\nextra=1").toByteArray()
        assertEquals(RejectReason.BAD_STRUCTURE, reasonOf(extra))
    }

    @Test
    fun `rejects a trailing newline`() {
        val trailing = (payload().decodeToString() + "\n").toByteArray()
        assertEquals(RejectReason.BAD_STRUCTURE, reasonOf(trailing))
    }

    @Test
    fun `rejects CRLF line endings`() {
        val crlf = payload().decodeToString().replace("\n", "\r\n").toByteArray()
        assertEquals(RejectReason.BAD_STRUCTURE, reasonOf(crlf))
    }

    @Test
    fun `rejects surrounding whitespace on a value`() {
        val padded =
            payload()
                .decodeToString()
                .replace("deviceId=$DEVICE_ID", "deviceId=$DEVICE_ID ")
                .toByteArray()
        assertEquals(RejectReason.BAD_STRUCTURE, reasonOf(padded))
    }

    @Test
    fun `rejects reordered keys`() {
        val reordered =
            buildString {
                append(ChallengeCodec.PREFIX).append('\n')
                append("deviceId=").append(DEVICE_ID).append('\n')
                append("macInstallationId=").append(MAC_ID).append('\n')
                append("issuedAt=").append(NOW).append('\n')
                append("challenge=").append(challengeB64())
            }.toByteArray()
        assertEquals(RejectReason.BAD_STRUCTURE, reasonOf(reordered))
    }

    @Test
    fun `rejects a non uuid identifier`() {
        assertEquals(RejectReason.BAD_UUID, reasonOf(payload(macId = "not-a-uuid")))
        assertEquals(RejectReason.BAD_UUID, reasonOf(payload(deviceId = "1234")))
    }

    @Test
    fun `rejects a non numeric timestamp`() {
        val bad = payload().decodeToString().replace("issuedAt=$NOW", "issuedAt=soon").toByteArray()
        assertEquals(RejectReason.BAD_TIMESTAMP, reasonOf(bad))
    }

    @Test
    fun `rejects a challenge that is not exactly 32 bytes`() {
        val short = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31))
        val long = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(33))
        assertEquals(RejectReason.BAD_CHALLENGE, reasonOf(payload(challenge = short)))
        assertEquals(RejectReason.BAD_CHALLENGE, reasonOf(payload(challenge = long)))
    }

    @Test
    fun `rejects standard base64 in the challenge field`() {
        // '+' and '/' are base64, not base64url. Accepting both would make the same challenge
        // encodable two ways and defeat the replay cache.
        // All-ones bytes encode to all-'/' in the standard alphabet, all-'_' in base64url.
        val standard =
            Base64
                .getEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(32) { 0xFF.toByte() })
        assertTrue(standard.contains('/'))
        assertEquals(RejectReason.BAD_CHALLENGE, reasonOf(payload(challenge = standard)))
    }

    @Test
    fun `rejects invalid utf8`() {
        val invalid = byteArrayOf(0xC3.toByte(), 0x28) + payload()
        assertEquals(RejectReason.NOT_UTF8, reasonOf(invalid))
    }

    @Test
    fun `rejects an oversized payload`() {
        val huge = ByteArray(ChallengeCodec.MAX_PAYLOAD_BYTES + 1) { 'a'.code.toByte() }
        assertEquals(RejectReason.TOO_LARGE, reasonOf(huge))
    }

    @Test
    fun `accepts a request addressed to us by our paired mac`() {
        val request = valueOf(payload())
        assertNull(ChallengeCodec.validate(request, MAC_ID, DEVICE_ID, NOW))
    }

    @Test
    fun `identifier comparison ignores case so swift and android uuids interoperate`() {
        val request = valueOf(payload())
        assertNull(
            ChallengeCodec.validate(request, MAC_ID.lowercase(), DEVICE_ID.uppercase(), NOW),
        )
    }

    @Test
    fun `rejects when not paired`() {
        val request = valueOf(payload())
        assertEquals(
            RejectReason.NOT_PAIRED,
            ChallengeCodec.validate(request, null, DEVICE_ID, NOW),
        )
    }

    @Test
    fun `rejects a challenge from an unknown mac`() {
        val request = valueOf(payload())
        assertEquals(
            RejectReason.UNKNOWN_MAC,
            ChallengeCodec.validate(request, "00000000-0000-0000-0000-000000000001", DEVICE_ID, NOW),
        )
    }

    @Test
    fun `rejects a challenge addressed to another device`() {
        val request = valueOf(payload())
        assertEquals(
            RejectReason.WRONG_DEVICE,
            ChallengeCodec.validate(request, MAC_ID, "00000000-0000-0000-0000-000000000002", NOW),
        )
    }

    @Test
    fun `rejects a stale or future timestamp`() {
        val request = valueOf(payload())
        val fiveMinutes = 5 * 60_000L
        assertEquals(
            RejectReason.CLOCK_SKEW,
            ChallengeCodec.validate(request, MAC_ID, DEVICE_ID, NOW + fiveMinutes),
        )
        assertEquals(
            RejectReason.CLOCK_SKEW,
            ChallengeCodec.validate(request, MAC_ID, DEVICE_ID, NOW - fiveMinutes),
        )
    }

    @Test
    fun `accepts a timestamp inside the skew window`() {
        val request = valueOf(payload())
        assertNull(ChallengeCodec.validate(request, MAC_ID, DEVICE_ID, NOW + 119_000L))
        assertNull(ChallengeCodec.validate(request, MAC_ID, DEVICE_ID, NOW - 119_000L))
    }

    @Test
    fun `different challenges produce different replay keys`() {
        val first = valueOf(payload(challenge = challengeB64(seed = 1)))
        val second = valueOf(payload(challenge = challengeB64(seed = 2)))
        assertTrue(first.challengeKey != second.challengeKey)
    }
}
