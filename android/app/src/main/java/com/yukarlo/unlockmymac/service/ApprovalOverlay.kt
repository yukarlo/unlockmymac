package com.yukarlo.unlockmymac.service

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.yukarlo.unlockmymac.MainActivity
import com.yukarlo.unlockmymac.container
import com.yukarlo.unlockmymac.ui.theme.UnlockMyMacTheme
import java.lang.ref.WeakReference

/**
 * Floating approval banner drawn over whatever is on screen, which can expand into the app.
 *
 * Strictly additive to the notification, which is still posted. Two reasons it has to be:
 *
 * 1. **An overlay cannot be drawn over the keyguard.** Since Android 8, `TYPE_APPLICATION_OVERLAY`
 *    sits *below* system-critical windows including the lock screen — so a locked phone sees only
 *    the notification, and that is the common case when a Mac is being unlocked.
 * 2. The overlay needs a special permission the user may not have granted.
 *
 * So this improves the unlocked-and-using-the-phone case and changes nothing else. Answering here
 * dispatches the identical broadcast a notification action would, so there is one resolution path
 * rather than two.
 *
 * The card is Compose ([ApprovalSheet]); this object is only the window around it. That costs the
 * lifecycle plumbing in [OverlayOwners] — see there for why a `ComposeView` cannot simply be added
 * to a `WindowManager` the way an inflated `View` can.
 */
object ApprovalOverlay {
    private const val TAG = "ApprovalOverlay"

    /**
     * How long the expanded card stays up after the app is launched.
     *
     * Long enough for the activity to be drawn behind it, short enough not to be felt. Removing
     * in the same frame showed whatever was behind the banner for a frame or two first.
     */
    private const val OPEN_APP_REMOVE_DELAY_MS = 220L

    /**
     * The card currently on screen, if any. Touched only on the main thread; see [onMain].
     *
     * Weak on purpose, and safe to be weak: `WindowManager.addView` hands the view to
     * `WindowManagerGlobal`, which holds it strongly for as long as it is attached — so the only
     * thing this reference has to survive is long enough to pass the same view back to `removeView`.
     * If it has been collected, the view is already gone and there is nothing to remove.
     *
     * A strong reference here would be a static field pointing at a `View`, which lint flags as a
     * context leak. It would not actually leak an `Activity` — the card is created with the
     * application context, which outlives everything anyway — but a singleton holding a live view
     * tree is still the wrong shape, and weakening it costs nothing.
     *
     * Not restructured into an instance owned by the service: the notifier that reaches it is itself
     * a singleton object, so that would relocate the static-ness rather than remove it.
     */
    private var shown: WeakReference<View>? = null

    /**
     * Owners for the card on screen. Strong, unlike [shown], and deliberately so.
     *
     * [OverlayOwners] holds no `Context` and no `View`, so a strong reference is not a leak of the
     * kind [shown] is avoiding. It has to be strong: nothing else retains it, so a weak reference
     * could be collected while the card is still up, and then the lifecycle would never be moved to
     * `DESTROYED` and the `ViewModelStore` never cleared.
     */
    private var owners: OverlayOwners? = null

    private val main = Handler(Looper.getMainLooper())

