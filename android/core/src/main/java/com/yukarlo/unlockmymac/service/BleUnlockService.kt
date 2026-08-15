package com.yukarlo.unlockmymac.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.yukarlo.unlockmymac.AppContainer
import com.yukarlo.unlockmymac.AppVisibility
import com.yukarlo.unlockmymac.ble.BleAdvertiser
import com.yukarlo.unlockmymac.ble.GattContext
import com.yukarlo.unlockmymac.ble.GattServerController
import com.yukarlo.unlockmymac.ble.GattServerListener
import com.yukarlo.unlockmymac.ble.PendingChallenge
import com.yukarlo.unlockmymac.container
import com.yukarlo.unlockmymac.core.R
import com.yukarlo.unlockmymac.data.AdvertisingState
import com.yukarlo.unlockmymac.data.AppSettings
import com.yukarlo.unlockmymac.data.ApprovalRequest
import com.yukarlo.unlockmymac.data.Timeouts
import com.yukarlo.unlockmymac.permissions.BlePermissions
import com.yukarlo.unlockmymac.util.challengeTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the BLE peripheral alive while the app is backgrounded and the screen is off.
 *
 * A foreground service of type `connectedDevice` is the only way to hold a GATT server open
 * through Doze. It publishes its state through [com.yukarlo.unlockmymac.data.BleStatusRepository]
 * rather than a binder, since the UI lives in the same process.
 */
