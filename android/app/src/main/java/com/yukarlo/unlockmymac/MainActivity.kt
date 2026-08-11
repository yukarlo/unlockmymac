package com.yukarlo.unlockmymac

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
