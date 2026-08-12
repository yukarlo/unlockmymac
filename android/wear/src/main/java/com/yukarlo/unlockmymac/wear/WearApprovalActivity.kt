package com.yukarlo.unlockmymac.wear

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.yukarlo.unlockmymac.container
import com.yukarlo.unlockmymac.service.BleUnlockService

/**
 * Full-screen "may I unlock?" prompt, so the answer is a button press rather than a scroll and a
 * tap on a notification.
 *
 * A watch is worn, not held — the reason to approve from the wrist at all is that it is quicker
 * than reaching for a phone, and that advantage evaporates if it takes two precise taps on a
 * 40mm screen. The physical button is the fast path; the chips stay for when the button is not
 * delivered.
 *
 * Which keycode a given watch actually sends is not knowable from documentation: the home and
 * power buttons are usually reserved by the system, and only watches declaring a spare stem
 * button deliver `KEYCODE_STEM_*` to an app. So this accepts any of the stem codes and logs
 * every unhandled key it sees, which turns "does the button work on this hardware" into
 * something the log answers rather than something to guess at.
 */
class WearApprovalActivity : ComponentActivity() {
    private var challengeId: Long = -1L
    private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Shown while the watch is locked and the screen is off — this is the whole point.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        challengeId = intent.getLongExtra(EXTRA_CHALLENGE_ID, -1L)
        val macName = intent.getStringExtra(EXTRA_MAC_NAME)

        if (challengeId < 0) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        text =
                            macName?.let { getString(R.string.notification_approval_title_mac, it) }
                                ?: getString(R.string.notification_approval_title),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.title3,
                    )
                    Text(
                        text = getString(R.string.approval_press_button),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption2,
                    )
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { resolve(approved = true) },
                        label = { Text(getString(R.string.action_approve)) },
                    )
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { resolve(approved = false) },
                        label = { Text(getString(R.string.action_deny)) },
                    )
                }
            }
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_STEM_PRIMARY,
            KeyEvent.KEYCODE_STEM_1,
            KeyEvent.KEYCODE_STEM_2,
            KeyEvent.KEYCODE_STEM_3,
            -> {
                Log.i(TAG, "Approved by physical button (keycode $keyCode)")
                resolve(approved = true)
                true
            }

            else -> {
                // Recorded rather than swallowed: the only reliable way to find out what this
                // hardware sends is to see it in a log after someone presses it.
                Log.i(TAG, "Unhandled key on the approval screen: keycode $keyCode")
                super.onKeyDown(keyCode, event)
            }
        }

    /** Walking away is not consent, but neither is it a refusal — let the challenge expire. */
    override fun onStop() {
        super.onStop()
        if (!resolved) finish()
    }

    private fun resolve(approved: Boolean) {
        if (resolved) return
        resolved = true
        BleUnlockService.resolveApproval(this, challengeId, approved)
        container.notifier.cancelApproval(this)
        finish()
    }

    companion object {
        const val EXTRA_CHALLENGE_ID = "challenge_id"
        const val EXTRA_MAC_NAME = "mac_name"
        private const val TAG = "WearApproval"
    }
}
