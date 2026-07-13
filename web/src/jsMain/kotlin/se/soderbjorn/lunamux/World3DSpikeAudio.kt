/**
 * The **3D-world sound effects** — procedural sci-fi audio for the spike world, synthesized
 * live in the browser via the Web Audio API (no bundled audio files). Every effect is built
 * from oscillators, filtered noise and saturation on a single shared [AudioContext], so the
 * whole thing is a few kB of code with zero asset weight.
 *
 * Five effects mirror the world's cinematic beats and are fired from their tick/trigger sites:
 *  - [playPhaserShot]        — one bright plasma zap per bolt volley of the phaser-fire close
 *                              ([tickPhaser]); called repeatedly through the ~4 s barrage.
 *  - [playExplosion]         — the pane blows up ([spawnPaneExplosion]): crack + brown-noise
 *                              body + long roiling rumble.
 *  - [playWormholeAppear]    — a wormhole tears open ([tickWormhole], as the funnel spirals
 *                              open): instant whoosh into a sustained shimmering bloom.
 *  - [playTerminalMaterialize] — a terminal arrives out of the vortex ([tickWormhole], as the
 *                              pane begins to emerge): a rising warp-in swoosh.
 *  - [playWormholeTravel]    — riding the tunnel to another world ([enterOrExitOtherWorld]):
 *                              a long dark hum under a rushing whoosh, duration matched to the
 *                              actual transit time by the caller.
 *
 * Everything is gated by [spikeSoundEffects] (the persisted `world3dSoundEffects` setting,
 * default ON) — when off, every entry point returns immediately and no context is even created.
 * Because these fire only from user-initiated world events (opening the world, closing/creating
 * panes, switching worlds), the [AudioContext] is always resumed from a real gesture, so no
 * autoplay policy blocks them.
 *
 * @see spikeSoundEffects
 * @see syncWorld3dRuntimeFromSettings
 */
package se.soderbjorn.lunamux

import kotlin.math.abs
import kotlin.random.Random
import kotlinx.browser.window
import org.khronos.webgl.Float32Array
import org.khronos.webgl.set

/**
 * The lazily-created shared Web Audio context (`AudioContext`), or `null` until the first
 * sound plays. Held as [dynamic] because there is no Kotlin/JS external for the full Web
 * Audio surface and the synthesis touches many of its node types. @see audioContext
 */
private var spikeAudioCtx: dynamic = null

/** The master gain node all effects route through, so one place sets the world's SFX level. */
private var spikeAudioMaster: dynamic = null

/** Master output level for all 3D sound effects (kept moderate so blasts don't clip). */
private const val SPIKE_SFX_MASTER = 0.55

/**
 * Return the shared [AudioContext], creating it on first call and resuming it if the browser
 * left it suspended. Returns `null` if the browser has no Web Audio support (the effects then
 * silently no-op). Callers must already have checked [spikeSoundEffects].
 *
 * @return the live context, or `null` when Web Audio is unavailable.
 */
private fun audioContext(): dynamic {
    if (spikeAudioCtx == null) {
        // Throws (and getOrNull → null) if the browser has no Web Audio support.
        spikeAudioCtx = runCatching {
            js("new (window.AudioContext || window.webkitAudioContext)()")
        }.getOrNull()
        if (spikeAudioCtx == null) return null
    }
    val ac = spikeAudioCtx
    if (ac.state == "suspended") runCatching { ac.resume() }
    return ac
}

/**
 * The master gain node, created once and reconnected to the destination. Its gain is
 * re-asserted each call so a future settings-driven level change would apply immediately.
 *
 * @return the master [GainNode] every effect connects to. @see audioContext
 */
private fun masterOut(): dynamic {
    val ac = audioContext() ?: return null
    if (spikeAudioMaster == null) {
        spikeAudioMaster = ac.createGain()
        spikeAudioMaster.connect(ac.destination)
    }
    spikeAudioMaster.gain.value = SPIKE_SFX_MASTER
    return spikeAudioMaster
}

