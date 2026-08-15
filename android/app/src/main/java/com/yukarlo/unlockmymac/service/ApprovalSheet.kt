package com.yukarlo.unlockmymac.service

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import com.yukarlo.unlockmymac.R
import kotlinx.coroutines.flow.first
import kotlin.math.min

/** Drag down past this, or fling down faster than [flingVelocity], to dismiss. */
private val dismissDistance = 88.dp

/** Drag up past this, or fling up faster than [flingVelocity], to open the app. */
private val openAppDistance = 56.dp

/** Share of the card's own height that also counts as "dragged far enough to dismiss". */
private const val dismissHeightFraction = 0.28f

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
    var translationYPx by remember { mutableFloatStateOf(0f) }

    /** 0 at rest, 1 when the card fills the screen. Drives gutter, corners, height and content fade. */
    var expansion by remember { mutableFloatStateOf(0f) }

    // Needed to rise from exactly off-screen and to leave the same way, rather than from a guessed
    // distance that would either start visible or overshoot and waste time.
    var cardHeightPx by remember { mutableFloatStateOf(0f) }

    /** True between finger down and finger up, so the entrance animation can stand aside. */
    var dragging by remember { mutableStateOf(false) }

    // Whichever is nearer: a fixed distance, or a share of the card. Fixed alone was measured against a
    // card that turned out to be ~210dp tall, making [dismissDistance] about 42% of it — a long way to
    // drag something that only has to be put away, and reported as a swipe that would not dismiss.
    val dismissThresholdPx =
        if (cardHeightPx > 0f) min(dismissPx, cardHeightPx * dismissHeightFraction) else dismissPx

    // The entrance runs off the same offset the drag uses, so a swipe that arrives mid-entrance
    // continues from wherever the card actually is instead of jumping to meet the finger.
    //
    // Keyed on `Unit`, and waiting for the height from inside rather than being keyed on it. That is the
    // whole point: the height arrives as a sequence, not a value, because the card is measured more than
    // once. Keyed on `cardHeightPx` this effect was cancelled and restarted by the second measurement —
    // the restart found `entered` already true, skipped the body, and left the card parked off-screen.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val height = snapshotFlow { cardHeightPx }.first { it > 0f }
        translationYPx = height
        // Revealed only now, with the card already off the bottom. Before this it is transparent, so the
        // frames between first composition and a known height are not seen at the final position.
        entered = true
        animate(initialValue = height, targetValue = 0f, animationSpec = tween(240)) { value, _ ->
            // Stands aside if the user grabbed the card mid-rise. This effect is not cancelled by a
            // drag — it is keyed on `Unit` deliberately — so without the guard the entrance and the
            // finger would write the same state in alternate frames and the card would judder.
            if (!dragging) translationYPx = value
        }
    }

    val revealed by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(240),
        label = "approvalBannerReveal",
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val fullHeightPx = with(density) { maxHeight.toPx() }

        // Scrim. Fades out with the expansion, because by then the card covers what it was dimming.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimOpacity * revealed * (1f - expansion)))
                    // Tap to put away without answering — the same outcome as a swipe down. Consuming
                    // taps here is what makes the window modal; see the class comment.
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
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
                        translationY = translationYPx
                        alpha = revealed
                    }.draggable(
                        orientation = Orientation.Vertical,
                        // Once the expansion starts the gesture is over and its outcome is decided;
                        // further drag would fight the animation.
                        enabled = expansion == 0f,
                        state =
                            rememberDraggableState { delta ->
                                val next = translationYPx + delta
                                // Downward is free; upward is damped and capped.
                                translationYPx =
                                    if (next >= 0f) {
                                        next
                                    } else {
                                        (translationYPx + delta * upwardDragDamping)
                                            .coerceAtLeast(-upLimitPx)
                                    }
                            },
                        onDragStarted = { dragging = true },
                        onDragStopped = { velocity ->
                            dragging = false
                            when {
                                translationYPx > dismissThresholdPx || velocity > flingVelocity -> {
                                    // All the way off the bottom first, *then* report. Tearing the
                                    // window down on the upstroke makes the card vanish rather than
                                    // leave.
                                    animate(
                                        initialValue = translationYPx,
                                        targetValue =
                                            cardHeightPx.takeIf { it > 0f }
                                                ?: (dismissPx * 3f),
                                        animationSpec = tween(180),
                                    ) { value, _ -> translationYPx = value }
                                    onDismiss()
                                }

                                translationYPx < -openAppPx || velocity < -flingVelocity -> {
                                    // Grow into the screen first, and settle back to zero offset while
                                    // doing it, so the card is exactly full-screen when it hands over.
                                    val from = translationYPx
                                    animate(
                                        initialValue = 0f,
                                        targetValue = 1f,
                                        animationSpec = tween(260),
                                    ) { value, _ ->
                                        expansion = value
                                        translationYPx = lerp(from, 0f, value)
                                    }
                                    // Only now: the activity fades in over a full-screen surface of its
                                    // own colour, so the hand-over reads as the card becoming the app
                                    // rather than as a window appearing over a banner.
                                    onOpenApp()
                                }

                                else -> {
                                    animate(
                                        initialValue = translationYPx,
                                        targetValue = 0f,
                                        animationSpec = spring(),
                                    ) { value, _ -> translationYPx = value }
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
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The grabber is honest: the card really is draggable, in both directions.
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
