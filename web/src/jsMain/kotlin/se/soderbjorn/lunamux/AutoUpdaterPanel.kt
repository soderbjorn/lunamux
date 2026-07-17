/**
 * AutoUpdaterPanel.kt
 * -------------------
 * Renderer half of the Lunamux desktop auto-updater (Electron only).
 *
 * electron-updater runs in the Electron main process (see the electron-main
 * module's AutoUpdater.kt) and forwards each lifecycle event to the renderer over
 * the preload bridge (`window.electronApi.onUpdate*`). This file keeps a small
 * [UpdaterUiState] state machine fed by those events and renders it as a compact
 * banner pinned to the TOP of the left sidebar footer (mounted from
 * `LunamuxToolkitBootstrap.buildSidebarFooter`, styled by `.lunamux-update-banner`
 * in styles.css) — modeled on acolite's side-menu auto-updater: hidden while idle,
 * appearing with an accent title + version, a "What's new" link (opens the GitHub
 * release page for the new version), a progress bar, and a Download / Restart
 * action.
 *
 * The initial (silent) check is fired by the renderer itself in
 * [initAutoUpdaterListeners], *after* it has subscribed, so the main process can
 * never emit `update-available` before the renderer is listening
 * (`webContents.send` does not buffer). The same function arms a slow periodic
 * re-check ([UPDATE_RECHECK_INTERVAL_MS]) so a long-running app still learns of
 * new releases without a relaunch. [triggerUserUpdateCheck] backs the
 * "Check for Updates…" Help-menu item.
 *
 * All entry points are safe outside Electron: they no-op when the `electronApi`
 * bridge (or its updater methods) is absent, and the banner is only mounted for
 * [isElectronClient].
 *
 * @see initAutoUpdaterListeners
 * @see buildUpdateBanner
 * @see triggerUserUpdateCheck
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import kotlin.math.roundToInt
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * The phases of an update, mirroring electron-updater's lifecycle events. Drives
 * the banner's title, visibility, and which action its button performs.
 */
private enum class UpdaterStatus {
    /** No check has surfaced anything this session; the banner is hidden. */
    IDLE,

    /** A user-initiated check is in flight. */
    CHECKING,

    /** A newer version exists and can be downloaded. */
    AVAILABLE,

    /** The update is downloading. */
    DOWNLOADING,

    /** The update finished downloading and can be installed. */
    DOWNLOADED,

    /** The last check found the app already up to date (banner hidden). */
    NOT_AVAILABLE,

    /** The last user-initiated operation failed. */
    ERROR,
}

/**
 * Immutable snapshot of the updater UI.
 *
 * @property status the current lifecycle phase.
 * @property version the target version, when known (available/downloaded).
 * @property percent download progress 0..100, when downloading.
 * @property message an error message, when [status] is [UpdaterStatus.ERROR].
 * @property releaseNotesUrl the new version's GitHub release page, when known;
 *   opened by the banner's "What's new" link. Built main-side in AutoUpdater.kt.
 */
private data class UpdaterUiState(
    val status: UpdaterStatus = UpdaterStatus.IDLE,
    val version: String? = null,
    val percent: Double? = null,
    val message: String? = null,
    val releaseNotesUrl: String? = null,
)

/**
 * How often the silent periodic re-check runs (4 hours). Lunamux is a terminal
 * app that stays open for days or weeks, so the single launch check alone would
 * leave long-running sessions permanently unaware of new releases. Silent like
 * the launch check: only an actionable outcome (update available) surfaces the
 * banner. See the interval armed in [initAutoUpdaterListeners].
 */
private const val UPDATE_RECHECK_INTERVAL_MS = 4 * 60 * 60 * 1000

/** The live updater state, updated by the events wired in [initAutoUpdaterListeners]. */
private var updaterState = UpdaterUiState()

/**
 * The mounted banner's re-render callback, or `null` before the sidebar footer is
 * built. Set once by [buildUpdateBanner]; the footer element is cached by the
 * toolkit bootstrap, so the banner (and this callback) survive toolkit rerenders.
 */
private var bannerRender: ((UpdaterUiState) -> Unit)? = null

