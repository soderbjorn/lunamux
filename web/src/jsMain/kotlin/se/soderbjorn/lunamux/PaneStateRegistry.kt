/**
 * Per-pane caches: file-browser and git backing ViewModels (with their
 * collector jobs), live DOM-handle records used by the renderer, and the
 * "previous maximized" map that lets [buildPane] animate maximize/restore
 * across full-config re-renders.
 *
 * Lives next to [WebState]; same-package unqualified references continue
 * to work.
 *
 * @see FileBrowserBackingViewModel
 * @see GitPaneBackingViewModel
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.Job
import se.soderbjorn.lunamux.client.viewmodel.FileBrowserBackingViewModel
import se.soderbjorn.lunamux.client.viewmodel.GitPaneBackingViewModel

/** Backing VM + collector job per file-browser pane, refilled by `renderConfig`. */
internal val fileBrowserVms = HashMap<String, Pair<FileBrowserBackingViewModel, Job>>()

/** Backing VM + collector job per git pane. */
internal val gitPaneVms = HashMap<String, Pair<GitPaneBackingViewModel, Job>>()

/**
 * Per-pane maximized state captured just before `renderConfig` wipes the
 * DOM, so [buildPane] can rebuild the pane in its previous class state
 * first and then `requestAnimationFrame`-flip to the new state, restoring
 * the floating-pane CSS transition across re-renders.
 */
internal val previousMaximizedStates = HashMap<String, Boolean>()

/** Pane ids that existed before the latest re-render, for animation reuse. */
internal val previousPaneIds = HashSet<String>()

/** File-browser per-pane state (selection, expanded dirs, font size). */
internal val fileBrowserPaneStates = HashMap<String, FileBrowserPaneState>()

/** File-browser DOM handles per pane (left list root, right content root). */
internal val fileBrowserPaneViews = HashMap<String, FileBrowserPaneView>()

/** Git per-pane state (selection, diff mode, font size, …). */
internal val gitPaneStates = HashMap<String, GitPaneState>()

/**
 * Optional listener notified with a `paneId` whenever that pane's
 * file-browser directory listing or git file list is refreshed in
 * [handlePaneContentMessage]. The 3D overview ([Overview3D]) installs this
 * while open so it can paint / repaint non-terminal pane thumbnails as their
 * data arrives (file-browser and git panes have no PTY transcript to mirror).
 * `null` when the overview is closed, so there is no cost in the common case.
 */
internal var onPaneContentUpdated: ((String) -> Unit)? = null

/** Git DOM handles per pane (file list, diff pane, search bar). */
internal val gitPaneViews = HashMap<String, GitPaneView>()