/**
 * Build an [AudioBuffer] of white noise `dur` seconds long. The raw material for cracks,
 * muzzle snaps, debris and the rushing-tunnel wind.
 *
 * @param dur buffer length in seconds. @return a one-channel white-noise buffer.
 */
private fun whiteNoise(ac: dynamic, dur: Double): dynamic {
    val sr: Double = ac.sampleRate
    val len = (sr * dur).toInt().coerceAtLeast(1)
    val buf = ac.createBuffer(1, len, sr)
    val d = buf.getChannelData(0)
    for (i in 0 until len) d[i] = Random.nextDouble() * 2.0 - 1.0
    return buf
}

/**
 * Build an [AudioBuffer] of brown (red) noise — integrated white noise, so energy is heavily
 * weighted to the low end. This is what gives an explosion its chest-thumping power instead of
 * a thin hiss.
 *
 * @param dur buffer length in seconds. @return a one-channel brown-noise buffer.
 */
private fun brownNoise(ac: dynamic, dur: Double): dynamic {
    val sr: Double = ac.sampleRate
    val len = (sr * dur).toInt().coerceAtLeast(1)
    val buf = ac.createBuffer(1, len, sr)
    val d = buf.getChannelData(0)
    var last = 0.0
    for (i in 0 until len) {
        val wn = Random.nextDouble() * 2.0 - 1.0
        last = (last + 0.02 * wn) / 1.02
        d[i] = last * 3.5 // compensate for the level drop from integration
    }
    return buf
}

/**
 * A `WaveShaperNode` configured for soft-clipping saturation. Higher [amount] = more drive /
 * grit, adding the aggressive "torn" edge a clean synth tone lacks (used by the phaser and
 * the explosion body/sub).
 *
 * @param amount drive amount (~0..1.5). @return a ready-to-insert waveshaper node.
 */
private fun saturator(ac: dynamic, amount: Double): dynamic {
    val ws = ac.createWaveShaper()
    val k = amount * 100.0
    val n = 1024
    val curve = Float32Array(n)
    for (i in 0 until n) {
        val x = (i * 2.0) / n - 1.0
        curve[i] = (((1.0 + k) * x) / (1.0 + k * abs(x))).toFloat()
    }
    ws.curve = curve
    ws.oversample = "4x"
    return ws
}

/**
 * One bright, saturated plasma zap — fired once per bolt volley by [tickPhaser] so the ~4 s
 * barrage reads as a rapid string of distinct shots. Deliberately shorter than the standalone
 * "heavy" phaser so overlapping volleys don't smear, with a small per-shot pitch jitter so
 * the barrage doesn't sound robotically identical.
 *
 * Chain: three detuned voices (two saws + a square an octave down) → saturator → resonant
 * lowpass (opens on the attack, closes on decay: the "vwooom") → amp env → out, plus a
 * feedback-delay ricochet tail and a sub thump for weight.
 *
 * @see tickPhaser
 */
