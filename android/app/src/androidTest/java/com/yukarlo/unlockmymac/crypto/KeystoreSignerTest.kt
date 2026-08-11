package com.yukarlo.unlockmymac.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Proves the Android signing path produces something the Mac can verify.
 *
 * The Mac loads our key with `P256.Signing.PublicKey(derRepresentation:)` and the signature
 * with `ECDSASignature(derRepresentation:)`. Re-importing the exported SPKI through a plain
 * JCA `KeyFactory` here exercises the same "standard DER, no Android specifics" assumption.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSignerTest {
    private val signer = KeystoreSigner()

    @Before
    fun setUp() {
        signer.deleteKey()
        signer.ensureKey()
    }

    @After
    fun tearDown() {
        signer.deleteKey()
    }

    @Test
    fun signatureVerifiesAgainstTheExportedPublicKey() {
        val payload = "mac-ble-unlock:v1\ntest-payload".toByteArray()
        val signature = signer.sign(payload)

        assertTrue("DER ECDSA signatures start with SEQUENCE", signature[0] == 0x30.toByte())
        assertTrue(verify(signer.identity().publicKeyDer, payload, signature))
    }

    @Test
    fun aTamperedPayloadDoesNotVerify() {
        val payload = "mac-ble-unlock:v1\ntest-payload".toByteArray()
        val signature = signer.sign(payload)
        val tampered = payload.copyOf().also { it[it.size - 1] = 'X'.code.toByte() }

        assertFalse(verify(signer.identity().publicKeyDer, tampered, signature))
    }

    @Test
    fun theExportedKeyIsAnX509SubjectPublicKeyInfo() {
        val der = signer.identity().publicKeyDer
        // 0x30 SEQUENCE header, and a P-256 SPKI is 91 bytes.
        assertEquals(0x30.toByte(), der[0])
        assertEquals(91, der.size)
    }

    @Test
    fun ensureKeyIsIdempotent() {
        val first = signer.identity().fingerprint
        signer.ensureKey()
        assertEquals(first, signer.identity().fingerprint)
    }

    @Test
    fun deletingTheKeyProducesANewIdentity() {
        val first = signer.identity().fingerprint
        signer.deleteKey()
        signer.ensureKey()

        assertFalse(first == signer.identity().fingerprint)
    }

    @Test
    fun theFingerprintIsAFullSha256() {
        // 32 bytes rendered as colon-separated hex pairs.
        assertEquals(
            32,
            signer
                .identity()
                .fingerprint
                .split(":")
                .size,
        )
    }

    private fun verify(
        publicKeyDer: ByteArray,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val publicKey =
            KeyFactory
                .getInstance("EC")
                .generatePublic(X509EncodedKeySpec(publicKeyDer))
        return Signature.getInstance(KeystoreSigner.SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(payload)
            verify(signature)
        }
    }
}
