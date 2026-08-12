package com.yukarlo.unlockmymac.ble

/**
 * Remembers recently seen challenge values so the same one can never be signed twice.
 *
 * A bounded LRU is enough: a challenge older than [capacity] entries is also long past the
 * clock-skew window enforced by [ChallengeCodec.validate], so it would be rejected anyway.
 */
class ReplayCache(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val seen =
        object : LinkedHashMap<String, Unit>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > capacity
        }

    /** Records [key], returning false if it had already been seen. */
    @Synchronized
    fun recordIfNew(key: String): Boolean {
        if (seen.containsKey(key)) return false
        seen[key] = Unit
        return true
    }

    @Synchronized
    fun contains(key: String): Boolean = seen.containsKey(key)

    @Synchronized
    fun size(): Int = seen.size

    @Synchronized
    fun clear() = seen.clear()

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