fun playPhaserShot() {
    if (!spikeSoundEffects) return
    val ac = audioContext() ?: return
    val dst = masterOut() ?: return
    val t: Double = ac.currentTime
    val pitch = 820.0 * (0.9 + Random.nextDouble() * 0.2) // slight per-shot jitter
    val endHz = pitch * 0.35
    val dur = 0.28
    val sweepEnd = t + dur * 0.7

    val drive = saturator(ac, 0.85)
    val lp = ac.createBiquadFilter(); lp.type = "lowpass"
    lp.frequency.setValueAtTime(pitch * 6.0, t)
    lp.frequency.exponentialRampToValueAtTime(endHz * 3.0, t + dur)
    lp.Q.value = 9.0
    val g = ac.createGain()
    g.gain.setValueAtTime(0.0001, t)
    g.gain.exponentialRampToValueAtTime(0.9, t + 0.006)
    g.gain.setValueAtTime(0.9, t + dur * 0.3)
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur)
    drive.connect(lp); lp.connect(g); g.connect(dst)

    // ricochet tail
    val delay = ac.createDelay(); delay.delayTime.value = 0.08
    val fb = ac.createGain(); fb.gain.value = 0.28
    val fbLp = ac.createBiquadFilter(); fbLp.type = "lowpass"; fbLp.frequency.value = 2000.0
    g.connect(delay); delay.connect(fbLp); fbLp.connect(fb); fb.connect(delay); delay.connect(dst)

    // three detuned voices
    val voiceDetune = doubleArrayOf(-7.0, 7.0, 0.0)
    val voiceMult = doubleArrayOf(1.0, 1.0, 0.5)
    val voiceType = arrayOf("sawtooth", "sawtooth", "square")
    for (i in 0..2) {
        val o = ac.createOscillator()
        o.type = voiceType[i]; o.detune.value = voiceDetune[i]
        o.frequency.setValueAtTime(pitch * voiceMult[i], t)
        o.frequency.exponentialRampToValueAtTime(endHz * voiceMult[i], sweepEnd)
        val og = ac.createGain(); og.gain.value = 0.4
        o.connect(og); og.connect(drive)
        o.start(t); o.stop(t + dur + 0.05)
    }

    // sub thump
    val sub = ac.createOscillator(); sub.type = "sine"
    sub.frequency.setValueAtTime(pitch * 0.5, t)
    sub.frequency.exponentialRampToValueAtTime(endHz * 0.4, t + dur * 0.6)
    val sg = ac.createGain()
    sg.gain.setValueAtTime(0.5, t)
    sg.gain.exponentialRampToValueAtTime(0.0001, t + dur * 0.8)
    sub.connect(sg); sg.connect(dst)
    sub.start(t); sub.stop(t + dur + 0.05)

    // muzzle snap
    val n = ac.createBufferSource(); n.buffer = whiteNoise(ac, 0.06)
    val nf = ac.createBiquadFilter(); nf.type = "highpass"; nf.frequency.value = 1600.0
    val ng = ac.createGain()
    ng.gain.setValueAtTime(0.5, t)
    ng.gain.exponentialRampToValueAtTime(0.0001, t + 0.06)
    n.connect(nf); nf.connect(ng); ng.connect(dst)
    n.start(t); n.stop(t + 0.07)
}

/**
 * A powerful, cinematic explosion — fired by [spawnPaneExplosion] when a phasered pane blows
 * up. Four layers: an initial highpassed-noise **crack** (shockwave snap), a saturated
 * brown-noise **body**, a long roiling brown-noise **rumble** that decays smoothly to true
 * silence (filter closing the whole time so it rolls away like a real blast), and a detuned
 * **sub** with noisy vibrato for weight without a tonal "kick drum" pitch — plus scattered
 * debris crackle.
 *
 * @param durationSeconds total tail length; defaults to a full ~1.8 s cinematic decay.
 * @see spawnPaneExplosion
 */
