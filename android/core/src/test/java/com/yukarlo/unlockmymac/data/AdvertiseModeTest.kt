package com.yukarlo.unlockmymac.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The resolution behind the watch connect-stall of 2026-08-14.
 *
 * The watch had no `advertise_mode` key stored at all, so it fell through to `LOW_POWER` — ~1s
 * advertising — while the Mac's connect watchdog was 2.5s. It cancelled connects that were landing
 * and gave up. The fix was a per-form-factor default, which makes exactly this function the thing
 * that must not regress.
 */
class AdvertiseModeTest {
    @Test
    fun `nothing stored takes the form factor default`() {
        assertEquals(AdvertiseMode.BALANCED, AdvertiseMode.fromStored(null, AdvertiseMode.BALANCED))
        assertEquals(AdvertiseMode.LOW_POWER, AdvertiseMode.fromStored(null, AdvertiseMode.LOW_POWER))
    }

    /**
     * The branch that is easy to leave out and impossible to notice.
     *
     * On a form factor defaulting to BALANCED, a stored LOW_POWER is indistinguishable from nothing
     * stored unless it is matched explicitly — so the user turning fast discovery *off* would be
     * silently overridden on every read.
     */
    @Test
    fun `an explicit choice survives a default that disagrees`() {
        assertEquals(
            AdvertiseMode.LOW_POWER,
            AdvertiseMode.fromStored(AdvertiseMode.LOW_POWER.name, default = AdvertiseMode.BALANCED),
        )
        assertEquals(
            AdvertiseMode.BALANCED,
            AdvertiseMode.fromStored(AdvertiseMode.BALANCED.name, default = AdvertiseMode.LOW_POWER),
        )
    }

    @Test
    fun `an unrecognised value falls back rather than throwing`() {
        // A rename or a downgrade can leave a value this build has never heard of in DataStore.
        // Advertising at the wrong rate beats crashing on every settings read.
        assertEquals(
            AdvertiseMode.BALANCED,
            AdvertiseMode.fromStored("TURBO", default = AdvertiseMode.BALANCED),
        )
        assertEquals(
            AdvertiseMode.LOW_POWER,
            AdvertiseMode.fromStored("", default = AdvertiseMode.LOW_POWER),
        )
    }

    @Test
    fun `matching is exact, not lenient`() {
        // Guards against someone "helpfully" making this case-insensitive: the stored value is always
        // written from `name`, so anything else is a value we did not write and should not trust.
        assertEquals(
            AdvertiseMode.LOW_POWER,
            AdvertiseMode.fromStored("balanced", default = AdvertiseMode.LOW_POWER),
        )
    }
}