/** Guards one-time event subscription in [initAutoUpdaterListeners]. */
private var listenersInitialized = false

/**
 * Whether the current (or most recent) update lifecycle was started by an explicit
 * user gesture — the banner's button or the "Check for Updates…" menu — as opposed
 * to the silent check at launch. [setUpdaterState] suppresses the non-actionable
 * phases (checking / up-to-date / error) unless this is set, so a silent startup
 * check never surfaces the banner for a no-op or a transient offline error.
 * Available/downloading/downloaded are always surfaced regardless.
 */
private var userInitiatedAction = false

/**
 * Subscribe once to the main process's update lifecycle events, then kick off the
 * initial silent check.
 *
 * Called from `main.kt`'s startup inside the `isElectronClient` branch. The check
 * is fired here — *after* the `onUpdate*` subscriptions are registered — so the
 * main process cannot emit `update-available` before the renderer is listening
 * (that race is why an available update previously never showed on launch). No-ops
 * when the `electronApi` bridge or its updater methods are absent.
 */
fun initAutoUpdaterListeners() {
    if (listenersInitialized) return
    val api = window.asDynamic().electronApi ?: return
    if (api.onUpdateAvailable == null) return
    listenersInitialized = true

    api.onUpdateChecking({ setUpdaterState(UpdaterUiState(UpdaterStatus.CHECKING)) })
    api.onUpdateAvailable({ info: dynamic ->
        setUpdaterState(UpdaterUiState(
            UpdaterStatus.AVAILABLE,
            version = info?.version as? String,
            releaseNotesUrl = info?.releaseNotesUrl as? String,
        ))
    })
    api.onUpdateNotAvailable({ setUpdaterState(UpdaterUiState(UpdaterStatus.NOT_AVAILABLE)) })
    api.onUpdateProgress({ p: dynamic ->
        setUpdaterState(UpdaterUiState(UpdaterStatus.DOWNLOADING, percent = (p?.percent as? Number)?.toDouble()))
    })
    api.onUpdateDownloaded({ info: dynamic ->
        setUpdaterState(UpdaterUiState(
            UpdaterStatus.DOWNLOADED,
            version = info?.version as? String,
            releaseNotesUrl = info?.releaseNotesUrl as? String,
        ))
    })
    api.onUpdateError({ err: dynamic ->
        // If this error follows a "Restart to install" click, the app is still
        // running after we suppressed the "Connection lost" modal in
        // anticipation of the teardown — re-arm it and re-evaluate, so a server
        // that WAS shut down before the install failed surfaces as disconnected
        // instead of dying silently. No-op when nothing was suppressed.
        if (suppressDisconnectedModal) {
            suppressDisconnectedModal = false
            updateAggregateStatus()
        }
        setUpdaterState(UpdaterUiState(UpdaterStatus.ERROR, message = err?.message as? String))
    })

    // Silent initial check, fired only now that we're subscribed. Not user-initiated,
    // so a "nothing new" / offline result leaves the banner hidden.
    if (api.checkForUpdates != null) api.checkForUpdates()

    // Slow periodic re-check (silent, like the launch check) so an app left
    // open for weeks still learns of new releases. Skipped while an update flow
    // is in progress: re-checking then would re-emit update-available and
    // downgrade a DOWNLOADED banner back to "Download".
    window.setInterval({
        val s = updaterState.status
        val busy = s == UpdaterStatus.CHECKING ||
            s == UpdaterStatus.DOWNLOADING ||
            s == UpdaterStatus.DOWNLOADED
        if (!busy && api.checkForUpdates != null) api.checkForUpdates()
    }, UPDATE_RECHECK_INTERVAL_MS)
}

/**
 * Commit a new [UpdaterUiState] and re-render the banner.
 *
 * A silent (startup) check must not surface the banner for a non-actionable
 * outcome, so checking / up-to-date / error are dropped unless the user asked
 * ([userInitiatedAction]); available/downloading/downloaded are always applied.
 *
 * @param next the state to apply.
 */