fun playExplosion(durationSeconds: Double = 1.8) {
    if (!spikeSoundEffects) return
    val ac = audioContext() ?: return
    val dst = masterOut() ?: return
    val t: Double = ac.currentTime
    val dur = durationSeconds
    val bright = 1.0

    val drive = saturator(ac, 0.4 + 0.6 * 0.9)
    val bus = ac.createGain(); bus.gain.value = 0.9
    drive.connect(bus); bus.connect(dst)

    // 1. crack
    val crack = ac.createBufferSource(); crack.buffer = whiteNoise(ac, 0.08)
    val chp = ac.createBiquadFilter(); chp.type = "highpass"; chp.frequency.value = 900.0
    val cg = ac.createGain()
    cg.gain.setValueAtTime(1.4 * bright, t)
    cg.gain.exponentialRampToValueAtTime(0.0001, t + 0.09)
    crack.connect(chp); chp.connect(cg); cg.connect(dst)
    crack.start(t); crack.stop(t + 0.1)

    // 2. body
    val body = ac.createBufferSource(); body.buffer = brownNoise(ac, 0.6)
    val blp = ac.createBiquadFilter(); blp.type = "lowpass"
    blp.frequency.setValueAtTime(3200.0 * bright, t)
    blp.frequency.exponentialRampToValueAtTime(280.0, t + 0.35)
    blp.Q.value = 0.8
    val bg = ac.createGain()
    bg.gain.setValueAtTime(0.0001, t)
    bg.gain.exponentialRampToValueAtTime(1.6, t + 0.01)
    bg.gain.exponentialRampToValueAtTime(0.35, t + 0.28)
    body.connect(blp); blp.connect(bg); bg.connect(drive)
    body.start(t); body.stop(t + 0.7)

    // 3. rumble — long tail, smooth fade to real silence
    val rum = ac.createBufferSource(); rum.buffer = brownNoise(ac, dur + 0.3); rum.loop = true
    val rlp = ac.createBiquadFilter(); rlp.type = "lowpass"
    rlp.frequency.setValueAtTime(900.0 * bright, t)
    rlp.frequency.exponentialRampToValueAtTime(70.0, t + dur)
    rlp.Q.value = 0.7
    val rg = ac.createGain()
    rg.gain.setValueAtTime(0.0001, t)
    rg.gain.exponentialRampToValueAtTime(1.3, t + 0.04)
    rg.gain.exponentialRampToValueAtTime(0.05, t + dur * 0.92)
    rg.gain.linearRampToValueAtTime(0.0, t + dur)
    val lfo = ac.createOscillator(); lfo.type = "sine"; lfo.frequency.value = 9.0
    val lfoAmt = ac.createGain()
    lfoAmt.gain.setValueAtTime(0.3, t)
    lfoAmt.gain.linearRampToValueAtTime(0.0, t + dur * 0.8)
    lfo.connect(lfoAmt); lfoAmt.connect(rg.gain)
    lfo.start(t); lfo.stop(t + dur + 0.05)
    rum.connect(rlp); rlp.connect(rg); rg.connect(drive)
    rum.start(t); rum.stop(t + dur + 0.05)

    // 4. sub
    val subMult = doubleArrayOf(1.0, 1.51)
    for (i in 0..1) {
        val sub = ac.createOscillator()
        sub.type = if (i == 1) "triangle" else "sine"
        sub.frequency.setValueAtTime(90.0 * subMult[i], t)
        sub.frequency.exponentialRampToValueAtTime(24.0 * subMult[i], t + 0.5)
        val vib = ac.createBufferSource(); vib.buffer = whiteNoise(ac, 0.6)
        val vibAmt = ac.createGain(); vibAmt.gain.value = 12.0
        vib.connect(vibAmt); vibAmt.connect(sub.frequency); vib.start(t); vib.stop(t + 0.6)
        val sg = ac.createGain()
        val subEnd = t + minOf(dur, 0.9)
        sg.gain.setValueAtTime(0.9, t)
        sg.gain.exponentialRampToValueAtTime(0.0001, subEnd)
        sub.connect(sg); sg.connect(drive)
        sub.start(t); sub.stop(subEnd + 0.05)
    }

    // debris crackle
    val debrisCount = 8
    for (i in 0 until debrisCount) {
        val dt = t + 0.04 + Random.nextDouble() * dur * 0.75
        val cn = ac.createBufferSource(); cn.buffer = whiteNoise(ac, 0.04)
        val cf = ac.createBiquadFilter(); cf.type = "bandpass"
        cf.frequency.value = 500.0 + Random.nextDouble() * 2600.0; cf.Q.value = 4.0
        val dg = ac.createGain()
        dg.gain.setValueAtTime(0.22, dt)
        dg.gain.exponentialRampToValueAtTime(0.0001, dt + 0.07)
        cn.connect(cf); cf.connect(dg); dg.connect(dst)
        cn.start(dt); cn.stop(dt + 0.08)
    }
}

/**
 * A wormhole tearing open — fired by [tickWormhole] as the funnel spirals open. Three layers:
 * an instant rising-noise **tear** (audible from the very first sample so there's no dead
 * lead-in), detuned inharmonic **bloom** partials that rise then hold the vortex's hum open,
 * and a high **sparkle** ping-swarm for otherworldly shimmer. The amp env reaches audible
 * level in ~40 ms and sustains through the middle before a gentle tail.
 *
 * @param durationSeconds how long the open sound lasts; the caller matches it to the funnel's
 *   open-leg duration. Defaults to ~2.3 s.
 * @see tickWormhole
 */
