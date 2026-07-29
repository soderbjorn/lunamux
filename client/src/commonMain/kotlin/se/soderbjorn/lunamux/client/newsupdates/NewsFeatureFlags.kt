/**
 * Ship-time feature flags for the "News & Updates" UI, defined once in the shared
 * `client` module so all three platforms gate the same behaviour off one switch.
 *
 * Unlike the developer-only toggles in
 * [se.soderbjorn.lunamux.client.UpdateNewsDebug] (which default to `false` and must
 * never be committed as `true`), the flags here describe features that ship
 * **enabled** and exist only so a follow-up build can turn them off without
 * ripping the code out — flip the constant to `false` and every platform hides the
 * gated affordance.
 *
 * Consumed in exactly one place — [NewsUpdatesBackingViewModel.State.checkNowAvailable] —
 * so the gating decision is made once in the shared view-model and every platform
 * (Android Compose, iOS SwiftUI, desktop/web) merely projects the resulting
 * [NewsUpdatesBackingViewModel.State] rather than reading this flag itself.
 *
 * @see se.soderbjorn.lunamux.client.newsupdates.NewsUpdatesBackingViewModel.State.checkNowAvailable
 */
package se.soderbjorn.lunamux.client.newsupdates

/**
 * When `true`, the "News & Updates" screen shows a "Check now" button — alongside
 * the Restore action — that triggers an out-of-band
 * [NewsUpdatesBackingViewModel.checkNow] instead of waiting for the once-per-day
 * periodic loop.
 *
 * Read by each platform's news screen (Android `NewsUpdatesScreen`, iOS
 * `NewsUpdatesView`, desktop `showNewsDialog`) to decide whether to render the
 * button. Defaults to `true` (button shown); flip to `false` to hide it on every
 * platform in a later build.
 */
const val CHECK_NOW_BUTTON_ENABLED: Boolean = true
