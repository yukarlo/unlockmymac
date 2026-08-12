package com.yukarlo.unlockmymac.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The enrolment path is the one place a device other than the user authorises a new credential,
 * so these lean on the rejection cases rather than the happy path.
 */
class EnrolmentCodecTest {
    private val macId = "6F9619FF-8B86-D011-B42D-00CF4FC964FF"
    private val phoneId = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
    private val watchId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val issuedAt = 1_760_000_000_000L

    private val phoneKeys: KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()

    private val watchKeys: KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()

    private fun signWithPhone(bytes: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(phoneKeys.private)
            update(bytes)
            sign()
        }

    private fun buildValidOffer(
        deviceId: String = watchId,
        name: String = "Galaxy Watch6",
        issuedAtMs: Long = issuedAt,
    ): ByteArray =
        requireNotNull(
            EnrolmentCodec.buildOffer(
                macInstallationId = macId,
                deviceId = deviceId,
                deviceName = name,
                publicKeyDer = watchKeys.public.encoded,
                issuedAtMs = issuedAtMs,
                sign = ::signWithPhone,
            ),
        )

    private fun verify(offer: EnrolmentOffer): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(phoneKeys.public)
            update(offer.signedBytes)
            verify(offer.signature)
        }

    @Test
    fun `a signed offer round trips and verifies`() {
        val parsed = EnrolmentCodec.parseOffer(buildValidOffer()) as ParseResult.Valid
        val offer = parsed.value

        assertEquals(macId, offer.macInstallationId)
        assertEquals(watchId, offer.deviceId)
        assertEquals("Galaxy Watch6", offer.deviceName)
        assertArrayEquals(watchKeys.public.encoded, offer.publicKeyDer)
        assertEquals(issuedAt, offer.issuedAtMs)
        assertTrue("Offer must verify under the vouching device's key", verify(offer))
    }

    @Test
    fun `the signature covers the body but not the signature line`() {
        val bytes = buildValidOffer()
        val offer = (EnrolmentCodec.parseOffer(bytes) as ParseResult.Valid).value
        val expectedBody = String(bytes, Charsets.UTF_8).substringBeforeLast("\nsignature=")
        assertArrayEquals(expectedBody.toByteArray(Charsets.UTF_8), offer.signedBytes)
    }

    @Test
    fun `a tampered public key does not verify`() {
        val bytes = buildValidOffer()
        val text = String(bytes, Charsets.UTF_8)
        // Swap in a different key while keeping the original signature.
        val forged =
            text.replaceFirst(
                Regex("publicKey=[^\n]+"),
                "publicKey=" +
                    java.util.Base64
                        .getEncoder()
                        .encodeToString(phoneKeys.public.encoded),
            )
        val offer = (EnrolmentCodec.parseOffer(forged.toByteArray(Charsets.UTF_8)) as ParseResult.Valid).value
        assertTrue("A substituted key must not verify", !verify(offer))
    }

    @Test
    fun `an offer for another Mac is rejected`() {
        val offer = (EnrolmentCodec.parseOffer(buildValidOffer()) as ParseResult.Valid).value
        assertEquals(
            RejectReason.UNKNOWN_MAC,
            EnrolmentCodec.validateOffer(
                offer = offer,
                expectedMacInstallationId = "00000000-0000-0000-0000-000000000000",
                vouchingDeviceId = phoneId,
                nowMs = issuedAt,
            ),
        )
    }

    @Test
    fun `a device cannot vouch for itself`() {
        val offer = (EnrolmentCodec.parseOffer(buildValidOffer(deviceId = phoneId)) as ParseResult.Valid).value
        assertEquals(
            RejectReason.WRONG_DEVICE,
            EnrolmentCodec.validateOffer(
                offer = offer,
                expectedMacInstallationId = macId,
                vouchingDeviceId = phoneId,
                nowMs = issuedAt,
            ),
        )
    }

    @Test
    fun `an offer past its ttl is rejected`() {
        val offer = (EnrolmentCodec.parseOffer(buildValidOffer()) as ParseResult.Valid).value
        assertEquals(
            RejectReason.CLOCK_SKEW,
            EnrolmentCodec.validateOffer(
                offer = offer,
                expectedMacInstallationId = macId,
                vouchingDeviceId = phoneId,
                nowMs = issuedAt + EnrolmentCodec.OFFER_TTL_MS + 1,
            ),
        )
    }

    @Test
    fun `a fresh offer inside the ttl is accepted`() {
        val offer = (EnrolmentCodec.parseOffer(buildValidOffer()) as ParseResult.Valid).value
        assertNull(
            EnrolmentCodec.validateOffer(
                offer = offer,
                expectedMacInstallationId = macId,
                vouchingDeviceId = phoneId,
                nowMs = issuedAt + EnrolmentCodec.OFFER_TTL_MS - 1,
            ),
        )
    }

    @Test
    fun `structural rejections`() {
        fun reason(text: String): RejectReason = (EnrolmentCodec.parseOffer(text.toByteArray(Charsets.UTF_8)) as ParseResult.Invalid).reason

        val valid = String(buildValidOffer(), Charsets.UTF_8)

        assertEquals(RejectReason.BAD_PREFIX, reason(valid.replaceFirst("mac-ble-enrol:v1", "mac-ble-enrol:v2")))
        assertEquals(RejectReason.BAD_STRUCTURE, reason(valid.substringBeforeLast("\nsignature=")))
        assertEquals(
            RejectReason.BAD_PUBLIC_KEY,
            reason(valid.replaceFirst(Regex("publicKey=[^\n]+"), "publicKey=c2hvcnQ=")),
        )
        assertEquals(
            RejectReason.BAD_UUID,
            reason(valid.replaceFirst(Regex("deviceId=[^\n]+"), "deviceId=not-a-uuid")),
        )
        assertEquals(
            RejectReason.BAD_TIMESTAMP,
            reason(valid.replaceFirst(Regex("issuedAt=[^\n]+"), "issuedAt=soon")),
        )
    }

    @Test
    fun `grammar constants match the vectors`() {
        val vectors = loadVectors()
        val enrolment = vectors.getJSONObject("enrolment")
        val offer = enrolment.getJSONObject("offer")

        assertEquals(offer.getString("prefix"), EnrolmentCodec.OFFER_PREFIX)
        assertEquals(offer.getString("signatureKey"), EnrolmentCodec.SIGNATURE_KEY)
        assertEquals(offer.getInt("publicKeyLengthBytes"), EnrolmentCodec.PUBLIC_KEY_DER_BYTES)
        assertEquals(offer.getInt("maxOfferBytes"), EnrolmentCodec.MAX_OFFER_BYTES)
        assertEquals(enrolment.getLong("offerTtlMs"), EnrolmentCodec.OFFER_TTL_MS)
    }

    private fun loadVectors() =
        org.json.JSONObject(
            requireNotNull(
                javaClass.classLoader?.getResourceAsStream("protocol-vectors.json"),
            ) { "protocol-vectors.json missing from test resources" }.use { it.readBytes().decodeToString() },
        )
}
