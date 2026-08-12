package com.yukarlo.unlockmymac

import android.content.Context
import android.os.SystemClock
import com.yukarlo.unlockmymac.ble.ChallengeSessions
import com.yukarlo.unlockmymac.ble.ElapsedClock
import com.yukarlo.unlockmymac.crypto.KeystoreSigner
import com.yukarlo.unlockmymac.data.BleStatusRepository
import com.yukarlo.unlockmymac.data.EventLog
import com.yukarlo.unlockmymac.data.PairingRepository
import com.yukarlo.unlockmymac.data.SettingsRepository
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
) {
    private val appContext = context.applicationContext

    val settings = SettingsRepository(appContext)
    val pairing = PairingRepository(appContext)
    val status = BleStatusRepository()
    val eventLog = EventLog(appContext)
    val signer = KeystoreSigner()
    val pairingCoordinator = PairingCoordinator()

    /** Session state lives here, not in the service, so it survives service restarts intact. */
    val sessions = ChallengeSessions(ElapsedClock { SystemClock.elapsedRealtime() })
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
