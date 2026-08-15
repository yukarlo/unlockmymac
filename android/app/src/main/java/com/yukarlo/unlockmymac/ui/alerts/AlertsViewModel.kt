package com.yukarlo.unlockmymac.ui.alerts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yukarlo.unlockmymac.container
import com.yukarlo.unlockmymac.data.AppSettings
import com.yukarlo.unlockmymac.data.PairedMac
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlertsUiState(
    val settings: AppSettings?,
    /** Named in the preview so it reads like a real request rather than a mock. */
    val pairedMac: PairedMac?,
)

/**
 * How an unlock request reaches the user, as opposed to what the radio does.
 *
 * Separate from `HomeViewModel` rather than folded into it: these settings are all about presentation,
 * they are read by one screen, and the home screen already carries the service, permission, Bluetooth
 * and battery state. Nothing here needs any of that.
 */
class AlertsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val container = application.container

    val uiState: StateFlow<AlertsUiState> =
        combine(container.settings.settings, container.pairing.pairedMac) { settings, paired ->
            AlertsUiState(settings = settings, pairedMac = paired)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AlertsUiState(settings = null, pairedMac = null),
        )

    fun setBannerEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setApprovalBannerEnabled(enabled) }
    }

    fun setSwipeUpOpensApp(enabled: Boolean) {
        viewModelScope.launch { container.settings.setBannerSwipeUpOpensApp(enabled) }
    }

    fun setSwipeDownDismisses(enabled: Boolean) {
        viewModelScope.launch { container.settings.setBannerSwipeDownDismisses(enabled) }
    }

    fun setScrimTapDismisses(enabled: Boolean) {
        viewModelScope.launch { container.settings.setBannerScrimTapDismisses(enabled) }
    }
}
