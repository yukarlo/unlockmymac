package com.yukarlo.unlockmymac.ui.alerts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SwipeDown
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yukarlo.unlockmymac.R
import com.yukarlo.unlockmymac.service.ApprovalOverlay
import com.yukarlo.unlockmymac.service.UnlockNotifications
import com.yukarlo.unlockmymac.ui.components.ActionRow
import com.yukarlo.unlockmymac.ui.components.SectionCard
import com.yukarlo.unlockmymac.ui.components.SettingRow
import com.yukarlo.unlockmymac.util.OnResume

/**
 * Settings for how an unlock request announces itself.
 *
 * Two halves, and only one of them belongs to this app. The banner and its gestures are ours to
 * configure. Sound and vibration are the notification channel's, and since API 26 the channel belongs to
 * the user — an app cannot change those after creation and `setLights` is ignored outright — so that half
 * is a handoff to Android's own screen rather than switches that would do nothing or fight the channel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    onBack: () -> Unit,
    viewModel: AlertsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    val context = LocalContext.current

    // Re-read on resume, not once. The overlay permission is granted on a Settings screen the user leaves
    // the app for, so coming back is the only moment this can be noticed to have changed.
    var canDrawOverlays by remember { mutableStateOf(ApprovalOverlay.isPermitted(context)) }
    OnResume { canDrawOverlays = ApprovalOverlay.isPermitted(context) }

    val bannerOn = settings?.approvalBannerEnabled != false

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.alerts_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = stringResource(R.string.alerts_banner_section)) {
                SettingRow(
                    title = stringResource(R.string.home_alerts_banner),
                    description = stringResource(R.string.home_alerts_banner_desc),
                    checked = bannerOn && canDrawOverlays,
                    // Nothing to turn on without the permission; the row below asks for it instead.
                    enabled = canDrawOverlays,
                    icon = Icons.Default.NotificationsActive,
                    onCheckedChange = viewModel::setBannerEnabled,
                )

                if (!canDrawOverlays) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    ActionRow(
                        title = stringResource(R.string.home_alerts_permission),
                        description = stringResource(R.string.home_alerts_permission_desc),
                        icon = Icons.Default.OpenInNew,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    ApprovalOverlay
                                        .settingsIntent(context)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                } else if (bannerOn) {
                    // Which gestures the card responds to. Only shown when there is a card to gesture
                    // at, so the screen never offers controls for something switched off.
                    //
                    // None of these can approve or deny — see `ApprovalSheet`. Turning all three off
                    // leaves a card answerable only by its buttons, which is a legitimate choice for a
                    // prompt this consequential.
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    SettingRow(
                        title = stringResource(R.string.home_alerts_swipe_up),
                        description = stringResource(R.string.home_alerts_swipe_up_desc),
                        checked = settings?.bannerSwipeUpOpensApp != false,
                        icon = Icons.Default.OpenInFull,
                        onCheckedChange = viewModel::setSwipeUpOpensApp,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    SettingRow(
                        title = stringResource(R.string.home_alerts_swipe_down),
                        description = stringResource(R.string.home_alerts_swipe_down_desc),
                        checked = settings?.bannerSwipeDownDismisses != false,
                        icon = Icons.Default.SwipeDown,
                        onCheckedChange = viewModel::setSwipeDownDismisses,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    SettingRow(
                        title = stringResource(R.string.home_alerts_scrim_tap),
                        description = stringResource(R.string.home_alerts_scrim_tap_desc),
                        checked = settings?.bannerScrimTapDismisses != false,
                        icon = Icons.Default.TouchApp,
                        onCheckedChange = viewModel::setScrimTapDismisses,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    // Shows the real banner rather than a mock of it, so what is previewed is what
                    // arrives — including whichever gestures are switched on above, since the card reads
                    // those settings live. Challenge id -1 by the same convention the Wear probe uses:
                    // negative ids are not backed by a challenge, so answering this one resolves nothing.
                    ActionRow(
                        title = stringResource(R.string.home_alerts_banner_preview),
                        description = stringResource(R.string.alerts_preview_desc),
                        icon = Icons.Default.Visibility,
                        onClick = {
                            ApprovalOverlay.show(
                                context = context,
                                challengeId = -1L,
                                macName = state.pairedMac?.name,
                                originNodeId = null,
                            )
                        },
                    )
                }
            }

            SectionCard(title = stringResource(R.string.alerts_notification_section)) {
                ActionRow(
                    title = stringResource(R.string.home_alerts_notifications),
                    description = stringResource(R.string.home_alerts_notifications_desc),
                    icon = Icons.Default.OpenInNew,
                    onClick = { openApprovalChannelSettings(context) },
                )
            }
        }
    }
}

/**
 * Opens Android's settings for the approval notification channel.
 *
 * Falls back through app notification settings to app details, since the per-channel screen is not
 * guaranteed to resolve on every device.
 */
private fun openApprovalChannelSettings(context: Context) {
    val candidates =
        listOf(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, UnlockNotifications.APPROVAL_CHANNEL_ID),
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null)),
        )
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next, less specific, screen.
        }
    }
}