fun playWormholeAppear(durationSeconds: Double = 2.3) {
    if (!spikeSoundEffects) return
    val ac = audioContext() ?: return
    val dst = masterOut() ?: return
    val t: Double = ac.currentTime
    val dur = durationSeconds
    val bright = 1.0
    val open = t + dur * 0.35
    val fade = t + dur * 0.7

    // Boost bus — the whole effect is lifted well above unity so a new pane's arrival lands
    // loud (it was too quiet next to the explosion); every layer routes through here.
    val bus = ac.createGain(); bus.gain.value = 2.1
    bus.connect(dst)

    val g = ac.createGain()
    g.gain.setValueAtTime(0.0001, t)
    g.gain.exponentialRampToValueAtTime(0.95, t + 0.04)
    g.gain.setValueAtTime(0.95, fade)
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur)
    val lp = ac.createBiquadFilter(); lp.type = "lowpass"
    lp.frequency.setValueAtTime(700.0, t)
    lp.frequency.exponentialRampToValueAtTime(6500.0 * bright, open)
    lp.frequency.setValueAtTime(6500.0 * bright, fade)
    lp.Q.value = 6.0
    lp.connect(g); g.connect(bus)

    // 1. tear
    val tear = ac.createBufferSource(); tear.buffer = whiteNoise(ac, minOf(dur, 1.2) + 0.1)
    val tf = ac.createBiquadFilter(); tf.type = "bandpass"; tf.Q.value = 3.0
    tf.frequency.setValueAtTime(300.0, t)
    tf.frequency.exponentialRampToValueAtTime(4000.0, open)
    val tg = ac.createGain()
    tg.gain.setValueAtTime(0.6, t)
    tg.gain.exponentialRampToValueAtTime(0.18, open)
    tg.gain.exponentialRampToValueAtTime(0.0001, t + dur)
    tear.connect(tf); tf.connect(tg); tg.connect(bus)
    tear.start(t); tear.stop(t + dur + 0.1)

    // 2. bloom
    val base = 110.0
    val partials = doubleArrayOf(1.0, 1.5, 2.01, 3.0, 4.02, 5.4)
    for (i in partials.indices) {
        val mult = partials[i]
        val o = ac.createOscillator()
        o.type = if (i % 2 == 1) "triangle" else "sawtooth"
        o.frequency.setValueAtTime(base * mult * 0.6, t)
        o.frequency.exponentialRampToValueAtTime(base * mult, open)
        o.detune.value = ((i * 53) % 30 - 15).toDouble()
        val og = ac.createGain(); og.gain.value = 0.5 / partials.size
        o.connect(og); og.connect(lp)
        o.start(t); o.stop(t + dur + 0.05)
    }

    // 3. sparkle
    for (i in 0 until 5) {
        val st = t + (i / 5.0) * dur * 0.5
        val s = ac.createOscillator(); s.type = "sine"
        val f = 1200.0 + i * 700.0 * bright
        s.frequency.setValueAtTime(f, st)
        s.frequency.exponentialRampToValueAtTime(f * 1.4, st + 0.3)
        val sg = ac.createGain()
        sg.gain.setValueAtTime(0.0001, st)
        sg.gain.exponentialRampToValueAtTime(0.12, st + 0.02)
        sg.gain.exponentialRampToValueAtTime(0.0001, st + 0.5)
        s.connect(sg); sg.connect(bus)
        s.start(st); s.stop(st + 0.55)
    }
}

/**
 * A terminal arriving out of the vortex — fired by [tickWormhole] as the pane begins to
 * emerge. A single rising warp-in swoosh (a sawtooth swept up through an opening lowpass),
 * deliberately clean with no landing chime.
 *
 * @param durationSeconds swoosh length; defaults to ~0.75 s. @see tickWormhole
 */
