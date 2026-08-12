package com.yukarlo.unlockmymac.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.yukarlo.unlockmymac.util.keyFingerprint
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

enum class KeySecurityLevel { SOFTWARE, TRUSTED_ENVIRONMENT, STRONGBOX, UNKNOWN }

class KeyIdentity(
    /** X.509 SubjectPublicKeyInfo DER. This is what the Mac loads with `P256.Signing.PublicKey`. */
    val publicKeyDer: ByteArray,
    val fingerprint: String,
    val securityLevel: KeySecurityLevel,
)

/**
 * The phone's identity: one non-exportable P-256 key in `AndroidKeyStore`.
 *
 * No `setUserAuthenticationRequired` — signing has to work with the screen off for the Mac's
 * heartbeat to survive overnight. Presence of the key is therefore proof the phone is present,
 * not proof the user is present; the manual-approval setting exists for when you want the
 * stronger claim.
 */
class KeystoreSigner {
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(PROVIDER).apply { load(null) }
    }

    /** Creates the keypair if it does not exist yet. Safe to call repeatedly. */
    @Synchronized
    fun ensureKey() {
        if (keyStore.containsAlias(KEY_ALIAS)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(
            KeyGenParameterSpec
                .Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                ).setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        generator.generateKeyPair()
    }

    fun hasKey(): Boolean = keyStore.containsAlias(KEY_ALIAS)

    /** Public material only. The private key has no export path by construction. */
    fun identity(): KeyIdentity {
        ensureKey()
        val publicKey =
            requireNotNull(keyStore.getCertificate(KEY_ALIAS)?.publicKey) {
                "Keystore entry $KEY_ALIAS has no certificate"
            }
        val der = publicKey.encoded
        return KeyIdentity(
            publicKeyDer = der,
            fingerprint = keyFingerprint(der),
            securityLevel = securityLevel(),
        )
    }

    /** Signs the exact bytes given. Returns a DER-encoded ECDSA signature. */
    fun sign(payload: ByteArray): ByteArray {
        ensureKey()
        val entry =
            keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: error("Keystore entry $KEY_ALIAS is not a private key entry")
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(entry.privateKey)
            update(payload)
            sign()
        }
    }

    /** Deletes the identity. Used when unpairing so the old public key becomes worthless. */
    @Synchronized
    fun deleteKey() {
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun securityLevel(): KeySecurityLevel =
        runCatching {
            val privateKey =
                keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
                    ?: return@runCatching KeySecurityLevel.UNKNOWN
            val info =
                KeyFactory
                    .getInstance(privateKey.algorithm, PROVIDER)
                    .getKeySpec(privateKey, KeyInfo::class.java)
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurityLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurityLevel.TRUSTED_ENVIRONMENT
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeySecurityLevel.SOFTWARE
                else -> KeySecurityLevel.UNKNOWN
            }
        }.getOrDefault(KeySecurityLevel.UNKNOWN)

    companion object {
        const val KEY_ALIAS = "MacBleUnlockKey"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val PROVIDER = "AndroidKeyStore"
        private const val CURVE = "secp256r1"
    }
}
