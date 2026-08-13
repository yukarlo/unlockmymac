package com.yukarlo.unlockmymac.wear

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.yukarlo.unlockmymac.container
import com.yukarlo.unlockmymac.service.ApprovalMirror
import com.yukarlo.unlockmymac.service.BleUnlockService

/**
 * Full-screen "may I unlock?" prompt, so answering takes one deliberate tap rather than finding
 * and expanding a notification.
 *
 * A watch is worn, not held — approving from the wrist is only worth doing if it is quicker than
 * reaching for a phone, and that advantage disappears if it needs a precise tap on a 40mm screen.
 * Hence one target filling most of the display.
 *
 * Answered by tapping, not by the physical buttons. Measured on a Galaxy Watch 6: the upper
 * button is Home and never reaches an app, and the lower one arrives as `KEYCODE_BACK` — which
 * from API 36 the platform is moving behind predictive back, where a gesture no longer dispatches
 * that key at all. Building the one security-relevant decision in this app on a key the platform
 * is in the middle of reclaiming is not worth the convenience.
 *
 * Pressing Back therefore just closes this screen, which is not an answer: the challenge is left
 * to expire rather than being denied, so the Mac keeps asking for the rest of its window instead
 * of starting a backoff. Deny deliberately if you mean it.
 *
 * Back denies rather than approves, deliberately. It is the reflexive dismiss gesture, so binding
 * it to approve would mean a stray press silently unlocking the Mac — the exact opposite of what
 * an approval prompt is for. Approving stays a deliberate act: a tap on a target that fills most
 * of the screen.
 */
class WearApprovalActivity : ComponentActivity() {
    private var challengeId: Long = -1L
    private var originNodeId: String? = null
    private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Shown while the watch is locked and the screen is off — this is the whole point.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        challengeId = intent.getLongExtra(EXTRA_CHALLENGE_ID, -1L)
        val macName = intent.getStringExtra(EXTRA_MAC_NAME)
        originNodeId = intent.getStringExtra(EXTRA_ORIGIN_NODE)

        Log.i(TAG, "Approval screen for challenge=$challengeId origin=$originNodeId")

        if (challengeId < 0) {
            finish()
            return
        }

        // Answering on the other device has to close this screen. Cancelling a notification
        // cannot, so the signal depends on whose challenge this is:
        //  - ours: the shared status goes null the moment it resolves;
        //  - mirrored: the originating device sends an explicit dismiss, since we hold no
        //    challenge of our own to watch.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (originNodeId == null) {
                    container.status.status
                        .map { it.pendingApproval?.id }
                        .distinctUntilChanged()
                        .collect { pendingId ->
                            if (!resolved && pendingId != challengeId) closeAlreadyAnswered()
                        }
                } else {
                    ApprovalMirror.dismissed.collect { dismissed ->
                        if (!resolved &&
                            dismissed.challengeId == challengeId &&
                            dismissed.nodeId == originNodeId
                        ) {
                            closeAlreadyAnswered()
                        }
                    }
                }
            }
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
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                        onClick = { resolve(approved = true) },
                        label = { Text(getString(R.string.action_approve)) },
                    )
                    CompactChip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { resolve(approved = false) },
                        label = { Text(getString(R.string.action_deny)) },
                    )
                }
            }
        }
    }

    /** Walking away is not consent, but neither is it a refusal — let the challenge expire. */
    override fun onStop() {
        super.onStop()
        if (!resolved) finish()
    }

    /** Someone answered on the other device; nothing to send, just get off the screen. */
    private fun closeAlreadyAnswered() {
        Log.i(TAG, "Closing: answered on the other device")
        resolved = true
        finish()
    }

    private fun resolve(approved: Boolean) {
        if (resolved) return
        resolved = true
        val origin = originNodeId
        if (origin == null) {
            BleUnlockService.resolveApproval(this, challengeId, approved)
        } else {
            // A mirrored prompt: this watch holds no such challenge, so the answer goes back
            // to the device that does.
            ApprovalMirror.sendDecision(this, origin, challengeId, approved)
        }
        container.notifier.cancelApproval(this)
        finish()
    }

    companion object {
        const val EXTRA_CHALLENGE_ID = "challenge_id"
        const val EXTRA_MAC_NAME = "mac_name"
        const val EXTRA_ORIGIN_NODE = "origin_node"
        private const val TAG = "WearApproval"
    }
}
