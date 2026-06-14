/**
 * Backing ViewModel for a single file-browser pane.
 *
 * Manages the directory tree expansion state, file listings, selected file
 * content (server-rendered HTML), and user interactions (expand/collapse,
 * open file, filter, sort). All mutations are sent to the server via
 * [WindowSocket] and the authoritative state is received back through
 * [WindowEnvelope] replies.
 *
 * @see se.soderbjorn.termtastic.client.WindowSocket.fileBrowserListDir
 * @see se.soderbjorn.termtastic.client.WindowSocket.fileBrowserOpenFile
 */
package se.soderbjorn.termtastic.client.viewmodel

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.FileBrowserContent
import se.soderbjorn.termtastic.FileBrowserEntry
import se.soderbjorn.termtastic.FileBrowserSort
import se.soderbjorn.termtastic.FileContentKind
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowEnvelope
import se.soderbjorn.termtastic.client.WindowSocket

/**
 * ViewModel for a file-browser pane identified by [paneId].
 *
 * @param paneId       the unique ID of the file-browser leaf node.
 * @param windowSocket the live `/window` WebSocket for commands and replies.
 */
class FileBrowserBackingViewModel(
    private val paneId: String,
    private val windowSocket: WindowSocket,
) {
    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * Immutable snapshot of the file-browser pane UI state.
     *
     * @property dirListings       cached directory listings keyed by relative path.
     * @property expandedDirs      set of relative paths of expanded directories.
     * @property selectedRelPath   the currently selected file, or `null`.
     * @property fileContentHtml   server-rendered HTML of the selected file.
     * @property fileContentKind   the kind of content (text, image, binary, etc.).
     * @property fileFilter        current filename filter string.
     * @property sortBy            active sort mode for directory entries.
     * @property leftColumnWidthPx persisted width of the file-tree column, or `null`.
     * @property isLoading         `true` while a server request is in flight.
     * @property errorMessage      error text from the server, or `null`.
     */
    data class State(
        val dirListings: Map<String, List<FileBrowserEntry>> = emptyMap(),
        val expandedDirs: Set<String> = emptySet(),
        val selectedRelPath: String? = null,
        val fileContentHtml: String? = null,
        val fileContentKind: FileContentKind? = null,
        val fileFilter: String = "",
        val sortBy: FileBrowserSort = FileBrowserSort.NAME,
        val leftColumnWidthPx: Int? = null,
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
    )

    /**
     * Start collecting envelopes and config updates for this pane. Triggers
     * the initial root-directory listing. Long-running -- cancels when the
     * enclosing scope is cancelled.
     */
    suspend fun run() {
        coroutineScope {
            launch {
                windowSocket.envelopes
                    .collect { envelope ->
                        if (!envelope.belongsToPane(paneId)) return@collect
                        when (envelope) {
                            is WindowEnvelope.FileBrowserDir -> {
                                val cur = _stateFlow.value
                                val updated = cur.dirListings.toMutableMap()
                                updated[envelope.dirRelPath] = envelope.entries
                                emit(cur.copy(
                                    dirListings = updated,
                                    isLoading = false,
                                    errorMessage = null,
                                ))
                            }
                            is WindowEnvelope.FileBrowserContentMsg -> {
                                emit(_stateFlow.value.copy(
                                    selectedRelPath = envelope.relPath,
                                    fileContentHtml = envelope.html,
                                    fileContentKind = envelope.kind,
                                    isLoading = false,
                                    errorMessage = null,
                                ))
                            }
                            is WindowEnvelope.FileBrowserError -> {
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
                        val content = leaf.content as? FileBrowserContent ?: return@collect
                        val cur = _stateFlow.value
                        emit(cur.copy(
                            expandedDirs = content.expandedDirs,
                            selectedRelPath = content.selectedRelPath ?: cur.selectedRelPath,
                            fileFilter = content.fileFilter ?: "",
                            sortBy = content.sortBy,
                            leftColumnWidthPx = content.leftColumnWidthPx,
                        ))
                    }
            }
            // Initial load
            listDir("")
        }
    }

    // ── Actions ─────────────────────────────────────────────────────

    /**
     * Request a directory listing from the server.
     *
     * @param dirRelPath relative path of the directory; `""` for root.
     */
    suspend fun listDir(dirRelPath: String) {
        emit(_stateFlow.value.copy(isLoading = true, errorMessage = null))
        windowSocket.send(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = dirRelPath))
    }

    /**
     * Toggle a directory's expanded/collapsed state. If expanding and the
     * directory has not been listed yet, triggers a [listDir] request.
     *
     * @param dirRelPath relative path of the directory to toggle.
     */
    suspend fun toggleDir(dirRelPath: String) {
        val cur = _stateFlow.value
        val nowExpanded = dirRelPath !in cur.expandedDirs
        val newExpanded = if (nowExpanded) cur.expandedDirs + dirRelPath else cur.expandedDirs - dirRelPath
        emit(cur.copy(expandedDirs = newExpanded))
        windowSocket.send(WindowCommand.FileBrowserSetExpanded(paneId = paneId, dirRelPath = dirRelPath, expanded = nowExpanded))
        if (nowExpanded && cur.dirListings[dirRelPath] == null) {
            listDir(dirRelPath)
        }
    }

    /**
     * Select and open a file, requesting rendered content from the server.
     *
     * @param relPath relative path of the file to open.
     */
    suspend fun openFile(relPath: String) {
        emit(_stateFlow.value.copy(
            selectedRelPath = relPath,
            isLoading = true,
            fileContentHtml = null,
            fileContentKind = null,
            errorMessage = null,
        ))
        windowSocket.send(WindowCommand.FileBrowserOpenFile(paneId = paneId, relPath = relPath))
    }

    /** Expand every directory in the file tree. */
    suspend fun expandAll() {
        windowSocket.send(WindowCommand.FileBrowserExpandAll(paneId = paneId))
    }

    /** Collapse all directories in the file tree. */
    suspend fun collapseAll() {
        emit(_stateFlow.value.copy(expandedDirs = emptySet()))
        windowSocket.send(WindowCommand.FileBrowserCollapseAll(paneId = paneId))
    }

    /**
     * Set or clear the filename filter and persist it on the server.
     *
     * @param filter the filter string; empty to clear.
     */
    suspend fun setFilter(filter: String) {
        emit(_stateFlow.value.copy(fileFilter = filter))
        windowSocket.send(WindowCommand.SetFileBrowserFilter(paneId = paneId, filter = filter))
    }

    /**
     * Change the directory sort order and persist it on the server.
     *
     * @param sort the new sort mode.
     */
    suspend fun setSort(sort: FileBrowserSort) {
        emit(_stateFlow.value.copy(sortBy = sort))
        windowSocket.send(WindowCommand.SetFileBrowserSort(paneId = paneId, sort = sort))
    }

    /**
     * Persist the file-tree column width in pixels.
     *
     * @param px the new width.
     */
    suspend fun setLeftColumnWidth(px: Int) {
        emit(_stateFlow.value.copy(leftColumnWidthPx = px))
        windowSocket.send(WindowCommand.SetFileBrowserLeftWidth(paneId = paneId, px = px))
    }

    /** Re-fetch the root listing and all currently expanded directories. */
    suspend fun refresh() {
        val cur = _stateFlow.value
        listDir("")
        for (dir in cur.expandedDirs) {
            windowSocket.send(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = dir))
        }
    }

    private fun emit(state: State) {
        _stateFlow.value = state
    }
}
