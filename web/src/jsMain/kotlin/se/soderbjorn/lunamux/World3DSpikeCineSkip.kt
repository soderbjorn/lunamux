/*
 * Split from World3DSpike.kt — the cinematic **skip**: pressing Enter or Esc while one of the
 * [spikeCinematicAnimations] sequences is playing fast-forwards it to its end instead of letting
 * it run its full length.
 *
 * The whole feature is one time-scale knob. Every cinematic in the 3D world is a per-frame tick
 * driven off the render loop's [spikeDtFrames] delta — there are no coroutines, timeouts or CSS
 * transitions in play — so "play it fast" is just a multiplier on that delta, read through
 * [cineDt]. Nothing else changes: the sequences run their normal code, hit their normal
 * completion branches, and fire their normal callbacks (`commitBundleUnlist`, the `flyCamTo`
 * `then` continuations, `applyWorldPalette`) in their normal order. That is the point of doing it
 * this way rather than jamming each cinematic to its end state: there is no second code path to
 * drift out of sync with the first.
 *
 * The multiplier is deliberately *not* applied to [spikeDtFrames] itself, because that delta also
 * drives ambient motion — warp-core charge, explosion debris, the warp clock — which must keep
 * running at wall-clock speed while a cinematic is being skipped.
 *
 * @see cineDt @see skipCinematics @see cinematicInFlight @see settleCineScale
 * @see SPIKE_CINE_SKIP_SCALE @see spikeCineScale
 */
package se.soderbjorn.lunamux

/**
 * The delta the **cinematic** clocks advance by: [spikeDtFrames] scaled by [spikeCineScale].
 *
 * Called from every cinematic tick — [tickWormhole], [tickWorldTransit], [tickPhaser],
 * [tickBundles], the camera-tour step and the stash-flight step in the render loop. Equal to
 * [spikeDtFrames] unless the user has asked to skip, so on the normal path this changes nothing.
 *
 * Ambient per-frame motion ([tickWarpCore], [tickExplosions]) deliberately keeps reading
 * [spikeDtFrames] directly — it is not part of any cinematic and must not speed up.
 *
 * @return 60fps-equivalent frames elapsed, multiplied by the active cinematic time-scale.
 * @see spikeCineScale @see spikeDtFrames
 */
internal fun cineDt(): Double = spikeDtFrames * spikeCineScale

/**
 * A cinematic duration in **wall-clock seconds** at the current time-scale — the bridge for the
 * one thing [cineDt] does not carry over on its own: sound.
 *
 * The audio effects are handed a length in seconds and then schedule their envelopes against the
 * Web Audio clock, which knows nothing about [spikeCineScale]. Their callers derive that length
 * from a distance in cinematic frames, so under a skip the visuals finish while the sound plays
 * on at its original length. Routing those conversions through here keeps the two in step.
 *
 * Only corrects sounds that *start* while a skip is already running. One already scheduled when
 * the user presses Enter/Esc plays out its natural length — the audio layer has no stop handle to
 * cut it short — so a skip pressed mid-hum leaves a tail of at most ~2s. Bounded and rare enough
 * to accept rather than build a whole cancellation path for.
 *
 * @param frames the duration in cinematic (60fps-equivalent) frames.
 * @return that duration in real seconds, given the scale the cinematic clocks are running at.
 * @see cineDt @see playWormholeTravel @see playWormholeAppear
 */
internal fun cineSeconds(frames: Double): Double = frames / 60.0 / spikeCineScale

/**
 * Whether any [spikeCinematicAnimations] sequence is currently playing.
 *
 * Called by [skipCinematics] to decide whether there is anything to skip (so the keypress can
 * fall through to its normal meaning if not), and by [settleCineScale] each frame to decide when
 * to drop the time-scale back to 1.0.
 *
 * Covers the entry cinematic that tilts the 2D shell away on open, the world-switch fly-through,
 * a pane's arrival wormhole, any scripted camera tour or live stash chase, a phaser death
 * mid-barrage, a tab bundle anywhere but at rest on the dock, and a pane still travelling on its
 * stash flight. That last check is what keeps the scale alive
 * until the pane physically lands, since the camera can finish its tour a frame or two before the
 * pane settles — and it defers to [paneInStashFlight] rather than comparing [RingPane.stashProg]
 * to a target here, because a pane in a *parked* bundle rests at `stashProg == 1.0` while no
 * longer being in [spikeStashed]. Read naively that pane looks eternally mid-flight, which would
 * pin the scale at [SPIKE_CINE_SKIP_SCALE] for the rest of the session and make Esc stop closing
 * the world on the first press.
 *
 * The demo tour ([spikeMovieJob]) is intentionally excluded — it is a scripted coroutine rather
 * than a gated cinematic, and the key handler already locks the keyboard out while it runs.
 *
 * @return true if at least one cinematic is mid-flight.
 * @see skipCinematics @see settleCineScale @see paneInStashFlight
 */
internal fun cinematicInFlight(): Boolean =
    spikeIntro != null ||
        spikeWorldTransit != null ||
        spikeWormholes.isNotEmpty() ||
        spikeCamReturning ||
        spikeStashChase != null ||
        spikePanes.any { it.phaserPhase >= 0.0 } ||
        spikeStashedTabs.any { it.state != BundleState.PARKED } ||
        spikePanes.any { paneInStashFlight(it) }

/**
 * Fast-forward whatever cinematic is playing — the Enter/Esc handler's entry point.
 *
 * Called from [buildKeyHandler] before the key reaches its normal meaning. Returns false when
 * nothing is playing, which is the signal for the handler to let the key through: Esc still
 * closes the world, Enter still engages the front pane. A second press *during* a skip also
 * returns false, since the scale is already set, so the key falls through as usual — pressing
 * Esc twice skips and then closes.
 *
 * Does not touch any cinematic's state; it only speeds up their shared clock. [settleCineScale]
 * undoes it once they finish.
 *
 * Inert while [spikeCinematicAnimations] is off. Skipping is a feature *of* that setting, so with
 * it switched off Enter/Esc must keep their plain meanings. The check is not redundant: a few
 * camera tours the setting does not gate — [resetCamera]'s flight home, the free-flight pane
 * fly-bys — still raise [spikeCamReturning], and without this they would swallow the first Esc in
 * a world where the user had turned cinematics off entirely.
 *
 * @return true if a cinematic was in flight and is now being fast-forwarded (the caller should
 *   consume the key), false if there was nothing to skip (the caller should let the key through).
 * @see cinematicInFlight @see SPIKE_CINE_SKIP_SCALE @see settleCineScale
 */
internal fun skipCinematics(): Boolean {
    if (!spikeCinematicAnimations) return false
    if (spikeCineScale != 1.0) return false // already skipping — let the key mean what it means
    if (!cinematicInFlight()) return false
    spikeCineScale = SPIKE_CINE_SKIP_SCALE
    return true
}

/**
 * Drop the cinematic time-scale back to 1.0 once nothing is left playing.
 *
 * Called by the render loop at the top of every frame, right after it recomputes [spikeDtFrames]
 * and before any tick runs — so it reads the state the previous frame's ticks left behind, and a
 * skip can never leak into the next cinematic.
 *
 * @see cinematicInFlight @see spikeCineScale
 */
internal fun settleCineScale() {
    if (spikeCineScale != 1.0 && !cinematicInFlight()) spikeCineScale = 1.0
}
