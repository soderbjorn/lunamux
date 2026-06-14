/**
 * Filesystem listing and content serving for the file-browser pane.
 *
 * This file contains [FileBrowserCatalog], which provides one-level directory
 * listings, recursive "expand all" walks, file content reading with binary
 * detection, and glob/substring filtering. All path operations are sandboxed
 * to the pane's working-directory root to prevent path-traversal attacks.
 *
 * Called by:
 *  - [handleWindowCommand] in Application.kt for `FileBrowserListDir`,
 *    `FileBrowserOpenFile`, `FileBrowserExpandAll`, and filter/sort commands.
 *  - [buildFileBrowserDirEnvelope] to construct directory listing envelopes
 *    pushed over the `/window` WebSocket.
 *
 * @see FileBrowserContent
 * @see WindowState
 */
package se.soderbjorn.termtastic

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * One-level directory listing + single-file read for the file-browser pane.
 * Shares its path-safety and ignore rules with the former `MarkdownCatalog`.
 *
 * Layout lookups go one directory at a time — the client is responsible for
 * expanding/collapsing and re-requesting. That keeps big repos from walking
 * thousands of files before the first frame lands.
 */
object FileBrowserCatalog {
    private val log = LoggerFactory.getLogger(FileBrowserCatalog::class.java)

    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val BINARY_SNIFF_BYTES = 8 * 1024

    // VCS metadata + common package caches / build outputs / IDE state.
    // Mirrors the old MarkdownCatalog list. Other dot-prefixed dirs like
    // .claude, .vscode, .github may be interesting so we don't hide them.
    private val IGNORED_DIRS = setOf(
        ".git", "node_modules", "build", "target", "dist", "out", ".gradle", ".idea",
    )

    /** Result of [readFile]: either HTML-ready text content or a binary flag. */
    sealed class FileRead {
        data class Text(val content: String) : FileRead()
        object Binary : FileRead()
    }

    /**
     * Compile [filter] (a glob like `*.md`, or empty/null for "all files") to
     * a name-matching predicate. Substrings without glob metacharacters are
     * treated as case-insensitive substring matches so plain text in the
     * filter box still narrows the listing as the user types.
     */
    fun compileFilter(filter: String?): (String) -> Boolean {
        val pattern = filter?.trim().orEmpty()
        if (pattern.isEmpty()) return { true }
        val hasGlob = pattern.any { it == '*' || it == '?' || it == '[' }
        if (!hasGlob) {
            val lower = pattern.lowercase()
            return { name -> name.lowercase().contains(lower) }
        }
        val regex = globToRegex(pattern)
        return { name -> regex.matches(name) }
    }

