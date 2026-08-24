/**
 * Shared-element "dive" transition plumbing between the overview's mini panes
 * and the full-screen terminal.
 *
 * Tapping a terminal card in the overview should feel like diving into it: the
 * card's bounds morph into the terminal screen's content box, and the reverse
 * plays on back. This file carries the two composition locals the
 * transition needs ([LunamuxApp] provides them: the app-level
 * [SharedTransitionScope] around the NavHost and each involved destination's
 * [AnimatedVisibilityScope]) and the [diveSharedBounds] modifier both ends
 * attach.
 *
 * The experimental shared-transition opt-in is deliberately confined to this
 * file and [LunamuxApp]; call sites only see a plain [Modifier] extension.
 *
 * IMPORTANT constraint: [diveSharedBounds] keeps `sharedBounds`' default
 * `resizeMode = ScaleToBounds`, which measures the content once at its final
 * bounds and only layer-scales during the flight. The terminal side wraps an
 * `AndroidView` whose relayout fires size votes to the server
 * (see `TerminalScreen`'s layout listener) — `RemeasureToBounds` would relayout
 * it every frame of the animation and must never be used here.
 *
 * @see LunamuxApp
 * @see OverviewContent
 * @see TerminalScreen
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The app-level [SharedTransitionScope] wrapping the NavHost, or `null` when
 * the current composition is not inside one (previews, tests). Provided by
 * [LunamuxApp].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * The nav destination's [AnimatedVisibilityScope] (nav-compose's `composable`
 * content receiver), or `null` outside a destination that provides one.
 * Provided by [LunamuxApp] for the `tree` and `terminal/{sessionId}` routes —
 * the two ends of the dive.
 */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Shared-content key for the dive between a session's mini card and its
 * full-screen terminal. Keyed by session id; the overview attaches it only to
 * the single tapped pane (see `divePaneId` in [OverviewContent]) so linked
 * panes sharing a session can never register duplicate keys.
 *
 * @param sessionId the PTY session both ends render.
 * @return the shared-content key.
 */
fun diveKey(sessionId: String): String = "terminal-dive/$sessionId"

/**
 * How long the bounds take to fly, and on what curve. A tween rather than the
 * default spring, and the same duration as the routes' own fades in
 * [LunamuxApp], so the card's flight and the chrome swapping around it read as
 * one movement instead of two overlapping ones.
 */
private const val DIVE_DURATION_MS = 300

/**
 * How long the *outgoing* end stays visible. Both ends of the dive render the
 * same session, but not identically framed — the card shows it inside a mini
 * pane with a title bar, the terminal shows it in the full content box — so the
 * default half-second crossfade drew two copies of the same text at two scales
 * through each other for the whole flight, which is the "double image" the
 * transition looked wrong for. The incoming end is instead opaque from the first
 * frame and the outgoing one is gone within a couple of frames, leaving a single
 * rendering to scale up.
 */
private const val DIVE_HANDOFF_MS = 70

/**
 * Attach `sharedBounds` for [key] when composed inside a live shared-transition
 * scope AND a nav destination scope; a no-op otherwise, so call sites degrade
 * gracefully wherever the transition can't run (sidebar-originated opens, host
 * previews, tests).
 *
 * Keeps the default `ScaleToBounds` resize mode — see the file header for why
 * that default is load-bearing — and replaces the default crossfade with an
 * immediate handoff (see [DIVE_HANDOFF_MS]). The same spec serves both
 * directions: whichever end is entering appears at once, whichever is leaving
 * fades out immediately.
 *
 * @param key a [diveKey]; the same key must be attached on both ends.
 * @return this modifier, with `sharedBounds` appended when the scopes exist.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.diveSharedBounds(key: String): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val navScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        this@diveSharedBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = navScope,
            enter = EnterTransition.None,
            exit = fadeOut(tween(durationMillis = DIVE_HANDOFF_MS)),
            boundsTransform = { _, _ ->
                tween(durationMillis = DIVE_DURATION_MS, easing = FastOutSlowInEasing)
            },
        )
    }
}
