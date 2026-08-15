package com.yukarlo.unlockmymac.service

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import com.yukarlo.unlockmymac.R
import com.yukarlo.unlockmymac.container
import kotlinx.coroutines.delay

/** Drag down past this, or fling down faster than [flingVelocity], to dismiss. */
private val dismissDistance = 64.dp

/** Drag up past this, or fling up faster than [flingVelocity], to open the app. */
private val openAppDistance = 56.dp

/** px/s at which a flick decides the gesture regardless of how far it travelled. */
private const val flingVelocity = 700f

/**
 * How far up the banner will travel under the finger, and how heavily upward drag is damped.
 *
 * Upward is a hint, not a journey: past [openAppDistance] the expansion takes over, so following the
 * finger any further would fight the animation that replaces it.
 */
private val openAppTravelLimit = 72.dp
private const val upwardDragDamping = 0.4f

/** Resting gutter around the card, animated to zero as it expands to fill the screen. */
private val restingGutter = 12.dp

/** Resting corner radius, animated to zero as it expands — a full-screen surface has no corners. */
private val restingCorner = 28.dp

private const val scrimOpacity = 0.45f

/**
 * How long to wait for the entrance animation before showing the card without it.
 *
 * Comfortably longer than the 240 ms entrance, short enough that a prompt is never lost. A prompt that
 * arrives late still beats one that never arrives.
 */
private const val REVEAL_WATCHDOG_MS = 600L

/**
 * The approval prompt: a floating banner that can expand into the app.
 *
 * Fills its window and draws its own scrim, rather than being a bottom-anchored `WRAP_CONTENT` window
 * dimmed by `FLAG_DIM_BEHIND`. That earlier shape had two faults. The window was exactly as tall as the
 * card, so dragging the card upwards moved it outside the window and it was **clipped** — the title
 * sheared off mid-letter. And a window-level dim cannot be animated, so there was no way to fade the
 * scrim as the card expands. A full-screen window makes the Compose scrim cover the same pixels, and it
 * can be animated like anything else.
 *
 * The cost, stated plainly: a full-screen touchable window is **modal**. Taps outside the card no longer
 * reach the app underneath — they dismiss instead, which is the conventional reading of a scrim.
 *
 * Deliberately not `ModalBottomSheet`: that brings its own dialog window, which would nest a second
 * window inside this one and fight it for the scrim and the insets.
 *
 * Four ways out, and only two of them are answers:
 *
 * - the buttons, which answer;
 * - swipe down, or a tap on the scrim, which takes the banner away **without answering** — the challenge
 *   stays live and the notification is still in the shade to answer from;
 * - swipe up, which expands the card to fill the screen and then opens the app.
 *
 * A swipe deliberately cannot approve or deny. This prompt authorises unlocking a Mac, and a gesture
 * that answers it is a gesture that can answer it in a pocket; "put this away" and "yes" must not be the
 * same motion. Dismissing without deciding carries no such hazard, which is exactly why it is the thing
 * a swipe is allowed to do.
 *
 * @param macName the Mac asking, or null when it did not identify itself.
 * @param onDismiss take the banner down, leaving the challenge unanswered.
 * @param onOpenApp open the app. Called once the card already fills the screen, so the activity fades in
 *        over a surface the same colour as itself rather than over whatever was behind the banner.
 */