    fun isPermitted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * Where to send the user to grant the permission.
     *
     * There is no runtime-permission dialog for this one — it is a per-app toggle buried in Settings,
     * so the app has to hand the user straight to it. Also grantable without the UI, which is how it
     * gets set on a test device: `adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow`.
     */
    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    fun show(
        context: Context,
        challengeId: Long,
        macName: String?,
        originNodeId: String?,
    ) {
        val appContext = context.applicationContext
        if (!isPermitted(appContext)) {
            // Logged rather than returning in silence. A prompt that never appears is the hardest thing
            // to diagnose here, and "the permission is gone" looks identical from the outside to every
            // other reason — the notification still arrives either way.
            Log.i(TAG, "No overlay permission; the notification is the only prompt")
            return
        }

        onMain {
            Log.i(TAG, "Raising the approval banner for challenge $challengeId")
            // Replace rather than stack. A second challenge while one is showing is a new question,
            // and two cards would leave the older one answerable against a dead challenge.
            removeNow(appContext, reason = "replaced by a newer prompt")

            val manager = appContext.getSystemService(WindowManager::class.java) ?: return@onMain

            val cardOwners = OverlayOwners()
            val view =
                runCatching { ComposeView(appContext) }.getOrElse {
                    Log.w(TAG, "Could not create the approval sheet", it)
                    return@onMain
                }

            // The load-bearing line, not the styling. This card authorises unlocking a Mac, so a
            // window sitting on top of it could otherwise place its own transparent view over
            // Approve and harvest the tap. With this set the framework discards any touch arriving
            // while another window obscures this one — the standard defence for a consequential
            // prompt, and the reason an overlay is an acceptable surface for one at all.
            view.filterTouchesWhenObscured = true

            // Compose finds its recomposer, and anything lifecycle-scoped, through the view tree.
            // Outside an Activity there is nothing to inherit, so these three are supplied here or
            // `setContent` composes nothing at all.
            view.setViewTreeLifecycleOwner(cardOwners)
            view.setViewTreeViewModelStoreOwner(cardOwners)
            view.setViewTreeSavedStateRegistryOwner(cardOwners)

            view.setContent {
                UnlockMyMacTheme {
                    ApprovalSheet(
                        macName = macName,
                        onApprove = {
                            answer(appContext, challengeId, originNodeId, approved = true)
                        },
                        onDeny = {
                            answer(appContext, challengeId, originNodeId, approved = false)
                        },
                        // Answers nothing. The challenge stays live and the notification stays in
                        // the shade, so a swipe costs the user nothing but the banner.
                        onDismiss = { removeNow(appContext, reason = "swiped away or scrim tapped") },
                        onOpenApp = { openApp(appContext, originNodeId) },
                    )
                }
            }

            // Resumed before the view is attached: `setContent` above only queues the composition,
            // and it will not run until the lifecycle is at least `CREATED`.
            cardOwners.markResumed()

            runCatching { manager.addView(view, layoutParams()) }
                .onSuccess {
                    shown = WeakReference(view)
                    owners = cardOwners
                }.onFailure {
                    // Nothing was attached, so tear the owners down here or the composition is left
                    // live with no window to draw into.
                    cardOwners.markDestroyed()
                    Log.w(TAG, "Could not add the approval overlay", it)
                }
        }
    }

    fun hide(context: Context) {
        val appContext = context.applicationContext
        onMain { removeNow(appContext, reason = "the request was withdrawn") }
    }