    /**
     * Convert a simple glob pattern (with `*`, `?`, and `[` metacharacters)
     * to a case-insensitive [Regex].
     *
     * @param glob the glob pattern string
     * @return a compiled regex that matches filenames against the glob
     */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("(?i)^")
        var i = 0
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '\\' ->
                    sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append('$')
        return Regex(sb.toString())
    }

    /**
     * List the direct children of [dirRelPath] under [root]. Dirs come first
     * alphabetically, then files alphabetically. Empty list if [root] isn't
     * a directory, [dirRelPath] escapes [root], or the target isn't readable.
     * [dirRelPath] is `""` for the root directory.
     *
     * [filter] is a filename glob (`*.md`) or substring; null/blank disables
     * filtering. Directories are never filtered — the user must be able to
     * walk into them even when the filter only matches deeper files.
     */
    fun listDir(
        root: Path,
        dirRelPath: String,
        filter: String? = null,
        sort: FileBrowserSort = FileBrowserSort.NAME,
    ): List<FileBrowserEntry> {
        val matcher = compileFilter(filter)
        val filterActive = !filter?.trim().isNullOrEmpty()
        return listDirInternal(root, dirRelPath, matcher, filterActive, sort)
    }

    private fun listDirInternal(
        root: Path,
        dirRelPath: String,
        nameMatches: (String) -> Boolean,
        filterActive: Boolean = false,
        sort: FileBrowserSort = FileBrowserSort.NAME,
    ): List<FileBrowserEntry> {
        val base = runCatching { root.toRealPath() }.getOrNull() ?: return emptyList()
        val target = runCatching {
            if (dirRelPath.isEmpty()) base
            else base.resolve(dirRelPath).normalize().toRealPath()
        }.getOrNull() ?: return emptyList()
        if (!target.startsWith(base)) return emptyList()
        if (!Files.isDirectory(target)) return emptyList()

        val dirs = ArrayList<FileBrowserEntry>()
        val files = ArrayList<FileBrowserEntry>()
        try {
            Files.newDirectoryStream(target).use { stream ->
                for (path in stream) {
                    val name = path.fileName?.toString() ?: continue
                    val attrs = runCatching {
                        Files.readAttributes(path, BasicFileAttributes::class.java)
                    }.getOrNull() ?: continue
                    if (attrs.isSymbolicLink) continue
                    val isDir = attrs.isDirectory
                    if (isDir && name in IGNORED_DIRS) continue
                    if (!isDir && !attrs.isRegularFile) continue
                    val rel = base.relativize(path).toString()
                    val entry = FileBrowserEntry(
                        name = name,
                        relPath = rel,
                        isDir = isDir,
                        sizeBytes = if (isDir) 0 else attrs.size(),
                        mtimeEpochMs = attrs.lastModifiedTime().toMillis(),
                    )
                    if (isDir) {
                        // With a filter active, prune directories that have
                        // no matching files anywhere in their subtree so the
                        // tree stays clean instead of showing empty scaffolding.
                        if (!filterActive || dirHasMatch(path, nameMatches)) dirs.add(entry)
                    } else if (nameMatches(name)) {
                        files.add(entry)
                    }
                }
            }
        } catch (t: Throwable) {
            log.warn("Dir listing failed under {}/{}", base, dirRelPath, t)
            return emptyList()
        }
        when (sort) {
            FileBrowserSort.NAME -> {
                dirs.sortBy { it.name.lowercase() }
                files.sortBy { it.name.lowercase() }
            }
            FileBrowserSort.MTIME -> {
                dirs.sortByDescending { it.mtimeEpochMs }
                files.sortByDescending { it.mtimeEpochMs }
            }
        }
        return dirs + files
    }

    /**
     * Recursively check whether [dirAbs] (or any subdirectory up to [maxDepth]
     * levels deep) contains at least one file whose name satisfies [nameMatches].
     * Used to prune empty directories from the tree when a filter is active.
     *
     * @param dirAbs absolute path to the directory to search
     * @param nameMatches predicate compiled from the user's filter input
     * @param maxDepth maximum recursion depth (default 8)
     * @return true if at least one matching file exists in the subtree
     */
    private fun dirHasMatch(
        dirAbs: Path,
        nameMatches: (String) -> Boolean,
        maxDepth: Int = 8,
    ): Boolean {
        val subdirs = ArrayList<Path>()
        try {
            Files.newDirectoryStream(dirAbs).use { stream ->
                for (path in stream) {
                    val name = path.fileName?.toString() ?: continue
                    val attrs = runCatching {
                        Files.readAttributes(path, BasicFileAttributes::class.java)
                    }.getOrNull() ?: continue
                    if (attrs.isSymbolicLink) continue
                    if (attrs.isDirectory) {
                        if (name in IGNORED_DIRS) continue
                        subdirs.add(path)
                    } else if (attrs.isRegularFile && nameMatches(name)) {
                        return true
                    }
                }
            }
        } catch (_: Throwable) {
            return false
        }
        if (maxDepth <= 0) return false
        for (sub in subdirs) {
            if (dirHasMatch(sub, nameMatches, maxDepth - 1)) return true
        }
        return false
    }

    /**
     * Result of [listAll]: per-directory listings keyed by `dirRelPath`
     * (`""` for the root) plus a flag set when the walk hit either of the
     * caps. The "Expand all" UI uses [truncated] to surface a notice.
     */
    data class WalkResult(
        val listings: Map<String, List<FileBrowserEntry>>,
        val expandedDirs: Set<String>,
        val truncated: Boolean,
    )

    /**
     * Recursively walk every directory under [root] (skipping [IGNORED_DIRS])
     * and return per-level listings. Stops early once [maxEntries] file
     * entries or [maxDepth] levels have been visited. Caller uses the result
     * to populate the file-browser tree client-side without a per-dir
     * round-trip.
     */
    fun listAll(
        root: Path,
        filter: String? = null,
        sort: FileBrowserSort = FileBrowserSort.NAME,
        maxEntries: Int = 5000,
        maxDepth: Int = 6,
    ): WalkResult {
        val matcher = compileFilter(filter)
        val filterActive = !filter?.trim().isNullOrEmpty()
        val listings = LinkedHashMap<String, List<FileBrowserEntry>>()
        val expanded = LinkedHashSet<String>()
        var totalFiles = 0
        var truncated = false

        fun walk(dirRelPath: String, depth: Int) {
            if (depth > maxDepth) { truncated = true; return }
            val entries = listDirInternal(root, dirRelPath, matcher, filterActive, sort)
            listings[dirRelPath] = entries
            totalFiles += entries.count { !it.isDir }
            if (totalFiles > maxEntries) { truncated = true; return }
            for (entry in entries) {
                if (!entry.isDir) continue
                expanded.add(entry.relPath)
                walk(entry.relPath, depth + 1)
                if (truncated) return
            }
        }
        walk("", 0)
        return WalkResult(listings, expanded, truncated)
    }

    /**
     * Read [relPath] under [root]. Returns null on path-traversal, missing
     * files, or size > 2 MB. Returns [FileRead.Binary] if the first 8 KB
     * contain a NUL byte; else [FileRead.Text] with the full decoded string.
     */
    fun readFile(root: Path, relPath: String): FileRead? {
        val base = runCatching { root.toRealPath() }.getOrNull() ?: return null
        val resolved = runCatching { base.resolve(relPath).normalize().toRealPath() }.getOrNull()
            ?: return null
        if (!resolved.startsWith(base)) return null
        if (!Files.isRegularFile(resolved)) return null
        val size = runCatching { Files.size(resolved) }.getOrNull() ?: return null
        if (size > MAX_FILE_BYTES) return null

        // Binary sniff: read up to the first 8 KB and look for a NUL byte.
        val sniffLen = minOf(size.toInt(), BINARY_SNIFF_BYTES)
        val head = runCatching { Files.newInputStream(resolved).use { it.readNBytes(sniffLen) } }
            .getOrNull() ?: return null
        if (head.any { it == 0.toByte() }) return FileRead.Binary
        return runCatching { FileRead.Text(Files.readString(resolved)) }.getOrNull()
    }
}
