package com.yukarlo.unlockmymac.ble

import java.util.concurrent.atomic.AtomicLong

/** Monotonic clock source. Injected so session expiry is testable without waiting. */
fun interface ElapsedClock {
    fun elapsedMs(): Long
}

enum class ApprovalState {
    /** Manual approval is off; the challenge may be signed immediately. */
    NOT_REQUIRED,
    PENDING,
    APPROVED,
    DENIED,
}

/**
 * One challenge held for one connection, valid for a single signature.
 *
 * [signature] is cached after the first read so ATT read-blob continuations under the default
 * 23-byte MTU return a consistent value instead of re-signing per fragment (ECDSA is
 * randomised, so re-signing would hand the central a spliced, unverifiable blob).
 */
class PendingChallenge(
    val id: Long,
    val connectionKey: String,
    val request: ChallengeRequest,
    val expiresAtElapsedMs: Long,
    approvalState: ApprovalState,
) {
    @Volatile
    var approval: ApprovalState = approvalState
        internal set

    @Volatile
    var used: Boolean = false
        internal set

    @Volatile
    var signature: ByteArray? = null
        internal set

    fun isExpired(nowElapsedMs: Long): Boolean = nowElapsedMs >= expiresAtElapsedMs
}

/**
 * Holds at most one in-flight authentication at a time, per plan §4: "Another central is
 * currently in an active authentication session" is a rejection condition.
 */
class ChallengeSessions(
    private val clock: ElapsedClock,
    private val replayCache: ReplayCache = ReplayCache(),
) {
    private val lock = Any()
    private val byConnection = HashMap<String, PendingChallenge>()
    private val ids = AtomicLong(1L)

    /**
     * Accepts a freshly parsed and validated challenge.
     *
     * @return null on success, or the reason it was refused.
     */
    fun offer(
        connectionKey: String,
        request: ChallengeRequest,
        ttlMs: Long,
        requiresApproval: Boolean,
    ): RejectReason? =
        synchronized(lock) {
            val now = clock.elapsedMs()
            pruneExpired(now)

            val busy =
                byConnection.any { (key, pending) ->
                    key != connectionKey && !pending.used && !pending.isExpired(now)
                }
            if (busy) return RejectReason.BUSY

            if (!replayCache.recordIfNew(request.challengeKey)) return RejectReason.REPLAY

            byConnection[connectionKey] =
                PendingChallenge(
                    id = ids.getAndIncrement(),
                    connectionKey = connectionKey,
                    request = request,
                    expiresAtElapsedMs = now + ttlMs,
                    approvalState = if (requiresApproval) ApprovalState.PENDING else ApprovalState.NOT_REQUIRED,
                )
            return null
        }

    /** The live challenge for a connection, or null if absent or expired. */
    fun current(connectionKey: String): PendingChallenge? =
        synchronized(lock) {
            val pending = byConnection[connectionKey] ?: return null
            if (pending.isExpired(clock.elapsedMs())) {
                byConnection.remove(connectionKey)
                return null
            }
            return pending
        }

    /** Every challenge still waiting on the user, newest last. */
    fun awaitingApproval(): List<PendingChallenge> =
        synchronized(lock) {
            val now = clock.elapsedMs()
            pruneExpired(now)
            byConnection.values
                .filter { it.approval == ApprovalState.PENDING }
                .sortedBy { it.id }
        }

    fun setApproval(
        id: Long,
        approved: Boolean,
    ): PendingChallenge? =
        synchronized(lock) {
            val pending = byConnection.values.firstOrNull { it.id == id } ?: return null
            if (pending.approval != ApprovalState.PENDING) return null
            pending.approval = if (approved) ApprovalState.APPROVED else ApprovalState.DENIED
            return pending
        }

    /**
     * Claims the challenge for signing. Succeeds at most once per challenge; the caller must
     * store the resulting signature via [attachSignature] for subsequent blob reads.
     */
    fun claimForSigning(connectionKey: String): Result =
        synchronized(lock) {
            val pending = byConnection[connectionKey] ?: return Result.Refused(RejectReason.NO_PENDING_CHALLENGE)
            if (pending.isExpired(clock.elapsedMs())) {
                byConnection.remove(connectionKey)
                return Result.Refused(RejectReason.EXPIRED)
            }
            when (pending.approval) {
                ApprovalState.PENDING -> {
                    return Result.Refused(RejectReason.AWAITING_APPROVAL)
                }

                ApprovalState.DENIED -> {
                    byConnection.remove(connectionKey)
                    return Result.Refused(RejectReason.DENIED_BY_USER)
                }

                ApprovalState.APPROVED, ApprovalState.NOT_REQUIRED -> {
                    Unit
                }
            }
            val cached = pending.signature
            if (cached != null) return Result.Cached(pending, cached)
            if (pending.used) return Result.Refused(RejectReason.ALREADY_USED)
            // Marked used *before* signing so a concurrent read cannot produce a second signature.
            pending.used = true
            return Result.Sign(pending)
        }

    fun attachSignature(
        pending: PendingChallenge,
        signature: ByteArray,
    ) = synchronized(lock) {
        pending.signature = signature
    }

    /** Drops a failed signing attempt so the slot does not linger as used-but-empty. */
    fun discard(pending: PendingChallenge) =
        synchronized(lock) {
            byConnection.remove(pending.connectionKey, pending)
        }

    fun remove(connectionKey: String) =
        synchronized(lock) {
            byConnection.remove(connectionKey)
            Unit
        }

    fun clear() =
        synchronized(lock) {
            byConnection.clear()
            replayCache.clear()
        }

    fun activeCount(): Int =
        synchronized(lock) {
            pruneExpired(clock.elapsedMs())
            byConnection.size
        }

    private fun pruneExpired(now: Long) {
        byConnection.entries.removeAll { it.value.isExpired(now) }
    }

    sealed interface Result {
        /** Caller must sign [pending].request.rawPayload and call [attachSignature]. */
        class Sign(
            val pending: PendingChallenge,
        ) : Result

        /** A signature already exists — serve it (this is a read-blob continuation). */
        class Cached(
            val pending: PendingChallenge,
            val signature: ByteArray,
        ) : Result

        class Refused(
            val reason: RejectReason,
        ) : Result
    }
}