class BleUnlockService :
    LifecycleService(),
    GattServerListener {
    private lateinit var appContainer: AppContainer
    private lateinit var advertiser: BleAdvertiser
    private lateinit var gattServer: GattServerController

    @Volatile
    private var settings: AppSettings? = null

    @Volatile
    private var pairedMacInstallationId: String? = null

    /** Friendly name of the paired Mac, shown as the ongoing notification's text. */
    @Volatile
    private var pairedMacName: String? = null

    @Volatile
    private var deviceId: String = ""

    /** The challenge currently being prompted for, so its mirrored copy can be withdrawn. */
    @Volatile
    private var promptedChallengeId: Long? = null

    private val bluetoothStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        appContainer.eventLog.info("Bluetooth turned on")
                        lifecycleScope.launch(Dispatchers.IO) { applyState() }
                    }

                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        appContainer.eventLog.warn("Bluetooth turned off; tearing down")
                        lifecycleScope.launch(Dispatchers.IO) { teardownRadio() }
                        appContainer.status.setAdvertising(AdvertisingState.BLUETOOTH_OFF)
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        appContainer = container
        advertiser = BleAdvertiser(this, appContainer.status, appContainer.eventLog)
        gattServer =
            GattServerController(
                context = this,
                sessions = appContainer.sessions,
                signer = appContainer.signer,
                pairingCoordinator = appContainer.pairingCoordinator,
                enrolmentCoordinator = appContainer.enrolmentCoordinator,
                status = appContainer.status,
                eventLog = appContainer.eventLog,
                contextProvider = ::gattContext,
                listener = this,
            )

        // Android 14+ refuses a connectedDevice foreground service without the Bluetooth
        // permissions. Fail loudly here rather than limping along with a dead radio.
        if (!startForegroundWithStatus(getString(R.string.notification_starting))) {
            appContainer.eventLog.error("Could not enter the foreground; stopping service")
            stopSelf()
            return
        }
        appContainer.status.setServiceRunning(true)
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        lifecycleScope.launch(Dispatchers.IO) {
            deviceId = appContainer.pairing.requireDeviceId()
            // Generate the identity key eagerly so the first pairing read is not the first
            // time we touch the Keystore (key generation can take a second on some devices).
            runCatching { appContainer.signer.ensureKey() }
                .onFailure { appContainer.eventLog.error("Key generation failed: ${it.javaClass.simpleName}") }
            applyState()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            appContainer.settings.settings.collect { current ->
                settings = current
                applyState()
            }
        }

        // Expired challenges are otherwise only pruned when something else calls into
        // ChallengeSessions. With a prompt sitting unanswered nothing does, so one survived
        // overnight and was "approved" 10.5 hours later against a Mac that had long gone.
        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                delay(SESSION_SWEEP_INTERVAL_MS)
                if (appContainer.sessions.sweepExpired()) {
                    appContainer.eventLog.info("Approval request expired; withdrawing the prompt")
                    onApprovalNoLongerValid()
                }
                // Also reconcile the central count here, so a dropped disconnect callback heals
                // even when no further connection arrives to trigger one. Without it the count
                // could stay wrong indefinitely, and with it the advertising restart below.
                if (gattServer.isOpen) gattServer.publishConnectedCentrals()
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            appContainer.pairing.pairedMac.collect { paired ->
                pairedMacInstallationId = paired?.installationId
                pairedMacName = paired?.name
                // The name arrives asynchronously, so refresh the notification once it lands.
                applyState()
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_RESOLVE_APPROVAL -> {
                val id = intent.getLongExtra(EXTRA_CHALLENGE_ID, -1L)
                val approved = intent.getBooleanExtra(EXTRA_APPROVED, false)
                if (id >= 0) {
                    // Cleared before resolving: resolving invokes onApprovalNoLongerValid, which
                    // would otherwise broadcast its own dismiss and double every message.
                    promptedChallengeId = null
                    gattServer.resolveApproval(id, approved)
                    // The question has been answered; every copy of it is now stale. Without
                    // this only the device that was tapped cleared its prompt, leaving the
                    // other showing a request that had already been decided.
                    container.notifier.cancelApproval(this)
                    // Also the signal a full-screen approval UI watches: cancelling a
                    // notification does nothing to an Activity already on screen.
                    appContainer.status.setPendingApproval(null)
                    ApprovalMirror.broadcastDismiss(this, id)
                }
            }

            ACTION_FORCE_RESET -> {
                forceReset()
            }
        }
        // START_STICKY: if the system kills us for memory, come back and resume advertising.
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        kotlinx.coroutines.runBlocking(Dispatchers.IO) { teardownRadio() }
        appContainer.status.setServiceRunning(false)
        container.notifier.cancelApproval(this)

        if (stopRequestedByUser) {
            stopRequestedByUser = false
            appContainer.eventLog.info("Service stopped — turned off in the app")
        } else {
            // Nobody asked for this. Most often an OEM battery manager; also seen on app
            // update and low-memory kills. Worth a warning: the Mac silently stops unlocking
            // and there is nothing else in the log to explain it.
            appContainer.eventLog.warn(
                "Service stopped unexpectedly — killed by the system. " +
                    "Check battery optimisation and Samsung's Sleeping apps list.",
            )
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // Guarded by BlePermissions.hasNotifications below.
    override fun onApprovalRequested(pending: PendingChallenge) {
        val tag = challengeTag(pending.request.rawPayload)
        appContainer.status.setPendingApproval(
            ApprovalRequest(
                id = pending.id,
                challengeTag = tag,
                requestedAtMs = System.currentTimeMillis(),
                expiresAtElapsedMs = pending.expiresAtElapsedMs,
            ),
        )
        // With the app on screen the card set above *is* the prompt, so neither of the surfaces meant
        // for a user who is elsewhere gets raised. A notification and a banner on top of a card asking
        // the same thing is the same question three times, and the banner would cover the card it
        // duplicates.
        //
        // The mirror below still goes out. It is a different device, and the watch is worth telling even
        // when the phone is in hand — suppressing that would mean walking away mid-request with no prompt
        // anywhere.
        if (AppVisibility.isForeground) {
            appContainer.eventLog.info("Approval needed for $tag (asking in the app)")
            // The card is silent on its own, and the notification that used to make the noise is being
            // suppressed right here. Without this an approval can go unnoticed on the very screen it is
            // displayed on.
            container.notifier.playApprovalAlert(this)
            promptedChallengeId = pending.id
            ApprovalMirror.broadcastRequest(this, pending.id, pairedMacName)
            return
        }

        // Raised before the notification-permission gate: the overlay is a separate surface with its
        // own permission, so a user who refused notifications but allowed the overlay still gets asked.
        //
        // `!= false` rather than `== true`: settings arrive asynchronously and are null until the first
        // emission. A request landing in that window should still raise the banner, because the setting
        // defaults on — reading null as "off" would silently drop the prompt right after a restart.
        if (settings?.approvalBannerEnabled != false) {
            container.notifier.showApprovalOverlay(this, pending.id, pairedMacName)
        }

        if (!BlePermissions.hasNotifications(this)) {
            // Without POST_NOTIFICATIONS the overlay and the in-app card are the only surfaces.
            appContainer.eventLog.warn("Approval needed for $tag (no notification permission)")
            promptedChallengeId = pending.id
            ApprovalMirror.broadcastRequest(this, pending.id, pairedMacName)
            return
        }
        NotificationManagerCompat.from(this).notify(
            container.notifier.approvalNotificationId,
            container.notifier.approvalRequest(this, pending.id, pairedMacName),
        )
        // After the local prompt, never before it: the mirror is a convenience and must not be
        // able to delay or suppress the prompt on the device actually being challenged.
        promptedChallengeId = pending.id
        ApprovalMirror.broadcastRequest(this, pending.id, pairedMacName)
    }

    /**
     * Restarts advertising once the last central drops.
     *
     * The Bluetooth controller stops a connectable advertisement the instant a connection is
     * established and the Android stack never resumes it. Without this the phone is
     * discoverable exactly once per app start: the Mac's first connection makes it invisible
     * for good, while the Mac keeps reconnecting on its retained peripheral handle so the
     * failure looks like "authentication works but discovery is dead".
     */
    override fun onCentralLinkEstablished() {
        // Keyed off the ACL, not off the central count: the count is earned by a read or write, which
        // lands after the link is already up and the controller has already gone quiet. Inferring
        // "paused" from a non-zero count therefore left the UI claiming to be discoverable for the
        // whole gap between connecting and the challenge write.
        advertiser.markPausedByConnection()
    }

    override fun onConnectedCentralsChanged(count: Int) {
        val current = settings ?: return
        if (count > 0) {
            advertiser.markPausedByConnection()
            return
        }
        if (!current.shouldAdvertise) return
        // Small delay: restarting inside the disconnect callback races the stack tearing the
        // connection down and tends to fail with ADVERTISE_FAILED_INTERNAL_ERROR.
        lifecycleScope.launch {
            delay(ADVERTISE_RESTART_DELAY_MS)
            if (settings?.shouldAdvertise == true && appContainer.status.status.value.connectedCentrals == 0) {
                appContainer.eventLog.info("Central disconnected; restarting advertising")
                advertiser.restart(current.advertiseMode)
            }
        }
    }

    override fun onApprovalNoLongerValid() {
        appContainer.status.setPendingApproval(null)
        container.notifier.cancelApproval(this)
        container.notifier.hideApprovalOverlay(this)
        // Whether answered, expired or swept, the copy on the other device is now stale.
        promptedChallengeId?.let { ApprovalMirror.broadcastDismiss(this, it) }
        promptedChallengeId = null
    }

    override fun onPaired(
        macInstallationId: String,
        macName: String,
    ) {
        lifecycleScope.launch {
            appContainer.pairing.savePairing(macInstallationId, macName, System.currentTimeMillis())
            pairedMacInstallationId = macInstallationId
        }
    }

    private fun gattContext(): GattContext {
        val current = settings
        val requireApproval = current?.requireApproval ?: false
        return GattContext(
            pairedMacInstallationId = pairedMacInstallationId,
            deviceId = deviceId,
            deviceName =
                current?.deviceName ?: android.os.Build.MODEL
                    .orEmpty(),
            requireApproval = requireApproval,
            challengeTtlMs = Timeouts.challengeTtlMs(requireApproval),
        )
    }

    /** Brings the radio in line with the current settings. Idempotent, safe to spam. */
    private suspend fun applyState() =
        withContext(Dispatchers.IO) {
            val current = settings ?: return@withContext
            if (deviceId.isEmpty()) return@withContext

            if (!current.shouldAdvertise) {
                teardownRadio()
                updateNotification(
                    if (current.paused) {
                        getString(R.string.notification_paused)
                    } else {
                        getString(R.string.notification_disabled)
                    },
                )
                // Leaving the service alive while paused keeps resuming instant; the user stops it
                // entirely from the Home switch.
                return@withContext
            }

            if (!gattServer.isOpen && !gattServer.open()) {
                updateNotification(getString(R.string.notification_error))
                return@withContext
            }
            advertiser.start(current.advertiseMode)
            updateNotification(
                pairedMacName ?: getString(R.string.notification_active_unpaired),
            )
        }

    private suspend fun teardownRadio() =
        withContext(Dispatchers.IO) {
            advertiser.stop()
            gattServer.close()
        }

    /**
     * Full manual reset of the BLE stack on this side.
     *
     * The escape hatch for the state neither app can detect: both report themselves healthy —
     * service running, advertising, GATT server open, the Mac even showing the phone as
     * connected — yet no challenge ever arrives because a link is half-open somewhere below.
     *
     * Hanging up on the centrals is what reaches the Mac: it sees the disconnect, tears down its
     * own session, and reconnects from scratch on the next advertisement. The rest clears any
     * stale state here, and the new advertising set gets a fresh address.
     */
    private fun forceReset() {
        lifecycleScope.launch(Dispatchers.IO) {
            appContainer.eventLog.warn("Manual reset requested — restarting BLE from scratch")

            val dropped = gattServer.disconnectAllCentrals()
            if (dropped > 0) {
                appContainer.eventLog.info("Dropped $dropped connected central(s)")
            }

            appContainer.sessions.clear()
            onApprovalNoLongerValid()

            teardownRadio()
            applyState()

            appContainer.eventLog.info("Reset complete — advertising restarted")
        }
    }

    private fun startForegroundWithStatus(text: String): Boolean =
        runCatching {
            ServiceCompat.startForeground(
                this,
                container.notifier.ongoingNotificationId,
                container.notifier.ongoing(this, text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.isSuccess

    @SuppressLint("MissingPermission") // Guarded by the hasNotifications check on the next line.
    private fun updateNotification(text: String) {
        if (!BlePermissions.hasNotifications(this)) return
        NotificationManagerCompat.from(this).notify(
            container.notifier.ongoingNotificationId,
            container.notifier.ongoing(this, text),
        )
    }

    companion object {
        const val ACTION_RESOLVE_APPROVAL = "com.yukarlo.unlockmymac.RESOLVE_APPROVAL"
        const val ACTION_FORCE_RESET = "com.yukarlo.unlockmymac.FORCE_RESET"
        const val EXTRA_CHALLENGE_ID = "challenge_id"
        const val EXTRA_APPROVED = "approved"

        /** Let the stack finish tearing down the connection before re-advertising. */
        private const val ADVERTISE_RESTART_DELAY_MS = 500L

        /** How often to drop expired challenges and withdraw their prompts. */
        private const val SESSION_SWEEP_INTERVAL_MS = 15_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BleUnlockService::class.java))
        }

        /**
         * Set immediately before a deliberate stop so [onDestroy] can tell a user action apart
         * from the system killing us.
         *
         * `onDestroy` fires identically for a toggle, an OEM battery-manager kill, and an app
         * update, and a single "Service stopped" line for all three makes the log useless
         * exactly when something has gone wrong. Same process, so a companion flag is reliable.
         */
        @Volatile
        private var stopRequestedByUser = false

        fun stop(context: Context) {
            stopRequestedByUser = true
            context.stopService(Intent(context, BleUnlockService::class.java))
        }

        /** Tears the BLE stack down and back up. No-ops if the service is not running. */
        fun forceReset(context: Context) {
            context.startService(
                Intent(context, BleUnlockService::class.java).setAction(ACTION_FORCE_RESET),
            )
        }

        /** Approve or deny from the UI. No-ops if the service is not running. */
        fun resolveApproval(
            context: Context,
            challengeId: Long,
            approved: Boolean,
        ) {
            context.startService(
                Intent(context, BleUnlockService::class.java)
                    .setAction(ACTION_RESOLVE_APPROVAL)
                    .putExtra(EXTRA_CHALLENGE_ID, challengeId)
                    .putExtra(EXTRA_APPROVED, approved),
            )
        }
    }
}
