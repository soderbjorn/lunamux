/**
 * Backing ViewModel for a single git pane, showing changed files and diffs.
 *
 * Manages the file list, selected-file diff hunks, diff display mode
 * (inline/side-by-side/graphical), search state, and section collapse state.
 * All mutations are sent to the server via [WindowSocket]; authoritative
 * state is received back as [WindowEnvelope.GitList], [WindowEnvelope.GitDiffResult],
 * and [WindowEnvelope.GitError] replies.
 *
 * @see se.soderbjorn.termtastic.client.WindowSocket.gitList
 * @see se.soderbjorn.termtastic.client.WindowSocket.gitDiff
 */
package se.soderbjorn.termtastic.client.viewmodel

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.DiffHunk
import se.soderbjorn.termtastic.GitContent
import se.soderbjorn.termtastic.GitDiffMode
import se.soderbjorn.termtastic.GitFileEntry
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowEnvelope
import se.soderbjorn.termtastic.client.WindowSocket

/**
 * ViewModel for a git pane identified by [paneId].
 *
 * @param paneId       the unique ID of the git leaf node.
 * @param windowSocket the live `/window` WebSocket for commands and replies.
 */
class GitPaneBackingViewModel(
    private val paneId: String,
    private val windowSocket: WindowSocket,
) {
    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * Immutable snapshot of the git pane UI state.
     *
     * @property entries            list of changed files, or `null` before first load.
     * @property selectedFilePath   the currently selected file, or `null`.
     * @property diffHunks          parsed diff hunks for the selected file.
     * @property diffLanguage       detected language for syntax highlighting.
     * @property oldContent         full "before" content for side-by-side view.
     * @property newContent         full "after" content for side-by-side view.
     * @property diffMode           inline vs. side-by-side diff display.
     * @property graphicalDiff      whether to show graphical (word-level) diff.
     * @property autoRefresh        whether to auto-refresh the file list on changes.
     * @property leftColumnWidthPx  persisted width of the file-list column.
     * @property collapsedSections  set of diff section keys that are collapsed.
     * @property searchQuery        current in-diff search query.
     * @property searchMatchIndex   index of the highlighted search match.
     * @property isLoading          `true` while a server request is in flight.
     * @property errorMessage       error text from the server, or `null`.
     */
    data class State(
        val entries: List<GitFileEntry>? = null,
        val selectedFilePath: String? = null,
        val diffHunks: List<DiffHunk>? = null,
        val diffLanguage: String? = null,
        val oldContent: String? = null,
        val newContent: String? = null,
        val diffMode: GitDiffMode = GitDiffMode.Inline,
        val graphicalDiff: Boolean = false,
        val autoRefresh: Boolean = false,
        val leftColumnWidthPx: Int? = null,
        val collapsedSections: Set<String> = emptySet(),
        val searchQuery: String = "",
        val searchMatchIndex: Int = 0,
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
    )

    /**
     * Start collecting envelopes and config updates for this pane. Triggers
     * the initial git file-list load. Long-running -- cancels when the
     * enclosing scope is cancelled.
     */
    suspend fun run() {
        coroutineScope {
            launch {
                windowSocket.envelopes
                    .collect { envelope ->
                        if (!envelope.belongsToPane(paneId)) return@collect
                        when (envelope) {
                            is WindowEnvelope.GitList -> {
                                emit(_stateFlow.value.copy(
                                    entries = envelope.entries,
                                    isLoading = false,
                                    errorMessage = null,
                                ))
                            }
                            is WindowEnvelope.GitDiffResult -> {
                                emit(_stateFlow.value.copy(
                                    selectedFilePath = envelope.filePath,
                                    diffHunks = envelope.hunks,
                                    diffLanguage = envelope.language,
                                    oldContent = envelope.oldContent,
                                    newContent = envelope.newContent,
                                    isLoading = false,
                                    errorMessage = null,
                                ))
                            }
                            is WindowEnvelope.GitError -> {
                                emit(_stateFlow.value.copy(
                                    errorMessage = envelope.message,
                                    isLoading = false,
                                ))
                            }
                            else -> Unit
                        }
                    }
            }
            launch {
                windowSocket.config
                    .filterNotNull()
                    .collect { config ->
                        val leaf = findLeafById(config, paneId) ?: return@collect
                        val content = leaf.content as? GitContent ?: return@collect
                        val cur = _stateFlow.value
                        emit(cur.copy(
                            selectedFilePath = content.selectedFilePath ?: cur.selectedFilePath,
                            diffMode = content.diffMode,
                            graphicalDiff = content.graphicalDiff,
                            leftColumnWidthPx = content.leftColumnWidthPx,
                            autoRefresh = content.autoRefresh,
                        ))
                    }
            }
            // Initial load
            refreshList()
        }
    }

    // ── Actions ─────────────────────────────────────────────────────

    /** Re-fetch the list of changed files from the server. */
    suspend fun refreshList() {
        emit(_stateFlow.value.copy(isLoading = true, errorMessage = null))
        windowSocket.send(WindowCommand.GitList(paneId = paneId))
    }

    /**
     * Select a file and request its diff from the server.
     *
     * @param filePath path of the file to diff.
     */
    suspend fun selectFile(filePath: String) {
        emit(_stateFlow.value.copy(
            selectedFilePath = filePath,
            isLoading = true,
            diffHunks = null,
            oldContent = null,
            newContent = null,
            errorMessage = null,
        ))
        windowSocket.send(WindowCommand.GitDiff(paneId = paneId, filePath = filePath))
    }

    /**
     * Change the diff display mode and re-fetch the current file's diff.
     *
     * @param mode the new diff mode (inline or side-by-side).
     */
    suspend fun setDiffMode(mode: GitDiffMode) {
        emit(_stateFlow.value.copy(diffMode = mode))
        windowSocket.send(WindowCommand.SetGitDiffMode(paneId = paneId, mode = mode))
        _stateFlow.value.selectedFilePath?.let { selectFile(it) }
    }

    /**
     * Enable or disable graphical (word-level) diff rendering and re-fetch.
     *
     * @param enabled `true` to enable.
     */
    suspend fun setGraphicalDiff(enabled: Boolean) {
        emit(_stateFlow.value.copy(graphicalDiff = enabled))
        windowSocket.send(WindowCommand.SetGitGraphicalDiff(paneId = paneId, enabled = enabled))
        _stateFlow.value.selectedFilePath?.let { selectFile(it) }
    }

    /**
     * Enable or disable automatic refresh of the file list on file changes.
     *
     * @param enabled `true` to enable auto-refresh.
     */
    suspend fun setAutoRefresh(enabled: Boolean) {
        emit(_stateFlow.value.copy(autoRefresh = enabled))
        windowSocket.send(WindowCommand.SetGitAutoRefresh(paneId = paneId, enabled = enabled))
    }

    /**
     * Persist the file-list column width.
     *
     * @param px the new width in pixels.
     */
    suspend fun setLeftColumnWidth(px: Int) {
        emit(_stateFlow.value.copy(leftColumnWidthPx = px))
        windowSocket.send(WindowCommand.SetGitLeftWidth(paneId = paneId, px = px))
    }

    /**
     * Toggle the collapsed/expanded state of a diff section (client-side only).
     *
     * @param sectionKey identifier of the section to toggle.
     */
    suspend fun toggleSection(sectionKey: String) {
        val cur = _stateFlow.value
        val updated = if (sectionKey in cur.collapsedSections)
            cur.collapsedSections - sectionKey
        else
            cur.collapsedSections + sectionKey
        emit(cur.copy(collapsedSections = updated))
    }

    /**
     * Set the in-diff search query and reset the match index.
     *
     * @param query the search string.
     */
    suspend fun setSearchQuery(query: String) {
        emit(_stateFlow.value.copy(searchQuery = query, searchMatchIndex = 0))
    }

    /**
     * Navigate to a specific search match by index.
     *
     * @param index zero-based index of the match to highlight.
     */
    suspend fun setSearchMatchIndex(index: Int) {
        emit(_stateFlow.value.copy(searchMatchIndex = index))
    }

    private fun emit(state: State) {
        _stateFlow.value = state
    }
}