@Composable
internal fun ApprovalSheet(
    macName: String?,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit,
) {
    // Read here rather than passed in, so a toggle flipped in the app takes effect on the card that is
    // already on screen — which is what makes the preview a preview rather than a screenshot. Null until
    // the first emission, and every gesture is read as enabled in that window, matching the defaults.
    val settings by LocalContext.current.container.settings.settings
        .collectAsState(initial = null)
    val swipeUpOpensApp = settings?.bannerSwipeUpOpensApp != false
    val swipeDownDismisses = settings?.bannerSwipeDownDismisses != false
    val scrimTapDismisses = settings?.bannerScrimTapDismisses != false

    val density = LocalDensity.current
    val dismissPx = with(density) { dismissDistance.toPx() }
    val openAppPx = with(density) { openAppDistance.toPx() }
    val upLimitPx = with(density) { openAppTravelLimit.toPx() }

    // Plain state, written synchronously, rather than an `Animatable` driven from the drag callback.
    //
    // That was the previous shape and it was broken: `rememberDraggableState` fired one
    // `scope.launch { snapTo(…) }` per delta, and those coroutines could land *after* `onDragStopped`
    // had begun settling — `Animatable.snapTo` cancels a running animation, so a single stray delta
    // froze the card wherever it was, neither dismissed nor sprung back.
    //
    // Drag writes go straight to this state, so they are ordered with respect to each other and to the
    // release. Animations write it too, via `animate`, and a new drag cancels a running release because
    // `draggable` cancels the previous `onDragStopped` — so the finger always wins, from wherever the
    // card actually is.
    var dragPx by remember { mutableFloatStateOf(0f) }

    /** 0 at rest, 1 when the card fills the screen. Drives gutter, corners, height and content fade. */
    var expansion by remember { mutableFloatStateOf(0f) }

    /** The card's measured height, used only to expand it to full screen. Never gates visibility. */
    var cardHeightPx by remember { mutableFloatStateOf(0f) }

    /**
     * 0 = off the bottom and transparent, 1 = in place and opaque. Runs forwards to appear, backwards to
     * dismiss.
     *
     * The entrance no longer waits to be told how tall the card is, and that is the point. It used to:
     * `snapshotFlow { cardHeightPx }.first { it > 0f }` gated *both* the slide and the alpha, so if that
     * handshake ever failed the card stayed at `alpha = 0` — window attached, full-screen, laid out,
     * drawing nothing. Which is exactly the intermittent report: window added and measured 1248x1933,
     * up for 4.6 s, and only the notification seen. It fired on two prompts and not the third with no
     * other difference between them.
     *
     * Now the reveal is a plain time animation with no inputs, and the offset it needs comes from
     * `size.height` inside the `graphicsLayer` below — a value the layer always has at draw time. There
     * is no longer a state handshake that can be lost.
     *
     * It is still an *animation*, though, and that is its own hazard: an animation needs a frame clock,
     * and a `ComposeView` in a `WindowManager` window gets its clock from a recomposer this code does not
     * own. If that clock never ticks, `animate` never runs its callback and the card stays at progress 0
     * — laid out at 1248x1933 and drawing nothing. So the watchdog below is not decoration; it is what
     * stops "the animation did not start" from meaning "the prompt was never shown".
     */
    var revealProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        animate(initialValue = 0f, targetValue = 1f, animationSpec = tween(240)) { value, _ ->
            revealProgress = value
        }
    }

    // `delay` is driven by the dispatcher's `Handler`, not by the frame clock, so this fires even when
    // the clock that `animate` above depends on is stuck. Visibility is the thing that matters; the slide
    // is not. If the animation has not moved by now, put the card on screen without it.
    LaunchedEffect(Unit) {
        delay(REVEAL_WATCHDOG_MS)
        if (revealProgress == 0f) {
            android.util.Log.w(
                "ApprovalSheet",
                "Reveal animation never ran; showing the card without it (no frame clock)",
            )
            revealProgress = 1f
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val fullHeightPx = with(density) { maxHeight.toPx() }

        // Scrim. Fades out with the expansion, because by then the card covers what it was dimming.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimOpacity * revealProgress * (1f - expansion)))
                    // Tap to put away without answering — the same outcome as a swipe down. Consuming
                    // taps here is what makes the window modal; see the class comment.
                    //
                    // The modifier is omitted entirely when the setting is off rather than given an
                    // empty callback: an empty tap handler still consumes the touch, which would leave
                    // the scrim swallowing taps and doing nothing with them.
                    .then(
                        if (scrimTapDismisses) {
                            Modifier.pointerInput(Unit) { detectTapGestures { onDismiss() } }
                        } else {
                            Modifier
                        },
                    ),
        )

        Surface(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // Recorded only at rest. While expanding, the height below is *derived* from this
                    // value, so recording the growing height would feed the animation its own output.
                    .onSizeChanged { if (expansion == 0f) cardHeightPx = it.height.toFloat() }
                    .then(
                        if (expansion > 0f && cardHeightPx > 0f) {
                            Modifier.height(
                                with(density) { lerp(cardHeightPx, fullHeightPx, expansion).toDp() },
                            )
                        } else {
                            Modifier
                        },
                    )
                    // `graphicsLayer`, not `offset`: a draw-time transform, so dragging cannot trigger a
                    // layout pass. Alpha is folded in rather than stacking a second layer.
                    .graphicsLayer {
                        // `size.height` is the card's own height at draw time, so the off-screen
                        // distance never has to be measured, published and read back. That round trip
                        // is what could silently fail and leave the card drawing nothing.
                        translationY = dragPx + (1f - revealProgress) * size.height
                        alpha = revealProgress
                    }.draggable(
                        orientation = Orientation.Vertical,
                        // Once the expansion starts the gesture is over and its outcome is decided;
                        // further drag would fight the animation. Also off when neither direction is
                        // configured to do anything, so the card does not rubber-band pointlessly.
                        enabled = expansion == 0f && (swipeUpOpensApp || swipeDownDismisses),
                        state =
                            rememberDraggableState { delta ->
                                val next = dragPx + delta
                                // Downward is free; upward is damped and capped. A direction that is
                                // switched off does not move at all — following the finger towards an
                                // outcome that will not happen promises something the release cannot
                                // deliver.
                                dragPx =
                                    when {
                                        next >= 0f -> {
                                            if (swipeDownDismisses) next else 0f
                                        }

                                        !swipeUpOpensApp -> {
                                            0f
                                        }

                                        else -> {
                                            (dragPx + delta * upwardDragDamping)
                                                .coerceAtLeast(-upLimitPx)
                                        }
                                    }
                            },
                        onDragStopped = { velocity ->
                            when {
                                swipeDownDismisses &&
                                    (dragPx > dismissPx || velocity > flingVelocity) -> {
                                    // Dismissal is the entrance in reverse: run the reveal back to zero
                                    // and the card slides off the bottom and fades as it goes. Needs no
                                    // measured height, and reports only once it has left rather than
                                    // vanishing on the upstroke.
                                    animate(
                                        initialValue = revealProgress,
                                        targetValue = 0f,
                                        animationSpec = tween(180),
                                    ) { value, _ -> revealProgress = value }
                                    onDismiss()
                                }

                                swipeUpOpensApp &&
                                    (dragPx < -openAppPx || velocity < -flingVelocity) -> {
                                    // Grow into the screen first, and settle back to zero offset while
                                    // doing it, so the card is exactly full-screen when it hands over.
                                    val from = dragPx
                                    animate(
                                        initialValue = 0f,
                                        targetValue = 1f,
                                        animationSpec = tween(260),
                                    ) { value, _ ->
                                        expansion = value
                                        dragPx = lerp(from, 0f, value)
                                    }
                                    // Only now: the activity fades in over a full-screen surface of its
                                    // own colour, so the hand-over reads as the card becoming the app
                                    // rather than as a window appearing over a banner.
                                    onOpenApp()
                                }

                                else -> {
                                    animate(
                                        initialValue = dragPx,
                                        targetValue = 0f,
                                        animationSpec = spring(),
                                    ) { value, _ -> dragPx = value }
                                }
                            }
                        },
                    ).padding(lerp(restingGutter, 0.dp, expansion))
                    // Additive insurance, not the margin itself: a full-screen overlay window is
                    // normally laid out clear of the system bars, in which case this contributes
                    // nothing. Dropped as the card expands, since a full-screen surface wants the bars.
                    .then(if (expansion == 0f) Modifier.navigationBarsPadding() else Modifier),
            shape = RoundedCornerShape(lerp(restingCorner, 0.dp, expansion)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            // The cast shadow is what sells "floating"; the gutter alone reads as a card with a margin.
            // Gone once expanded — a full-screen surface casting a shadow has nothing to cast it onto.
            shadowElevation = lerp(8.dp, 0.dp, expansion),
        ) {
            Column(
                modifier =
                    Modifier
                        // Fades faster than the card grows, so the text is gone well before it could be
                        // seen stretched across a full screen.
                        .graphicsLayer { alpha = (1f - expansion * 2.2f).coerceIn(0f, 1f) }
                        .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 22.dp),
            ) {
                // Only when the card can actually be dragged. An affordance for a gesture that has
                // been switched off is worse than none — the same reason it was dropped when the card
                // was not draggable at all.
                if (swipeUpOpensApp || swipeDownDismisses) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(
                            Modifier
                                .size(width = 36.dp, height = 4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    CircleShape,
                                ),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }

                Text(
                    text = stringResource(R.string.overlay_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text =
                        macName?.let { stringResource(R.string.overlay_body_named, it) }
                            ?: stringResource(R.string.overlay_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(22.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onDeny,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(stringResource(R.string.overlay_deny))
                    }
                    Button(
                        onClick = onApprove,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(stringResource(R.string.overlay_approve))
                    }
                }
            }
        }
    }
}
