package com.yukarlo.unlockmymac.ble

import com.yukarlo.unlockmymac.crypto.KeystoreSigner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Runs the shared `protocol-vectors.json` fixtures against the real codec.
 *
 * The same file is meant to be consumed by the macOS app, so a change to the wire format that
 * is not reflected in the vectors breaks this test before it breaks interop.
 */
class ProtocolVectorsTest {
    private val vectors: JSONObject by lazy {
        val text =
            requireNotNull(
                javaClass.classLoader?.getResourceAsStream("protocol-vectors.json"),
            ) { "protocol-vectors.json missing from test resources" }.use { it.readBytes().decodeToString() }
        JSONObject(text)
    }

    @Test
    fun `service uuids match the vectors`() {
        val service = vectors.getJSONObject("service")
        assertEquals(service.getString("serviceUuid"), BleUuids.SERVICE.toString())
        assertEquals(service.getString("challengeCharacteristic"), BleUuids.CHALLENGE.toString())
        assertEquals(service.getString("responseCharacteristic"), BleUuids.RESPONSE.toString())
        assertEquals(service.getString("pairingCharacteristic"), BleUuids.PAIRING.toString())
    }

    @Test
    fun `gatt status codes match the vectors`() {
        val status = vectors.getJSONObject("gattStatus")
        assertEquals(status.getInt("pendingApproval"), GattStatus.PENDING_APPROVAL)
        assertEquals(status.getInt("rejected"), GattStatus.REJECTED)
        assertEquals(status.getInt("denied"), GattStatus.DENIED)
    }

    @Test
    fun `the reference payload parses and validates`() {
        val vector = vectors.getJSONObject("signatureVector")
        val identity = vectors.getJSONObject("identity")
        val payload = Base64.getDecoder().decode(vector.getString("payloadBase64"))

        assertEquals(vector.getInt("payloadLength"), payload.size)

        val request = (ChallengeCodec.parse(payload) as ParseResult.Valid).value
        assertEquals(identity.getString("macInstallationId"), request.macInstallationId)
        assertEquals(identity.getString("deviceId"), request.deviceId)
        assertEquals(vector.getLong("issuedAtMs"), request.issuedAtMs)

        assertNull(
            ChallengeCodec.validate(
                request = request,
                pairedMacInstallationId = identity.getString("macInstallationId"),
                ownDeviceId = identity.getString("deviceId"),
                nowMs = vector.getLong("issuedAtMs"),
            ),
        )
    }

    @Test
    fun `the reference signature verifies against the reference public key`() {
        val vector = vectors.getJSONObject("signatureVector")
        val identity = vectors.getJSONObject("identity")

        val payload = Base64.getDecoder().decode(vector.getString("payloadBase64"))
        val signature = Base64.getDecoder().decode(vector.getString("signatureDerBase64"))
        val publicKeyDer = Base64.getDecoder().decode(identity.getString("publicKeySpkiDerBase64"))

        assertEquals(91, publicKeyDer.size)

        val publicKey =
            KeyFactory
                .getInstance("EC")
                .generatePublic(X509EncodedKeySpec(publicKeyDer))
        val verified =
            Signature.getInstance(KeystoreSigner.SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(payload)
                verify(signature)
            }
        assertTrue("Reference signature must verify", verified)
    }

    @Test
    fun `the reference signature does not verify over a mutated payload`() {
        val vector = vectors.getJSONObject("signatureVector")
        val identity = vectors.getJSONObject("identity")

        val payload = Base64.getDecoder().decode(vector.getString("payloadBase64"))
        // Flip one byte anywhere: the exact bytes received are the bytes signed.
        val tampered = payload.copyOf().also { it[it.size - 44] = ('0' + 1).code.toByte() }
        val signature = Base64.getDecoder().decode(vector.getString("signatureDerBase64"))
        val publicKeyDer = Base64.getDecoder().decode(identity.getString("publicKeySpkiDerBase64"))

        val publicKey =
            KeyFactory
                .getInstance("EC")
                .generatePublic(X509EncodedKeySpec(publicKeyDer))
        val verified =
            Signature.getInstance(KeystoreSigner.SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(tampered)
                verify(signature)
            }
        assertTrue("A mutated payload must not verify", !verified)
    }

    @Test
    fun `grammar constants match the vectors`() {
        val grammar = vectors.getJSONObject("challengeGrammar")
        assertEquals(grammar.getString("prefix"), ChallengeCodec.PREFIX)
        assertEquals(grammar.getInt("challengeBytes"), ChallengeCodec.CHALLENGE_BYTES)
        assertEquals(grammar.getInt("maxPayloadBytes"), ChallengeCodec.MAX_PAYLOAD_BYTES)
        assertEquals(grammar.getLong("maxClockSkewMs"), ChallengeCodec.MAX_CLOCK_SKEW_MS)

        val pairing = vectors.getJSONObject("pairing")
        assertEquals(
            pairing.getJSONObject("claim").getString("prefix"),
            PairingCodec.CLAIM_PREFIX,
        )
        assertEquals(
            pairing.getJSONObject("response").getString("prefix"),
            PairingCodec.RESPONSE_PREFIX,
        )
        assertEquals(
            pairing.getJSONObject("invite").getInt("minTokenBytes"),
            PairingCodec.MIN_TOKEN_BYTES,
        )
    }

    @Test
    fun `timing constants match the vectors`() {
        val timings = vectors.getJSONObject("timings")
        assertEquals(
            timings.getLong("challengeTtlMs"),
            com.yukarlo.unlockmymac.data.Timeouts.CHALLENGE_TTL_MS,
        )
        assertEquals(
            timings.getLong("challengeTtlWithApprovalMs"),
            com.yukarlo.unlockmymac.data.Timeouts.CHALLENGE_TTL_WITH_APPROVAL_MS,
        )
        assertEquals(
            timings.getLong("pairingWindowMs"),
            com.yukarlo.unlockmymac.data.Timeouts.PAIRING_WINDOW_MS,
        )
    }
}
