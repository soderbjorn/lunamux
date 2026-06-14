/**
 * Unified diff parser for the git-diff pane.
 *
 * This file contains [DiffParser], which converts raw unified diff text
 * (as produced by `git diff`) into a list of [DiffHunk] / [DiffLine] objects
 * that the client renders as a side-by-side or inline diff view. It also
 * provides [syntheticAdd] and [syntheticDelete] helpers for files that have
 * no previous revision (untracked/added) or have been removed.
 *
 * Called exclusively by [GitCatalog.readDiff] when building the diff payload
 * for a `WindowCommand.GitDiff` request.
 *
 * @see GitCatalog
 * @see DiffHunk
 * @see DiffLine
 */
package se.soderbjorn.termtastic

/**
 * Parses unified diff output (as produced by `git diff`) into structured
 * [DiffHunk] / [DiffLine] objects. Handles the `@@ -a,b +c,d @@` hunk
 * header format and maps each line to a [DiffLineType] with old/new line
 * numbers.
 */
object DiffParser {
    private val HUNK_HEADER = Regex("""^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@""")

    /**
     * Parse a unified diff string into a list of hunks.
     * Lines like `\ No newline at end of file` are silently dropped.
     * Trailing `\r` is stripped for cross-platform safety.
     */
    fun parse(unifiedDiff: String): List<DiffHunk> {
        val hunks = mutableListOf<DiffHunk>()
        val lines = unifiedDiff.lines()
        var i = 0

        // Skip the diff header lines (--- / +++ / diff --git / index ...)
        while (i < lines.size && !lines[i].startsWith("@@")) i++

        while (i < lines.size) {
            val line = lines[i].trimEnd('\r')
            val match = HUNK_HEADER.find(line)
            if (match != null) {
                val oldStart = match.groupValues[1].toInt()
                val oldCount = match.groupValues[2].ifEmpty { "1" }.toInt()
                val newStart = match.groupValues[3].toInt()
                val newCount = match.groupValues[4].ifEmpty { "1" }.toInt()

                val hunkLines = mutableListOf<DiffLine>()
                var oldLine = oldStart
                var newLine = newStart
                i++

                while (i < lines.size) {
                    val l = lines[i].trimEnd('\r')
                    if (l.startsWith("@@")) break
                    if (l.startsWith("\\ ")) { i++; continue } // "\ No newline at end of file"
                    if (l.startsWith("diff ")) break // next file in multi-file diff

                    when {
                        l.startsWith("+") -> {
                            hunkLines.add(DiffLine(
                                type = DiffLineType.Addition,
                                newLineNo = newLine,
                                content = l.substring(1),
                            ))
                            newLine++
                        }
                        l.startsWith("-") -> {
                            hunkLines.add(DiffLine(
                                type = DiffLineType.Deletion,
                                oldLineNo = oldLine,
                                content = l.substring(1),
                            ))
                            oldLine++
                        }
                        else -> {
                            // Context line (starts with space or is empty for
                            // trailing context at end of hunk).
                            val content = if (l.startsWith(" ")) l.substring(1) else l
                            hunkLines.add(DiffLine(
                                type = DiffLineType.Context,
                                oldLineNo = oldLine,
                                newLineNo = newLine,
                                content = content,
                            ))
                            oldLine++
                            newLine++
                        }
                    }
                    i++
                }
                hunks.add(DiffHunk(
                    oldStart = oldStart,
                    oldCount = oldCount,
                    newStart = newStart,
                    newCount = newCount,
                    lines = hunkLines,
                ))
            } else {
                i++
            }
        }
        return hunks
    }

    /**
     * Synthesize a diff for a completely new (untracked/added) file: every
     * line is an addition.
     */
    fun syntheticAdd(content: String): List<DiffHunk> {
        val lines = content.lines()
        val diffLines = lines.mapIndexed { idx, line ->
            DiffLine(
                type = DiffLineType.Addition,
                newLineNo = idx + 1,
                content = line,
            )
        }
        return listOf(DiffHunk(
            oldStart = 0, oldCount = 0,
            newStart = 1, newCount = lines.size,
            lines = diffLines,
        ))
    }

    /**
     * Synthesize a diff for a deleted file: every line is a deletion.
     */
    fun syntheticDelete(content: String): List<DiffHunk> {
        val lines = content.lines()
        val diffLines = lines.mapIndexed { idx, line ->
            DiffLine(
                type = DiffLineType.Deletion,
                oldLineNo = idx + 1,
                content = line,
            )
        }
        return listOf(DiffHunk(
            oldStart = 1, oldCount = lines.size,
            newStart = 0, newCount = 0,
            lines = diffLines,
        ))
    }
}
