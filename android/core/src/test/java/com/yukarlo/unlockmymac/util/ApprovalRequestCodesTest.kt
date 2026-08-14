package com.yukarlo.unlockmymac.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalRequestCodesTest {
    @Test
    fun `approve and deny for the same challenge differ`() {
        assertNotEquals(
            ApprovalRequestCodes.forDecision(7L, approved = true),
            ApprovalRequestCodes.forDecision(7L, approved = false),
        )
    }

    /** The bug this replaced: the watch used a fixed pair of codes, so every challenge collided. */
    @Test
    fun `different challenges do not share a code`() {
        val codes =
            (1L..500L).flatMap { id ->
                listOf(
                    ApprovalRequestCodes.forDecision(id, approved = true),
                    ApprovalRequestCodes.forDecision(id, approved = false),
                )
            }
        assertEquals(codes.size, codes.distinct().size)
    }

    /** The test prompt uses -1, and it must not collide with a real challenge. */
    @Test
    fun `the probe id does not collide with a real challenge`() {
        val probe = ApprovalRequestCodes.forDecision(-1L, approved = true)
        val real = (1L..500L).map { ApprovalRequestCodes.forDecision(it, approved = true) }
        assertTrue(probe !in real)
    }

    /**
     * `toInt()` alone would collide here: both ids share their low 32 bits, so truncating discards
     * exactly the part that distinguishes them.
     */
    @Test
    fun `ids differing only above 32 bits do not collide`() {
        assertNotEquals(
            ApprovalRequestCodes.forDecision(1L, approved = true),
            ApprovalRequestCodes.forDecision((1L shl 32) or 1L, approved = true),
        )
    }

    @Test
    fun `codes are never negative`() {
        val ids = listOf(-1L, 0L, 1L, Long.MIN_VALUE, Long.MAX_VALUE, -999_999L)
        for (id in ids) {
            for (approved in listOf(true, false)) {
                assertTrue(
                    "id=$id approved=$approved",
                    ApprovalRequestCodes.forDecision(id, approved) >= 0,
                )
            }
        }
    }
}
