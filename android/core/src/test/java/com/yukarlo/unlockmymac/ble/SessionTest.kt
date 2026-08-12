package com.yukarlo.unlockmymac.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

private class FakeClock(
    var now: Long = 0L,
) : ElapsedClock {
    override fun elapsedMs(): Long = now

    fun advance(ms: Long) {
        now += ms
    }
}

private fun request(seed: Byte): ChallengeRequest {
    val challenge =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(ByteArray(32) { seed })
    val payload =
        buildString {
            append(ChallengeCodec.PREFIX).append('\n')
            append("macInstallationId=6f9619ff-8b86-d011-b42d-00cf4fc964ff").append('\n')
            append("deviceId=1b4e28ba-2fa1-11d2-883f-0016d3cca427").append('\n')
            append("issuedAt=1760000000000").append('\n')
            append("challenge=").append(challenge)
        }.toByteArray()
    return (ChallengeCodec.parse(payload) as ParseResult.Valid).value
}

class ReplayCacheTest {
    @Test
    fun `records a key once`() {
        val cache = ReplayCache(capacity = 4)
        assertTrue(cache.recordIfNew("a"))
        assertFalse(cache.recordIfNew("a"))
    }

    @Test
    fun `evicts the least recently used entry past capacity`() {
        val cache = ReplayCache(capacity = 2)
        cache.recordIfNew("a")
        cache.recordIfNew("b")
        cache.recordIfNew("c")

        assertFalse(cache.contains("a"))
        assertTrue(cache.contains("b"))
        assertTrue(cache.contains("c"))
        assertEquals(2, cache.size())
    }

    @Test
    fun `clear forgets everything`() {
        val cache = ReplayCache(capacity = 4)
        cache.recordIfNew("a")
        cache.clear()
        assertTrue(cache.recordIfNew("a"))
    }
}

class ChallengeSessionsTest {
    private val clock = FakeClock()
    private val sessions = ChallengeSessions(clock)