fun playTerminalMaterialize(durationSeconds: Double = 0.75) {
    if (!spikeSoundEffects) return
    val ac = audioContext() ?: return
    val dst = masterOut() ?: return
    val t: Double = ac.currentTime
    val dur = durationSeconds

    val o = ac.createOscillator(); o.type = "sawtooth"
    o.frequency.setValueAtTime(120.0, t)
    o.frequency.exponentialRampToValueAtTime(900.0, t + dur * 0.6)
    val lp = ac.createBiquadFilter(); lp.type = "lowpass"
    lp.frequency.setValueAtTime(400.0, t)
    lp.frequency.exponentialRampToValueAtTime(5000.0, t + dur * 0.6)
    lp.Q.value = 6.0
    val g = ac.createGain()
    g.gain.setValueAtTime(0.0001, t)
    g.gain.exponentialRampToValueAtTime(0.5, t + dur * 0.5)
    g.gain.exponentialRampToValueAtTime(0.001, t + dur * 0.62)
    o.connect(lp); lp.connect(g); g.connect(dst)
    o.start(t); o.stop(t + dur * 0.65)
}

/**
 * Riding the wormhole tunnel to another world — fired by [enterOrExitOtherWorld] and held for
 * the whole transit. A looping-noise **rushing wind** through an LFO-wobbled bandpass (the
 * walls flying past) over a **dark drone** that bends with the motion and sustains the full
 * trip, fading only at the exit. The caller passes the real transit duration so the sound
 * lasts exactly as long as the journey rather than cutting out early.
 *
 * @param durationSeconds the transit length in seconds (≈ 10 s for the four-leg world switch).
 * @see enterOrExitOtherWorld
 */
fun playWormholeTravel(durationSeconds: Double) {
    if (!spikeSoundEffects) return
    val ac = audioContext() ?: return
    val dst = masterOut() ?: return
    val t: Double = ac.currentTime
    val dur = durationSeconds
    val bright = 1.0
    val rush = 1.0
    val pitch = 1.0

    // rushing wind
    val n = ac.createBufferSource(); n.buffer = whiteNoise(ac, 1.0); n.loop = true
    val bp = ac.createBiquadFilter(); bp.type = "bandpass"; bp.Q.value = 3.5
    bp.frequency.setValueAtTime(200.0, t)
    bp.frequency.exponentialRampToValueAtTime(2600.0 * bright, t + dur * 0.5)
    bp.frequency.exponentialRampToValueAtTime(180.0, t + dur)
    val ng = ac.createGain()
    ng.gain.setValueAtTime(0.0001, t)
    // Fast attack (capped ~0.7 s) so the wind is full by the time we punch into the tube, rather
    // than slowly ramping up a good while after entry on a long transit.
    ng.gain.exponentialRampToValueAtTime(0.6, t + minOf(dur * 0.25, 0.7))
    ng.gain.setValueAtTime(0.6, t + dur * 0.72)
    ng.gain.exponentialRampToValueAtTime(0.0001, t + dur)
    val lfo = ac.createOscillator(); lfo.type = "sine"; lfo.frequency.value = 7.0 * rush
    val lfoGain = ac.createGain(); lfoGain.gain.value = 600.0
    lfo.connect(lfoGain); lfoGain.connect(bp.frequency)
    lfo.start(t); lfo.stop(t + dur + 0.05)
    n.connect(bp); bp.connect(ng); ng.connect(dst)
    n.start(t); n.stop(t + dur + 0.05)

    // dark drone — sustained across the whole trip, fades only at the exit
    val d = ac.createOscillator(); d.type = "sawtooth"
    d.frequency.setValueAtTime(55.0 * pitch, t)
    d.frequency.linearRampToValueAtTime(38.0 * pitch, t + dur * 0.5)
    d.frequency.linearRampToValueAtTime(70.0 * pitch, t + dur)
    val dlp = ac.createBiquadFilter(); dlp.type = "lowpass"; dlp.frequency.value = 400.0
    val dg = ac.createGain()
    dg.gain.setValueAtTime(0.0001, t)
    dg.gain.exponentialRampToValueAtTime(0.55, t + minOf(dur * 0.2, 0.6))
    dg.gain.setValueAtTime(0.55, t + dur * 0.82)
    dg.gain.exponentialRampToValueAtTime(0.0001, t + dur)
    d.connect(dlp); dlp.connect(dg); dg.connect(dst)
    d.start(t); d.stop(t + dur + 0.05)
}
