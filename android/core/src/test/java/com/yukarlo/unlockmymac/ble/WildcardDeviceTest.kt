package com.yukarlo.unlockmymac.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A challenge may name a specific device or the wildcard. Both must behave, because the wildcard
 * is what stops a Mac with several devices paired burning a round trip per wrong guess.
 */
class WildcardDeviceTest {
    private val macId = "6F9619FF-8B86-D011-B42D-00CF4FC964FF"
    private val ownId = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
    private val otherId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val issuedAt = 1_760_000_000_000L

    private fun challenge(deviceId: String): ChallengeRequest {
        val payload =
            buildString {
                append(ChallengeCodec.PREFIX).append('\n')
                append("macInstallationId=").append(macId).append('\n')
                append("deviceId=").append(deviceId).append('\n')
                append("issuedAt=").append(issuedAt).append('\n')
                append("challenge=").append("A".repeat(43))
            }.toByteArray(Charsets.UTF_8)
        return (ChallengeCodec.parse(payload) as ParseResult.Valid).value
    }

    private fun validate(deviceId: String) =
        ChallengeCodec.validate(
            request = challenge(deviceId),
            pairedMacInstallationId = macId,
            ownDeviceId = ownId,
            nowMs = issuedAt,
        )

    @Test
    fun `a wildcard challenge is accepted`() {
        assertNull(validate(ChallengeCodec.ANY_DEVICE))
    }

    @Test
    fun `a challenge naming this device is accepted`() {
        assertNull(validate(ownId))
    }

    @Test
    fun `a challenge naming another device is still refused`() {
        assertEquals(RejectReason.WRONG_DEVICE, validate(otherId))
    }

    @Test
    fun `a deviceId that is neither a uuid nor the wildcard is malformed`() {
        val payload =
            buildString {
                append(ChallengeCodec.PREFIX).append('\n')
                append("macInstallationId=").append(macId).append('\n')
                append("deviceId=not-a-uuid\n")
                append("issuedAt=").append(issuedAt).append('\n')
                append("challenge=").append("A".repeat(43))
            }.toByteArray(Charsets.UTF_8)
        assertEquals(
            RejectReason.BAD_UUID,
            (ChallengeCodec.parse(payload) as ParseResult.Invalid).reason,
        )
    }

    @Test
    fun `the wildcard matches the vectors`() {
        val vectors =
            org.json.JSONObject(
                requireNotNull(
                    javaClass.classLoader?.getResourceAsStream("protocol-vectors.json"),
                ).use { it.readBytes().decodeToString() },
            )
        assertEquals(
            vectors.getJSONObject("challengeGrammar").getString("anyDeviceId"),
            ChallengeCodec.ANY_DEVICE,
        )
    }
}