    @Test
    fun `accepts a challenge and signs it once`() {
        assertNull(sessions.offer("aa", request(1), ttlMs = 10_000, requiresApproval = false))

        val first = sessions.claimForSigning("aa")
        assertTrue(first is ChallengeSessions.Result.Sign)

        sessions.attachSignature((first as ChallengeSessions.Result.Sign).pending, byteArrayOf(1, 2, 3))

        // A second read is a blob continuation, not a second signature.
        val second = sessions.claimForSigning("aa")
        assertTrue(second is ChallengeSessions.Result.Cached)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals((second as ChallengeSessions.Result.Cached).signature))
    }

    @Test
    fun `refuses a read with no pending challenge`() {
        val result = sessions.claimForSigning("aa")
        assertEquals(
            RejectReason.NO_PENDING_CHALLENGE,
            (result as ChallengeSessions.Result.Refused).reason,
        )
    }

    @Test
    fun `refuses a second signature when the first attempt left no cached value`() {
        sessions.offer("aa", request(1), ttlMs = 10_000, requiresApproval = false)
        assertTrue(sessions.claimForSigning("aa") is ChallengeSessions.Result.Sign)

        val second = sessions.claimForSigning("aa")
        assertEquals(
            RejectReason.ALREADY_USED,
            (second as ChallengeSessions.Result.Refused).reason,
        )
    }

    @Test
    fun `expires a challenge after its ttl`() {
        sessions.offer("aa", request(1), ttlMs = 10_000, requiresApproval = false)
        clock.advance(10_000)

        val result = sessions.claimForSigning("aa")
        assertEquals(RejectReason.EXPIRED, (result as ChallengeSessions.Result.Refused).reason)
        assertNull(sessions.current("aa"))
    }

    @Test
    fun `rejects a replayed challenge`() {
        sessions.offer("aa", request(1), ttlMs = 10_000, requiresApproval = false)
        assertEquals(
            RejectReason.REPLAY,
            sessions.offer("aa", request(1), ttlMs = 10_000, requiresApproval = false),
        )
    }

    @Test
    fun `rejects a second central while one authentication is in flight`() {
        sessions.offer("aa", request(1), ttlMs = 10_000, requiresApproval = false)
        assertEquals(
            RejectReason.BUSY,
            sessions.offer("bb", request(2), ttlMs = 10_000, requiresApproval = false),
        )
    }

    @Test
    fun `allows another central once the first challenge expires`() {
        sessions.offer("aa", request(1), ttlMs = 10_000, requiresApproval = false)
        clock.advance(10_000)
        assertNull(sessions.offer("bb", request(2), ttlMs = 10_000, requiresApproval = false))
    }

    @Test
    fun `holds a challenge until the user approves it`() {
        sessions.offer("aa", request(1), ttlMs = 60_000, requiresApproval = true)

        val blocked = sessions.claimForSigning("aa")
        assertEquals(
            RejectReason.AWAITING_APPROVAL,
            (blocked as ChallengeSessions.Result.Refused).reason,
        )

        val pending = sessions.awaitingApproval().single()
        sessions.setApproval(pending.id, approved = true)
        assertTrue(sessions.claimForSigning("aa") is ChallengeSessions.Result.Sign)
    }

    @Test
    fun `drops a challenge the user denies`() {
        sessions.offer("aa", request(1), ttlMs = 60_000, requiresApproval = true)
        val pending = sessions.awaitingApproval().single()
        sessions.setApproval(pending.id, approved = false)

        val result = sessions.claimForSigning("aa")
        assertEquals(
            RejectReason.DENIED_BY_USER,
            (result as ChallengeSessions.Result.Refused).reason,
        )
        assertNull(sessions.current("aa"))
    }

    @Test
    fun `disconnect removes all state for that connection`() {
        sessions.offer("aa", request(1), ttlMs = 10_000, requiresApproval = false)
        sessions.remove("aa")

        assertNull(sessions.current("aa"))
        // The replay cache deliberately survives: the same challenge must never be signed again.
        assertEquals(
            RejectReason.REPLAY,
            sessions.offer("aa", request(1), ttlMs = 10_000, requiresApproval = false),
        )
    }

    @Test
    fun `approving an expired challenge is refused`() {
        sessions.offer("aa", request(1), ttlMs = 60_000, requiresApproval = true)
        val pending = sessions.awaitingApproval().single()
        clock.advance(60_000)

        // Regression: a prompt left on screen overnight was approved 10.5 hours later and the
        // approval "succeeded" against a challenge no central would ever read.
        assertNull(sessions.setApproval(pending.id, approved = true))
        assertNull(sessions.current("aa"))
    }

    @Test
    fun `sweep drops expired challenges and reports a lost approval prompt`() {
        sessions.offer("aa", request(1), ttlMs = 60_000, requiresApproval = true)
        assertFalse(sessions.sweepExpired())

        clock.advance(60_000)
        assertTrue(sessions.sweepExpired())
        assertNull(sessions.current("aa"))
        // Nothing left to report on a second pass.
        assertFalse(sessions.sweepExpired())
    }

    @Test
    fun `sweep leaves a live challenge alone`() {
        sessions.offer("aa", request(1), ttlMs = 60_000, requiresApproval = true)
        clock.advance(30_000)

        assertFalse(sessions.sweepExpired())
        assertTrue(sessions.current("aa") != null)
    }

    @Test
    fun `a fresh challenge is accepted after the previous one expired unanswered`() {
        // The scenario that matters: a prompt is ignored, the challenge dies, and the next time
        // the Mac comes into range the whole flow has to work as if nothing happened.
        sessions.offer("aa", request(1), ttlMs = 60_000, requiresApproval = true)
        clock.advance(120_000)
        sessions.sweepExpired()

        // Next contact — new connection, new challenge, approval, signature.
        assertNull(sessions.offer("bb", request(2), ttlMs = 60_000, requiresApproval = true))
        val pending = sessions.awaitingApproval().single()
        sessions.setApproval(pending.id, approved = true)
        assertTrue(sessions.claimForSigning("bb") is ChallengeSessions.Result.Sign)
    }

    @Test
    fun `an abandoned challenge does not block the next one even without a sweep`() {
        // Nothing guarantees the sweep ran first, so `offer` must prune on its own.
        sessions.offer("aa", request(1), ttlMs = 60_000, requiresApproval = true)
        clock.advance(120_000)

        assertNull(sessions.offer("bb", request(2), ttlMs = 60_000, requiresApproval = true))
    }

    @Test
    fun `approval is single shot`() {
        sessions.offer("aa", request(1), ttlMs = 60_000, requiresApproval = true)
        val pending = sessions.awaitingApproval().single()

        assertTrue(sessions.setApproval(pending.id, approved = true) != null)
        assertNull(sessions.setApproval(pending.id, approved = false))
    }
}