    /**
     * Answering goes through the same receiver the notification actions use.
     *
     * Deliberately not a direct call into the service: the receiver already handles both cases — a
     * challenge this device holds, and a mirrored one that has to be answered on the device that
     * holds it — and duplicating that here is how the two surfaces would drift apart.
     */
    private fun answer(
        context: Context,
        challengeId: Long,
        originNodeId: String?,
        approved: Boolean,
    ) {
        // Down first, so the card cannot be tapped twice while the broadcast is in flight.
        removeNow(context, reason = if (approved) "approved" else "denied")
        val intent =
            Intent(context, ApprovalActionReceiver::class.java).apply {
                action =
                    if (approved) {
                        ApprovalActionReceiver.ACTION_APPROVE
                    } else {
                        ApprovalActionReceiver.ACTION_DENY
                    }
                putExtra(ApprovalActionReceiver.EXTRA_CHALLENGE_ID, challengeId)
                putExtra(ApprovalActionReceiver.EXTRA_ORIGIN_NODE, originNodeId)
            }
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "Could not dispatch the approval decision", it) }
    }

    /**
     * Opens the app, fading it in over the expanded card.
     *
     * A plain cross-fade, not `makeScaleUpAnimation`. Scaling the activity up out of the card's bounds
     * was the right transition while the card was a small banner, but the card now expands to fill the
     * screen before this is called — so there is nothing left to scale *from*, and a fade is what makes
     * the full-screen surface become the app.
     *
     * The banner is taken down on a short delay rather than immediately, so the activity has time to
     * appear behind it. Removing it in the same frame put whatever was behind the banner back on screen
     * for a frame or two before the activity arrived, which is the flash this is avoiding.
     *
     * Starting an activity from a service context is normally blocked on Android 10+. This is exempt:
     * holding `SYSTEM_ALERT_WINDOW`, which this overlay cannot exist without, is one of the listed
     * background-activity-launch exemptions.
     */
    private fun openApp(
        context: Context,
        originNodeId: String?,
    ) {
        // The notification goes too, but only for a challenge this device holds.
        //
        // Swiping up hands the question to the app, which shows it as a card on the home screen — so
        // leaving the notification up is a second copy of a question already on screen. Unlike a swipe
        // *down*, which deliberately keeps it: that gesture means "not now", and the shade is then the
        // only place left to answer from.
        //
        // A mirrored prompt is the exception. It belongs to the other device, and `setPendingApproval`
        // is only ever called for this device's own challenges — so the app has no card to show for one,
        // and cancelling its notification would leave the phone with no surface for it at all.
        if (originNodeId == null) {
            runCatching { context.container.notifier.cancelApproval(context) }
                .onFailure { Log.w(TAG, "Could not withdraw the approval notification", it) }
        }

        val intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val options =
            runCatching {
                ActivityOptions
                    .makeCustomAnimation(
                        context,
                        android.R.anim.fade_in,
                        android.R.anim.fade_out,
                    ).toBundle()
            }.getOrNull()
        runCatching { context.startActivity(intent, options) }
            .onFailure { Log.w(TAG, "Could not open the app from the approval banner", it) }

        // Captured, and removed by identity rather than by "whatever is current".
        //
        // `removeNow()` operates on `shown`/`owners` as they are *when it runs*. On a delay that is a
        // different card if a fresh challenge arrived in the meantime, and this would then tear down the
        // new prompt and leave the state cleared — a prompt that never appears, with nothing in the log
        // to say why.
        val view = shown?.get()
        val cardOwners = owners
        shown = null
        owners = null
        Log.i(TAG, "Opening the app from the banner; removing it in ${OPEN_APP_REMOVE_DELAY_MS}ms")
        main.postDelayed({ detach(context, view, cardOwners, reason = "opened the app") }, OPEN_APP_REMOVE_DELAY_MS)
    }

    @SuppressLint("InlinedApi") // TYPE_APPLICATION_OVERLAY is API 26+; minSdk here is 31.
    private fun layoutParams(): WindowManager.LayoutParams =
        WindowManager
            .LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                // Full screen, not wrapped to the card. A window the height of the card clipped the card
                // the moment it was dragged upwards — the title sheared off mid-letter — and left no room
                // for it to expand into.
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_FOCUSABLE keeps the keyboard and the back button with whatever is underneath. Touches
                // still reach the card.
                //
                // No DIM_BEHIND: the scrim is drawn in Compose now. A window-level dim was the only way to
                // dim another app while this window was card-sized, but a full-screen window covers the
                // same pixels itself — and unlike `dimAmount` a Compose scrim can be animated, which is
                // what lets it fade out as the card expands over it.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

    /**
     * Clears the current card, whatever it is. Must run on the main thread: `WindowManager` view
     * operations are not thread-safe.
     *
     * Every exit goes through here or through [detach] — approve, deny, swipe away, tap the scrim, swipe
     * up, a replacing prompt, and the service withdrawing the request. All of them null out both fields
     * and destroy the lifecycle, so no exit can leave a card half-registered and block the next prompt.
     */
    private fun removeNow(
        context: Context,
        reason: String,
    ) {
        val view = shown?.get()
        val cardOwners = owners
        shown = null
        owners = null
        detach(context, view, cardOwners, reason)
    }

    /**
     * Takes down one specific card, identified by the view and owners handed in.
     *
     * Separate from [removeNow] so a delayed removal cannot act on a card that replaced the one it was
     * scheduled for. See [openApp].
     */
    private fun detach(
        context: Context,
        view: View?,
        cardOwners: OverlayOwners?,
        reason: String,
    ) {
        if (view == null && cardOwners == null) return
        Log.i(TAG, "Taking the approval banner down ($reason)")
        if (view != null) {
            val manager = context.getSystemService(WindowManager::class.java)
            // Throws if the view was already detached, which happens when the system tears overlays
            // down for us — not worth distinguishing from success.
            if (manager != null) runCatching { manager.removeView(view) }
        }
        // After the window is gone, so the composition is not disposed while still on screen.
        cardOwners?.markDestroyed()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /**
     * The lifecycle a `ComposeView` needs and a `WindowManager` window does not supply.
     *
     * An inflated XML `View` needs none of this — it draws as soon as it is attached. Compose is
     * different: `AbstractComposeView` resolves a recomposer from the view tree's `LifecycleOwner`
     * and refuses to compose without one, and `rememberSaveable` and `viewModel()` need the other
     * two. Inside an `Activity` all three come from the `Activity`; here there is no `Activity`, so
     * they are provided by hand and torn down with the card.
     *
     * Holds no `Context` and no `View`, which is what makes it safe for [owners] to reference it
     * strongly.
     */
    private class OverlayOwners :
        LifecycleOwner,
        ViewModelStoreOwner,
        SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry

        override val viewModelStore: ViewModelStore = ViewModelStore()

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        init {
            // Restored with nothing to restore. There is no saved state to carry — the card is
            // recreated from the challenge on every prompt — but the registry must be restored
            // before the lifecycle moves past `INITIALIZED` or reading it throws.
            savedStateController.performRestore(null)
        }

        fun markResumed() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun markDestroyed() {
            // Guarded: `DESTROYED` is terminal, and `LifecycleRegistry` throws on any move out of
            // it. `hide()` immediately after answering would otherwise crash, and both paths call
            // `removeNow`.
            if (registry.currentState == Lifecycle.State.DESTROYED) return
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
