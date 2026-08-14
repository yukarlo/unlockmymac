package com.yukarlo.unlockmymac

import android.content.Context
import android.os.SystemClock
import com.yukarlo.unlockmymac.ble.ChallengeSessions
import com.yukarlo.unlockmymac.ble.ElapsedClock
import com.yukarlo.unlockmymac.crypto.KeystoreSigner
import com.yukarlo.unlockmymac.data.AdvertiseMode
import com.yukarlo.unlockmymac.data.BleStatusRepository
import com.yukarlo.unlockmymac.data.EventLog
import com.yukarlo.unlockmymac.data.PairingRepository
import com.yukarlo.unlockmymac.data.SettingsRepository
import com.yukarlo.unlockmymac.pairing.EnrolmentCoordinator
import com.yukarlo.unlockmymac.pairing.PairingCoordinator
import com.yukarlo.unlockmymac.service.UnlockNotifier

/**
 * Hand-rolled service locator. The graph is a handful of singletons with no configuration, so
 * a DI framework would add build time and indirection without buying anything.
 */
class AppContainer(
    context: Context,
    /** How this form factor draws the service's notifications. See [UnlockNotifier]. */
    val notifier: UnlockNotifier,
    /** How fast this form factor advertises before the user has chosen. See [AdvertiseMode]. */
    defaultAdvertiseMode: AdvertiseMode = AdvertiseMode.LOW_POWER,
) {
    private val appContext = context.applicationContext

    val settings = SettingsRepository(appContext, defaultAdvertiseMode)
    val pairing = PairingRepository(appContext)
    val status = BleStatusRepository()
    val eventLog = EventLog(appContext)
    val signer = KeystoreSigner()
    val pairingCoordinator = PairingCoordinator()
    val enrolmentCoordinator = EnrolmentCoordinator()

    /** Session state lives here, not in the service, so it survives service restarts intact. */
    val sessions = ChallengeSessions(ElapsedClock { SystemClock.elapsedRealtime() })

    /**
     * Forgets the Mac and destroys this device's key, leaving nothing a Mac could still trust.
     *
     * Clearing the pairing record alone is not enough. The signing key lives in AndroidKeyStore
     * under a fixed alias and would survive, so a Mac that still held the matching public key could
     * verify a signature from a device that considers itself unpaired — the Mac decides who answered
     * by which stored key verifies, and nothing else. Dropping the key makes any leftover record on
     * the Mac unusable, which is the mirror of the Mac taking a new installation identity when it
     * forgets everything.
     *
     * A fresh key is created on demand by the next `ensureKey`, so pairing again just works. This
     * deliberately does not create one eagerly: nothing needs a key until the next enrolment, and
     * regenerating here would mean the Diagnostics screen's fingerprint changed before the user had
     * done anything.
     *
     * Everything else tied to the old pairing goes too. This used to drop only the record and the key,
     * with the rest of the teardown living in the phone's Diagnostics screen — so unpairing from the
     * watch, which has no such screen, left live sessions, a staged enrolment voucher and an on-screen
     * approval prompt all pointing at a Mac that was no longer trusted. Both form factors call this and
     * nothing else, so they cannot drift apart again.
     */
    suspend fun forgetPairing() {
        pairing.unpair()
        signer.deleteKey()

        // Any challenge in flight was addressed to the Mac we just forgot, and its approval can never
        // be redeemed. Drop the sessions before withdrawing the prompt, so nothing can resolve one in
        // between.
        sessions.clear()
        pairingCoordinator.close()
        enrolmentCoordinator.clear()

        status.setPendingApproval(null)
        status.setPairingWindow(null)
        notifier.cancelApproval(appContext)
    }
}

/**
 * Implemented by each module's `Application` so `Context.container` resolves without this library
 * knowing the concrete class. The phone and the watch have different `Application` subclasses —
 * they create different notification channels and the watch has no pairing UI — but both own the
 * same graph.
 */
interface ContainerHolder {
    val container: AppContainer
}

val Context.container: AppContainer
    get() = (applicationContext as ContainerHolder).container