private fun setUpdaterState(next: UpdaterUiState) {
    when (next.status) {
        UpdaterStatus.CHECKING -> if (!userInitiatedAction) return
        UpdaterStatus.NOT_AVAILABLE, UpdaterStatus.ERROR -> {
            if (!userInitiatedAction) return
            userInitiatedAction = false // terminal outcome of the user's action
        }
        else -> {}
    }
    updaterState = next
    bannerRender?.invoke(next)
}

/**
 * Run a user-initiated update check (marks the action so its checking / up-to-date
 * / error outcome shows in the banner, then asks the main process to check).
 * Backs the "Check for Updates…" Help-menu item (routed via `main.kt`). No-op-safe
 * outside Electron.
 */
fun triggerUserUpdateCheck() {
    val api = window.asDynamic().electronApi ?: return
    userInitiatedAction = true
    if (api.checkForUpdates != null) api.checkForUpdates()
}

/**
 * Build the update banner for the sidebar footer.
 *
 * Compact and acolite-style, laid out as two short rows so nothing truncates:
 *  - title row: an uppercase accent title + a right-aligned version (or, while
 *    downloading, the percent);
 *  - action row: a "What's new" link (opens the GitHub release page) or the error
 *    message, plus a Download / Restart / Try again button — the button fills the
 *    row when there's no left cell (once downloaded, or when no release URL);
 *  - a full-width progress bar while downloading.
 * Hidden entirely while idle / up-to-date. Registers itself as the live
 * [bannerRender] target and renders the current [updaterState] immediately.
 *
 * Mounted once by `LunamuxToolkitBootstrap.buildSidebarFooter` (gated on
 * [isElectronClient]); the footer is cached, so this is built a single time.
 * Styled by `.lunamux-update-banner` in styles.css (theme-token colours).
 *
 * @return the banner element (initially hidden via its CSS `display:none`).
 */
