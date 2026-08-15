package com.yukarlo.unlockmymac

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yukarlo.unlockmymac.service.ApprovalOverlay
import com.yukarlo.unlockmymac.ui.diagnostics.DiagnosticsScreen
import com.yukarlo.unlockmymac.ui.home.HomeScreen
import com.yukarlo.unlockmymac.ui.pairing.PairingScreen
import com.yukarlo.unlockmymac.ui.theme.UnlockMyMacTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UnlockMyMacTheme {
                UnlockMyMacNavHost()
            }
        }
        showApprovalOverlayIfAsked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showApprovalOverlayIfAsked(intent)
    }

    /**
     * Debug-only hook for putting the approval banner on screen without a Mac.
     *
     * `adb shell am start -n com.yukarlo.unlockmymac/.MainActivity --ez show_approval_overlay true`
     *
     * The banner is drawn by the BLE service in response to a challenge, which means the only way to
     * see it otherwise is to lock a paired Mac — a slow loop for laying out a card, and one that
     * needs somebody's Mac. The challenge id is negative by the same convention the Wear probe uses,
     * so answering it resolves nothing.
     *
     * Gated on the debuggable flag: an intent extra that raises an unlock prompt is not something to
     * ship, even one that cannot authorise anything. Read from `applicationInfo` rather than
     * `BuildConfig.DEBUG` because this module does not generate a `BuildConfig`, and switching that
     * on is a build change for the sake of one debug hook.
     */
    private fun showApprovalOverlayIfAsked(intent: Intent?) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return
        if (intent?.getBooleanExtra("show_approval_overlay", false) != true) return
        ApprovalOverlay.show(
            context = this,
            challengeId = -1L,
            macName = "Debug Mac",
            originNodeId = null,
        )
    }
}

private object Routes {
    const val HOME = "home"
    const val PAIRING = "pairing"
    const val DIAGNOSTICS = "diagnostics"
}

@Composable
private fun UnlockMyMacNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onPair = { navController.navigate(Routes.PAIRING) },
                onDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
            )
        }
        composable(Routes.PAIRING) {
            PairingScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }
    }
}
