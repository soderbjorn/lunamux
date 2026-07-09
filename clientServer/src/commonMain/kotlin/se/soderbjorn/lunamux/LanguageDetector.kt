/**
 * Programming language detection from file extensions.
 *
 * This file contains [LanguageDetector], a simple extension-based mapper that
 * returns the language identifier string used by [SyntaxHighlighter]. It is
 * the single source of truth for extension-to-language mapping so that the
 * git-diff pane and the file-browser pane ([SyntaxHighlighter.highlightFile])
 * stay in sync, in both the real server and the demo server.
 *
 * @see SyntaxHighlighter
 */
package se.soderbjorn.lunamux

/**
 * Map a file path to the language identifier the [SyntaxHighlighter] understands,
 * or `null` when no highlighter is available (the highlighter falls back to
 * plain HTML-escaped output in that case). Extracted from
 * `GitCatalog.detectLanguage` so the git-diff pane and the file-browser pane
 * stay in sync.
 */
object LanguageDetector {
    /**
     * Detect the programming language from the file extension of [filePath].
     *
     * @param filePath a file path or name (only the extension after the last `.` is used)
     * @return a language identifier string (`"javascript"`, `"typescript"`,
     *         `"kotlin"`, `"html"`, or `"css"`), or null if no highlighter
     *         is available for this extension
     */
    fun detect(filePath: String): String? {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "js", "jsx", "mjs", "cjs" -> "javascript"
            "ts", "tsx", "mts", "cts" -> "typescript"
            "kt", "kts" -> "kotlin"
            "html", "htm" -> "html"
            "css", "scss", "less" -> "css"
            else -> null
        }
    }
}
