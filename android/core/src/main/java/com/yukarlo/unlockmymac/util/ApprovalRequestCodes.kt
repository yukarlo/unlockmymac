package com.yukarlo.unlockmymac.util

/**
 * Request codes for the Approve/Deny `PendingIntent`s on an approval notification.
 *
 * `PendingIntent` identity ignores extras, so two intents differing only by challenge id are the
 * *same* intent as far as the system is concerned. Combined with `FLAG_UPDATE_CURRENT` that means the
 * newer one silently rewrites the older one's extras, and a stale notification's buttons start
 * answering for a different challenge. The request code is the only field that separates them.
 *
 * Both form factors use this. The watch previously used a fixed `1`/`2` — so a probe prompt
 * (`challengeId = -1`) and a real challenge overwrote each other — while the phone shifted the id
 * without folding the discarded bits back in.
 */
object ApprovalRequestCodes {
    /**
     * A code unique to this (challenge, decision) pair.
     *
     * Folds the id down to 30 bits rather than truncating with `toInt()`: challenge ids are `Long`, and
     * a plain cast collides for any two ids sharing their low 32 bits, while `shl 1` on top of that
     * discards the sign bit as well. XOR-folding the halves keeps every bit contributing.
     *
     * Kept non-negative because a negative request code is legal but reads as an error in logs, and
     * probe challenges are negative by convention.
     */
    fun forDecision(
        challengeId: Long,
        approved: Boolean,
    ): Int {
        val folded = (challengeId ushr 32).toInt() xor challengeId.toInt()
        val bounded = folded and 0x3FFF_FFFF
        return (bounded shl 1) or if (approved) 1 else 0
    }
}
