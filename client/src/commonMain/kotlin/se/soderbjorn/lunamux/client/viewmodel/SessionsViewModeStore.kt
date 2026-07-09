/**
 * Process-lifetime, in-memory store for the Sessions screen's view-mode toggle.
 *
 * The Sessions screen on mobile (Android `TreeScreen` / iOS `TreeView`) can show
 * its tabs and panes either as a flat list or as the miniaturised "overview"
 * (thumbnails of the windows/panes). The user's choice of mode used to live in
 * per-screen view state ([androidx.compose.runtime.saveable.rememberSaveable] on
 * Android, `@State` on iOS), so it reset every time the user drilled into a pane
 * and navigated back to the Sessions screen.
 *
 * This singleton holds the toggle as a single boolean ([overviewMode]) for the
 * whole process so the choice survives navigation away from and back to the
 * Sessions screen. It is **deliberately memory-only**: it is not persisted to
 * disk (no [se.soderbjorn.lunamux.client.LocalStore] round-trip), so it
 * resets to the default (overview mode) on the next app launch, exactly as
 * requested in issue #54.
 *
 * Both the Compose and SwiftUI front-ends read [overviewMode] when their
 * Sessions screen first appears and write it back whenever the user flips the
 * toolbar toggle. Each platform keeps its own `SessionsViewMode` enum for
 * rendering; this store only carries the underlying boolean so the two stay in
 * sync through the shared layer.
 *
 * @see se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel
 */
package se.soderbjorn.lunamux.client.viewmodel

/**
 * In-memory holder for the Sessions screen's list-vs-overview toggle.
 *
 * A plain `object` (process-wide singleton). Read/written from the Android
 * `TreeScreen` composable and the iOS `TreeViewModel`/`TreeView`; both treat
 * `true` as "overview mode" and `false` as "flat list".
 *
 * Single-threaded UI access is expected (mutations happen on the platform main
 * thread in response to a toolbar tap), so no extra synchronisation is applied.
 */
object SessionsViewModeStore {
    /**
     * Whether the Sessions screen should currently render the miniaturised
     * overview (`true`) instead of the flat session list (`false`).
     *
     * Defaults to `true` (overview mode) on a fresh process. Set by the
     * platform UI when the user flips the toolbar toggle and read back when the
     * Sessions screen re-appears, so the mode persists across navigation but
     * not across app restarts.
     */
    var overviewMode: Boolean = true

    /**
     * Whether the Sessions screen should currently reveal the tabs the user
     * hid from the sidebar (`true`) or omit them entirely (`false`).
     *
     * On mobile, tabs flagged
     * [se.soderbjorn.lunamux.TabConfig.isHiddenFromSidebar] used to always be
     * rendered under a small "Hidden" headline. Per issue #52 they are now
     * excluded from the list by default; the user opts in via a "Show hidden
     * tabs" affordance, after which they can re-hide them again.
     *
     * Defaults to `false` (hidden tabs omitted) on a fresh process. Set by the
     * platform UI when the user taps the show/hide affordance and read back when
     * the Sessions screen re-appears, so the choice persists across navigation
     * but **not** across app restarts — deliberately memory-only, like
     * [overviewMode] (no [se.soderbjorn.lunamux.client.LocalStore]
     * round-trip).
     */
    var showHiddenTabs: Boolean = false
}
