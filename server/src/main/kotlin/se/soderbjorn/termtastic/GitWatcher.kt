/**
 * Filesystem watcher for git working-tree changes.
 *
 * This file contains [GitWatcher] and [GitWatchHandle], which together provide
 * per-pane recursive filesystem watching for the git pane's "auto-refresh"
 * toggle. When enabled, changes to tracked files, `.git/index`, and
 * `.git/refs/` trigger a debounced callback that refreshes the git file list
 * and the currently-selected diff.
 *
 * Lifecycle is managed by [WindowConnectionContext]: each `/window` WebSocket
 * connection owns its watcher handles, and they are closed when the connection
 * drops or the pane is closed.
 *
 * @see GitCatalog
 * @see WindowConnectionContext
 */
package se.soderbjorn.termtastic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Per-pane recursive watcher for the git working tree, used by the git
 * pane's "auto-refresh" toggle. Mirrors [MarkdownWatchers] but also watches
 * `.git/index` and `.git/refs/` for staging/commit changes.
 *
 * The debounce is slightly longer than the markdown watcher (500 ms vs
 * 300 ms) because git operations are heavier.
 */
/**
 * Handle to a running git filesystem watcher. Holds the background coroutine
 * [Job] and the [WatchService] so both can be torn down when the pane is
 * closed or the WebSocket disconnects.
 *
 * @property job the coroutine running the watch loop
 * @property service the Java NIO [WatchService] being polled
 *
 * @see GitWatcher.register
 * @see WindowConnectionContext
 */
class GitWatchHandle internal constructor(
    private val job: Job,
    private val service: WatchService,
) {
    /**
     * Cancel the watch coroutine and close the underlying [WatchService],
     * releasing file descriptors.
     */
    fun close() {
        job.cancel()
        runCatching { service.close() }
    }
}

object GitWatcher {
    private val log = LoggerFactory.getLogger(GitWatcher::class.java)
    private const val DEBOUNCE_MS = 500L

    // Same ignore set as MarkdownCatalog, but we DO watch .git subdirectories
    // (index, refs) — those are registered separately below.
    private val IGNORED_DIRS = setOf(
        ".git", "node_modules", "build", "target", "dist", "out", ".gradle", ".idea",
    )

    /**
     * Start watching [root] for filesystem changes and invoke [onChange]
     * (debounced at 500 ms) when something changes in the working tree
     * or `.git/` metadata.
     *
     * Returns a [GitWatchHandle] that the caller must [close][GitWatchHandle.close]
     * when watching is no longer needed, or null if [root] is not a valid
     * directory.
     *
     * @param scope the coroutine scope owning the watch loop's lifecycle
     * @param root the git repository root directory to watch
     * @param onChange suspend callback invoked on each debounced change event
     * @return a handle for cancelling the watcher, or null on failure
     */
    fun register(
        scope: CoroutineScope,
        root: Path,
        onChange: suspend () -> Unit,
    ): GitWatchHandle? {
        if (!Files.isDirectory(root)) return null
        val real = runCatching { root.toRealPath() }.getOrNull() ?: return null
        val service = runCatching { real.fileSystem.newWatchService() }.getOrNull() ?: return null

        val keys = ConcurrentHashMap<WatchKey, Path>()

        fun registerDir(dir: Path) {
            try {
                val key = dir.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                keys[key] = dir
            } catch (t: Throwable) {
                log.debug("Failed to watch {}: {}", dir, t.message)
            }
        }

        // Walk working tree (excluding .git).
        try {
            Files.walkFileTree(real, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink) return FileVisitResult.SKIP_SUBTREE
                    val name = dir.fileName?.toString()
                    if (dir != real && name != null && name in IGNORED_DIRS) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    registerDir(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (t: Throwable) {
            log.warn("Initial git watcher walk failed for {}", real, t)
        }

        // Also watch .git/index and .git/refs/ for staging/commit changes.
        val gitDir = real.resolve(".git")
        if (Files.isDirectory(gitDir)) {
            registerDir(gitDir)
            val refsDir = gitDir.resolve("refs")
            if (Files.isDirectory(refsDir)) {
                try {
                    Files.walkFileTree(refsDir, object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            registerDir(dir)
                            return FileVisitResult.CONTINUE
                        }
                    })
                } catch (_: Throwable) {}
            }
        }

        val job = scope.launch(Dispatchers.IO) {
            var lastFireMs = 0L
            try {
                while (isActive) {
                    val key = try {
                        service.poll(DEBOUNCE_MS, TimeUnit.MILLISECONDS) ?: continue
                    } catch (_: ClosedWatchServiceException) {
                        break
                    } catch (_: InterruptedException) {
                        break
                    }
                    val dir = keys[key]
                    var sawChange = false
                    for (event in key.pollEvents()) {
                        val ctx = event.context() as? Path ?: continue
                        val resolved = dir?.resolve(ctx) ?: continue
                        if (event.kind() == ENTRY_CREATE && Files.isDirectory(resolved)) {
                            val name = resolved.fileName?.toString()
                            if (name != null && name !in IGNORED_DIRS) {
                                registerDir(resolved)
                            }
                        }
                        sawChange = true
                    }
                    if (!key.reset()) {
                        keys.remove(key)
                    }
                    if (!sawChange) continue

                    val now = System.currentTimeMillis()
                    if (now - lastFireMs < DEBOUNCE_MS) {
                        delay(DEBOUNCE_MS - (now - lastFireMs))
                    }
                    while (true) {
                        val drained = service.poll() ?: break
                        drained.pollEvents()
                        if (!drained.reset()) keys.remove(drained)
                    }
                    lastFireMs = System.currentTimeMillis()
                    runCatching { onChange() }.onFailure {
                        log.debug("Git watcher onChange failed", it)
                    }
                }
            } finally {
                runCatching { service.close() }
            }
        }
        return GitWatchHandle(job, service)
    }
}
