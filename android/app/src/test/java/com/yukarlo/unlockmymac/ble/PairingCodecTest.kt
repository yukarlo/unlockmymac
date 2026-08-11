package com.yukarlo.unlockmymac.ble

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

private const val MAC_ID = "6F9619FF-8B86-D011-B42D-00CF4FC964FF"
private const val DEVICE_ID = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
private const val NOW = 1_760_000_000_000L

private fun token(seed: Byte = 3): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24) { seed })

private fun inviteJson(
    version: Int = 1,
    macId: String = MAC_ID,
    tokenValue: String = token(),
    exp: Long = NOW + 60_000,
    name: String = "Karlo's MacBook",
): String =
    JSONObject()
        .put("v", version)
        .put("macInstallationId", macId)
        .put("token", tokenValue)
        .put("exp", exp)
        .put("name", name)
        .put("keyFormat", "x509-spki-der-p256")
        .toString()

private fun claimBytes(
    prefix: String = PairingCodec.CLAIM_PREFIX,
    macId: String = MAC_ID,
    tokenValue: String = token(),
    issuedAt: Long = NOW,
): ByteArray =
    buildString {
        append(prefix).append('\n')
        append("macInstallationId=").append(macId).append('\n')
        append("token=").append(tokenValue).append('\n')
        append("issuedAt=").append(issuedAt)
    }.toByteArray(Charsets.UTF_8)

private fun invite(json: String = inviteJson()): PairingInvite = (PairingCodec.parseInvite(json) as ParseResult.Valid).value

private fun claim(bytes: ByteArray = claimBytes()): PairingClaim = (PairingCodec.parseClaim(bytes) as ParseResult.Valid).value

private fun inviteReason(json: String): RejectReason? = (PairingCodec.parseInvite(json) as? ParseResult.Invalid)?.reason

private fun claimReason(bytes: ByteArray): RejectReason? = (PairingCodec.parseClaim(bytes) as? ParseResult.Invalid)?.reason

class PairingCodecTest {
    @Test
    fun `parses a well formed invite`() {
        val parsed = invite()
        assertEquals(MAC_ID, parsed.macInstallationId)
        assertEquals(token(), parsed.token)
        assertEquals("Karlo's MacBook", parsed.macName)
        assertEquals(NOW + 60_000, parsed.expiresAtMs)
    }

    @Test
    fun `rejects malformed invite json`() {
        assertEquals(RejectReason.BAD_STRUCTURE, inviteReason("not json"))
    }

    @Test
    fun `rejects an unknown invite version`() {
        assertEquals(RejectReason.BAD_STRUCTURE, inviteReason(inviteJson(version = 2)))
    }

    @Test
    fun `rejects an invite with a bad uuid`() {
        assertEquals(RejectReason.BAD_UUID, inviteReason(inviteJson(macId = "nope")))
    }

    @Test
    fun `rejects an invite token that is too short to be random`() {
        val weak = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(8))
        assertEquals(RejectReason.BAD_TOKEN, inviteReason(inviteJson(tokenValue = weak)))
    }

    @Test
    fun `rejects an invite with no expiry`() {
        assertEquals(RejectReason.BAD_TIMESTAMP, inviteReason(inviteJson(exp = 0)))
    }

    @Test
    fun `parses a well formed claim`() {
        val parsed = claim()
        assertEquals(MAC_ID, parsed.macInstallationId)
        assertEquals(token(), parsed.token)
        assertEquals(NOW, parsed.issuedAtMs)
    }

    @Test
    fun `rejects a claim with the wrong prefix`() {
        assertEquals(RejectReason.BAD_PREFIX, claimReason(claimBytes(prefix = "mac-ble-pair:v9")))
    }

    @Test
    fun `rejects a claim with a trailing newline`() {
        val trailing = (claimBytes().decodeToString() + "\n").toByteArray()
        assertEquals(RejectReason.BAD_STRUCTURE, claimReason(trailing))
    }

    @Test
    fun `accepts a claim matching the scanned invite`() {
        assertNull(PairingCodec.validateClaim(claim(), invite(), NOW))
    }

    @Test
    fun `rejects a claim whose token does not match`() {
        val other = claim(claimBytes(tokenValue = token(seed = 9)))
        assertEquals(RejectReason.BAD_TOKEN, PairingCodec.validateClaim(other, invite(), NOW))
    }

    @Test
    fun `rejects a claim from a different mac`() {
        val other = claim(claimBytes(macId = "00000000-0000-0000-0000-000000000001"))
        assertEquals(RejectReason.UNKNOWN_MAC, PairingCodec.validateClaim(other, invite(), NOW))
    }

    @Test
    fun `rejects a claim after the invite expires`() {
        assertEquals(
            RejectReason.PAIRING_WINDOW_CLOSED,
            PairingCodec.validateClaim(claim(), invite(), NOW + 120_000),
        )
    }

    @Test
    fun `rejects a claim with a skewed timestamp`() {
        val skewed = claim(claimBytes(issuedAt = NOW - 300_000))
        assertEquals(RejectReason.CLOCK_SKEW, PairingCodec.validateClaim(skewed, invite(), NOW))
    }

    @Test
    fun `builds a response the mac can parse back`() {
        val publicKey = ByteArray(91) { it.toByte() }
        val text = PairingCodec.buildResponse(DEVICE_ID, "Pixel 8", publicKey).decodeToString()
        val lines = text.split('\n')

        assertEquals(PairingCodec.RESPONSE_PREFIX, lines[0])
        assertEquals("deviceId=$DEVICE_ID", lines[1])
        assertEquals("name=Pixel 8", lines[2])
        val encoded = lines[3].removePrefix("publicKey=")
        assertTrue(publicKey.contentEquals(Base64.getDecoder().decode(encoded)))
    }

    @Test
    fun `strips newlines from the device name so the response stays parseable`() {
        val text = PairingCodec.buildResponse(DEVICE_ID, "Evil\nname=x", ByteArray(4)).decodeToString()
        assertEquals(4, text.split('\n').size)
    }
}
