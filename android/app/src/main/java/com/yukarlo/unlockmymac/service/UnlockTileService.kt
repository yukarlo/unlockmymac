package com.yukarlo.unlockmymac.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.yukarlo.unlockmymac.MainActivity
import com.yukarlo.unlockmymac.R
import com.yukarlo.unlockmymac.container
import com.yukarlo.unlockmymac.data.AppSettings
import com.yukarlo.unlockmymac.permissions.BlePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Quick Settings tile for turning discoverability on and off.
 *
 * Exists so the common action — stop broadcasting on the way out, start again on the way back —
 * does not need the app opened. Deliberately *not* an approval surface: an approval is a timed,
 * interrupting question and belongs in a notification, and mirroring challenge state onto a
 * third surface is how prompts end up outliving the challenge they belong to.
 *
 * The tile reports whether the phone is actually discoverable, which is
 * [AppSettings.shouldAdvertise] — not just whether the service switch is on. Reporting the switch
 * alone would show "on" while a pause set in the app kept the radio silent.
 */
class UnlockTileService : TileService() {
    /**
     * Bound to the service's life, not to a listening window.
     *
     * A tap does not require the shade to have been opened first — the system delivers `onClick` to a
     * freshly bound service, and `onStartListening` may not have run at all. Measured with
     * `cmd statusbar click-tile`: System UI logged `TileLifecycleManager: onClick` and nothing
     * happened, because the click handler was waiting on a scope only `onStartListening` created.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Only the settings collector is per-window; it exists to redraw a tile the user can see. */
    private var collectJob: Job? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        // Collected rather than read once: the switches in the app write to the same DataStore, so
        // changing one with the shade open should move the tile too.
        collectJob?.cancel()
        collectJob =
            scope.launch {
                container.settings.settings.collect { render(it) }
            }
    }

    override fun onStopListening() {
        collectJob?.cancel()
        collectJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()

        // Runtime permissions cannot be requested from a tile, so hand the user to the app instead
        // of flipping a setting the service will then refuse to honour. Same reasoning as
        // HomeViewModel.setServiceEnabled.
        if (!BlePermissions.hasBleAccess(this)) {
            openApp()
            return
        }

        scope.launch {
            val settings = container.settings.settings.first()
            if (settings.shouldAdvertise) {
                container.eventLog.info("Discoverable turned OFF from the Quick Settings tile")
                container.settings.setServiceEnabled(false)
                BleUnlockService.stop(applicationContext)
            } else {
                container.eventLog.info("Discoverable turned ON from the Quick Settings tile")
                // Covers both ways of being off in one call: `setServiceEnabled(true)` also clears a
                // stale pause, so the tile does not need to know which of the two was set.
                container.settings.setServiceEnabled(true)
                BleUnlockService.start(applicationContext)
            }
            // The collector above will re-render, but only while still listening. Render now so the
            // tile never shows the pre-tap state if this is the last event of the listening window.
            render(container.settings.settings.first())
        }
    }

    private fun render(settings: AppSettings) {
        val tile = qsTile ?: return

        val hasPermission = BlePermissions.hasBleAccess(this)
        val bluetoothOn =
            getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        tile.label = getString(R.string.tile_label)

        // Deliberately not UNAVAILABLE for the permission and Bluetooth cases: an unavailable tile
        // does not accept a tap on every version, and a tap is the only way to get the user to the
        // screen that fixes it.
        when {
            !hasPermission -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.tile_state_no_permission)
            }

            !bluetoothOn -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.tile_state_bluetooth_off)
            }

            settings.shouldAdvertise -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = getString(R.string.tile_state_on)
            }

            // Distinguished so the tile explains itself: paused and switched off both read as "not
            // discoverable" but are undone in different places.
            settings.serviceEnabled -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.tile_state_paused)
            }

            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.tile_state_off)
            }
        }

        // Set from the state resolved above rather than alongside it, so the glyph and the state can
        // never disagree — the system already colours the tile by state, and an icon that says the
        // opposite is worse than no icon change at all.
        tile.icon =
            Icon.createWithResource(
                this,
                if (tile.state == Tile.STATE_ACTIVE) {
                    R.drawable.ic_tile_discoverable
                } else {
                    R.drawable.ic_tile_discoverable_off
                },
            )

        tile.updateTile()
    }

    // The deprecated Intent overload is unavoidable at minSdk 31: the PendingIntent replacement only
    // exists from API 34, and the Intent form throws `UnsupportedOperationException` from API 34, so
    // each is the only option on its side of the version check below.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent =
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending =
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        // The Intent overload throws on API 34+, and the PendingIntent overload does not exist
        // before it, so both are needed at minSdk 31.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