fun buildUpdateBanner(): HTMLElement {
    val banner = document.createElement("div") as HTMLElement
    banner.className = "lunamux-update-banner"

    // Title row: title (left) + version / percent chip (right).
    val titleRow = document.createElement("div") as HTMLElement
    titleRow.className = "lu-update-row"
    val titleEl = document.createElement("span") as HTMLElement
    titleEl.className = "lu-update-title"
    val versionEl = document.createElement("span") as HTMLElement
    versionEl.className = "lu-update-version"
    titleRow.appendChild(titleEl)
    titleRow.appendChild(versionEl)
    banner.appendChild(titleRow)

    // Action row: "What's new" link (or the error message) + the primary action.
    val bodyRow = document.createElement("div") as HTMLElement
    bodyRow.className = "lu-update-row"
    val leftEl = document.createElement("span") as HTMLElement
    leftEl.className = "lu-update-left"
    // "What's new" opens the new version's GitHub release page in the browser.
    // Captured by both this handler (reads) and render (writes).
    var currentNotesUrl: String? = null
    leftEl.addEventListener("click", { _: Event -> currentNotesUrl?.let { openExternalUrl(it) } })
    bodyRow.appendChild(leftEl)

    val actionBtn = document.createElement("button") as HTMLElement
    (actionBtn.asDynamic()).type = "button"
    actionBtn.className = "lu-update-action"
    // One handler, dispatching on the live status at click time. Guards each
    // method (older preload may lack them), and optimistically advances the UI so
    // the click feels instant rather than waiting on the first event.
    actionBtn.addEventListener("click", { _: Event ->
        val api = window.asDynamic().electronApi ?: return@addEventListener
        userInitiatedAction = true
        when (updaterState.status) {
            UpdaterStatus.AVAILABLE -> {
                if (api.downloadUpdate != null) api.downloadUpdate()
                setUpdaterState(UpdaterUiState(UpdaterStatus.DOWNLOADING, percent = 0.0))
            }
            UpdaterStatus.DOWNLOADED -> {
                // The install shuts the server down first (up to a few seconds)
                // before the app quits, so give immediate feedback, not a dead button.
                actionBtn.textContent = "Restarting…"
                (actionBtn.asDynamic()).disabled = true
                // The server is about to be stopped on purpose; don't flash the
                // "Connection lost" modal in the moment before the app exits.
                suppressDisconnectedModal = true
                hideDisconnectedModal()
                if (api.quitAndInstall != null) api.quitAndInstall()
            }
            else -> {
                if (api.checkForUpdates != null) api.checkForUpdates()
                setUpdaterState(UpdaterUiState(UpdaterStatus.CHECKING))
            }
        }
        Unit
    })
    bodyRow.appendChild(actionBtn)
    banner.appendChild(bodyRow)

    // Progress row (shown only while downloading): a full-width bar.
    val progressRow = document.createElement("div") as HTMLElement
    progressRow.className = "lu-update-progress-row"
    val track = document.createElement("div") as HTMLElement
    track.className = "lu-update-progress"
    val fill = document.createElement("div") as HTMLElement
    fill.className = "lu-update-progress-fill"
    fill.style.setProperty("width", "0%")
    track.appendChild(fill)
    progressRow.appendChild(track)
    banner.appendChild(progressRow)

    /** Reflect [s] onto the banner: visibility, title/version, link/message, progress, button. */
    fun render(s: UpdaterUiState) {
        val visible = s.status != UpdaterStatus.IDLE && s.status != UpdaterStatus.NOT_AVAILABLE
        banner.style.setProperty("display", if (visible) "flex" else "none")
        if (!visible) return
        currentNotesUrl = s.releaseNotesUrl
        // The "Restart to install" click disables the button optimistically
        // (the pre-install server shutdown takes a few seconds). Every rendered
        // state re-enables it, so a failed install's ERROR → "Try again" is
        // actually clickable instead of inheriting the dead button.
        (actionBtn.asDynamic()).disabled = false

        val showBody = s.status == UpdaterStatus.AVAILABLE ||
            s.status == UpdaterStatus.DOWNLOADED ||
            s.status == UpdaterStatus.ERROR
        bodyRow.style.setProperty("display", if (showBody) "flex" else "none")
        progressRow.style.setProperty("display", if (s.status == UpdaterStatus.DOWNLOADING) "flex" else "none")

        // "What's new" shows only when available with a release URL; the error
        // message uses the same cell. Once downloaded (or when there's no URL) the
        // button fills the row — installing/downloading is the only action left.
        val hasNotes = !s.releaseNotesUrl.isNullOrBlank()
        val showLeft = (s.status == UpdaterStatus.AVAILABLE && hasNotes) || s.status == UpdaterStatus.ERROR
        val fullButton = s.status == UpdaterStatus.DOWNLOADED || (s.status == UpdaterStatus.AVAILABLE && !hasNotes)
        leftEl.style.setProperty("display", if (showLeft) "" else "none")
        actionBtn.classList.toggle("lu-update-action-full", fullButton)

        when (s.status) {
            UpdaterStatus.CHECKING -> {
                titleEl.textContent = "Checking for updates"
                versionEl.textContent = ""
            }
            UpdaterStatus.AVAILABLE -> {
                titleEl.textContent = "Update available"
                versionEl.textContent = s.version?.let { "v$it" } ?: ""
                leftEl.className = "lu-update-left lu-update-link"
                leftEl.textContent = "What's new"
                actionBtn.textContent = "Download"
            }
            UpdaterStatus.DOWNLOADING -> {
                titleEl.textContent = "Downloading"
                val pct = s.percent?.roundToInt() ?: 0
                versionEl.textContent = "$pct%"
                fill.style.setProperty("width", "$pct%")
            }
            UpdaterStatus.DOWNLOADED -> {
                titleEl.textContent = "Update ready"
                versionEl.textContent = s.version?.let { "v$it" } ?: ""
                actionBtn.textContent = "Restart to install"
            }
            UpdaterStatus.ERROR -> {
                titleEl.textContent = "Update failed"
                versionEl.textContent = ""
                leftEl.className = "lu-update-left"
                leftEl.textContent = s.message ?: "Something went wrong"
                actionBtn.textContent = "Try again"
            }
            // IDLE / NOT_AVAILABLE can't reach here: the visibility early-return
            // above filters them out, which the compiler tracks through `visible`
            // (so an `else` branch would be flagged as redundant).
            UpdaterStatus.IDLE, UpdaterStatus.NOT_AVAILABLE -> {}
        }
    }

    bannerRender = ::render
    render(updaterState)
    return banner
}
