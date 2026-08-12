package com.yukarlo.unlockmymac.util

import java.security.MessageDigest

/**
 * Short, non-reversible label for a challenge payload, safe to show in the UI and write to the
 * event log. The plan forbids logging the password, private key, challenges, or signatures, so
 * a truncated digest is the only identifier that ever leaves the crypto path.
 */
fun challengeTag(payload: ByteArray): String = sha256(payload).copyOf(4).joinToString("") { "%02x".format(it) }

/** Full colon-separated SHA-256 of a public key, for out-of-band comparison with the Mac. */
fun keyFingerprint(publicKeyDer: ByteArray): String = sha256(publicKeyDer).joinToString(":") { "%02X".format(it) }

fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
