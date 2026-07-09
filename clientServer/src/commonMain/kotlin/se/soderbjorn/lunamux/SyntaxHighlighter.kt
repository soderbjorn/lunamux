/**
 * Syntax highlighting for the file-browser and git-diff panes.
 *
 * This file contains [SyntaxHighlighter], a lightweight regex-based tokeniser
 * that produces HTML with `<span class="hl-...">` wrappers for CSS-driven
 * colouring. It supports JavaScript, TypeScript, Kotlin, HTML, and CSS.
 *
 * It lives in the shared `clientServer` module (pure Kotlin stdlib, no JVM
 * dependencies) so the real server's window command handler and the in-process
 * demo server (`DemoServer` / `DemoFixtures`) produce identical highlighted
 * output. Both call:
 *  - [SyntaxHighlighter.highlight] for individual git diff lines, and
 *  - [SyntaxHighlighter.highlightFile] for file previews, which auto-detects
 *    the language via [LanguageDetector] and returns the highlighted HTML
 *    together with the detected language name.
 *
 * @see LanguageDetector
 */
package se.soderbjorn.lunamux

/**
 * Lightweight regex-based syntax highlighter for diff content. Produces HTML
 * with `<span class="hl-…">` tokens for CSS-driven coloring. Supports the
 * five most common web/mobile languages:
 *
 *  1. JavaScript  2. TypeScript  3. Kotlin  4. HTML  5. CSS
 *
 * Not a full parser — just enough for visual clarity in a diff viewer.
 * Input is plain-text code; output is HTML-escaped with highlight spans.
 */
object SyntaxHighlighter {

    /**
     * Highlight [content] using the language auto-detected from [relPath].
     * Returns the rendered HTML and the detected language (or null if no
     * highlighter applies, in which case the HTML is plain escaped text).
     */
    fun highlightFile(content: String, relPath: String): Pair<String, String?> {
        val lang = LanguageDetector.detect(relPath)
        return highlight(content, lang) to lang
    }

    /**
     * Highlight [code] using the tokeniser for [language].
     *
     * If [language] is null or no tokeniser exists, the code is returned
     * as HTML-escaped plain text.
     *
     * @param code the source code to highlight
     * @param language the language identifier (as returned by [LanguageDetector.detect]),
     *                 or null for plain text
     * @return HTML string with `<span class="hl-...">` highlight tokens
     */
    fun highlight(code: String, language: String?): String {
        if (language == null) return escapeHtml(code)
        val tokenizer = TOKENIZERS[language] ?: return escapeHtml(code)
        return tokenize(code, tokenizer)
    }

    /**
     * Apply a list of [TokenPattern]s to [code], wrapping each match in a
     * `<span>` with the pattern's CSS class. Unmatched text is HTML-escaped.
     *
     * @param code the source code to tokenise
     * @param patterns ordered list of regex patterns with associated CSS classes
     * @return fully tokenised HTML string
     */
    private fun tokenize(code: String, patterns: List<TokenPattern>): String {
        // Build a combined regex from all patterns. Each alternative is
        // wrapped in a named group so we can tell which one matched.
        val combined = patterns.mapIndexed { i, tp ->
            "(?<g$i>${tp.pattern})"
        }.joinToString("|").toRegex()

        val sb = StringBuilder()
        var pos = 0
        for (match in combined.findAll(code)) {
            // Append any text before this match as plain escaped text.
            if (match.range.first > pos) {
                sb.append(escapeHtml(code.substring(pos, match.range.first)))
            }
            // Find which pattern group matched.
            val text = match.value
            var cssClass: String? = null
            for (i in patterns.indices) {
                val group = match.groups[i + 1] // groups are 1-indexed in Kotlin
                if (group != null && group.value == text) {
                    cssClass = patterns[i].cssClass
                    break
                }
            }
            if (cssClass != null) {
                sb.append("<span class=\"$cssClass\">").append(escapeHtml(text)).append("</span>")
            } else {
                sb.append(escapeHtml(text))
            }
            pos = match.range.last + 1
        }
        if (pos < code.length) {
            sb.append(escapeHtml(code.substring(pos)))
        }
        return sb.toString()
    }

    private data class TokenPattern(val pattern: String, val cssClass: String)

    // Using non-capturing groups inside each alternative; the outer layer in
    // tokenize() wraps them in capturing groups for identification.

    private val JS_KEYWORDS = listOf(
        "async", "await", "break", "case", "catch", "class", "const", "continue",
        "debugger", "default", "delete", "do", "else", "export", "extends",
        "finally", "for", "from", "function", "if", "import", "in", "instanceof",
        "let", "new", "of", "return", "static", "super", "switch", "this",
        "throw", "try", "typeof", "var", "void", "while", "with", "yield",
    ).joinToString("|")

    private val TS_EXTRA_KEYWORDS = listOf(
        "interface", "type", "enum", "as", "readonly", "implements", "declare",
        "namespace", "abstract", "keyof", "infer", "never", "unknown",
    ).joinToString("|")

    private val KOTLIN_KEYWORDS = listOf(
        "abstract", "actual", "annotation", "as", "break", "by", "catch", "class",
        "companion", "const", "constructor", "continue", "crossinline", "data",
        "do", "else", "enum", "expect", "external", "false", "final", "finally",
        "for", "fun", "get", "if", "import", "in", "infix", "init", "inline",
        "inner", "interface", "internal", "is", "it", "lateinit", "noinline",
        "null", "object", "open", "operator", "out", "override", "package",
        "private", "protected", "public", "reified", "return", "sealed", "set",
        "super", "suspend", "this", "throw", "true", "try", "typealias",
        "val", "var", "vararg", "when", "where", "while",
    ).joinToString("|")

    /**
     * Shared trailing patterns for the C-family code tokenisers (JS / TS /
     * Kotlin) that light up the remaining syntax slots. Appended *after* the
     * comment / string / keyword / number patterns so those higher-priority
     * kinds win; ordering within this list is load-bearing:
     *  - **constant** (ALL_CAPS, ≥2 chars) before **type** (TitleCase), so
     *    `MAX_VALUE` reads as a constant and `Map` as a type;
     *  - **type** / **constant** before **function** (identifier before `(`),
     *    so a constructor call `Foo(` reads as a type rather than a function
     *    while a plain `foo(` reads as a function;
     *  - **operator** last, with multi-char operators ahead of single-char
     *    ones so `&&` / `->` / `=>` aren't split.
     */
    private val CODE_TAIL_PATTERNS = listOf(
        TokenPattern("""\b[A-Z][A-Z0-9_]+\b""", "hl-constant"),
        TokenPattern("""\b[A-Z][a-zA-Z0-9_]*\b""", "hl-type"),
        TokenPattern("""\b[a-zA-Z_]\w*(?=\s*\()""", "hl-function"),
        TokenPattern("""(?:&&|\|\||\+\+|--|->|=>|::|\.\.|\?\.|\?:|[-+*/%=&|!<>^~]=?|[?:])""", "hl-operator"),
    )

    private val JS_PATTERNS = listOf(
        TokenPattern("""//[^\n]*""", "hl-comment"),
        TokenPattern("""/\*[\s\S]*?\*/""", "hl-comment"),
        TokenPattern(""""(?:[^"\\]|\\.)*"""", "hl-string"),
        TokenPattern("""'(?:[^'\\]|\\.)*'""", "hl-string"),
        TokenPattern("""`(?:[^`\\]|\\.)*`""", "hl-string"),
        TokenPattern("""\b(?:$JS_KEYWORDS)\b""", "hl-keyword"),
        TokenPattern("""\b(?:true|false|null|undefined|NaN|Infinity)\b""", "hl-keyword"),
        TokenPattern("""\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b""", "hl-number"),
        TokenPattern("""0[xX][0-9a-fA-F]+\b""", "hl-number"),
    ) + CODE_TAIL_PATTERNS

    private val TS_PATTERNS = listOf(
        TokenPattern("""//[^\n]*""", "hl-comment"),
        TokenPattern("""/\*[\s\S]*?\*/""", "hl-comment"),
        TokenPattern(""""(?:[^"\\]|\\.)*"""", "hl-string"),
        TokenPattern("""'(?:[^'\\]|\\.)*'""", "hl-string"),
        TokenPattern("""`(?:[^`\\]|\\.)*`""", "hl-string"),
        TokenPattern("""\b(?:$JS_KEYWORDS|$TS_EXTRA_KEYWORDS)\b""", "hl-keyword"),
        TokenPattern("""\b(?:true|false|null|undefined|NaN|Infinity)\b""", "hl-keyword"),
        TokenPattern("""\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b""", "hl-number"),
        TokenPattern("""0[xX][0-9a-fA-F]+\b""", "hl-number"),
    ) + CODE_TAIL_PATTERNS

    private val KOTLIN_PATTERNS = listOf(
        TokenPattern("""//[^\n]*""", "hl-comment"),
        TokenPattern("""/\*[\s\S]*?\*/""", "hl-comment"),
        TokenPattern(""""(?:[^"\\]|\\.)*"""", "hl-string"),
        TokenPattern("""\"\"\"[\s\S]*?\"\"\"""", "hl-string"),
        TokenPattern("""'(?:[^'\\]|\\.)'""", "hl-string"),
        TokenPattern("""@\w+""", "hl-annotation"),
        TokenPattern("""\b(?:$KOTLIN_KEYWORDS)\b""", "hl-keyword"),
        TokenPattern("""\b\d+(?:\.\d+)?(?:[eEfFLl])?\b""", "hl-number"),
        TokenPattern("""0[xX][0-9a-fA-F]+\b""", "hl-number"),
    ) + CODE_TAIL_PATTERNS

    private val HTML_PATTERNS = listOf(
        TokenPattern("""<!--[\s\S]*?-->""", "hl-comment"),
        TokenPattern("""</?[a-zA-Z][a-zA-Z0-9-]*""", "hl-tag"),
        TokenPattern("""/?>""", "hl-tag"),
        TokenPattern("""\b[a-zA-Z-]+(?==)""", "hl-attr"),
        TokenPattern(""""(?:[^"\\]|\\.)*"""", "hl-string"),
        TokenPattern("""'(?:[^'\\]|\\.)*'""", "hl-string"),
        TokenPattern("""&\w+;""", "hl-number"),
    )

    private val CSS_PATTERNS = listOf(
        TokenPattern("""/\*[\s\S]*?\*/""", "hl-comment"),
        TokenPattern(""""(?:[^"\\]|\\.)*"""", "hl-string"),
        TokenPattern("""'(?:[^'\\]|\\.)*'""", "hl-string"),
        TokenPattern("""#[0-9a-fA-F]{3,8}\b""", "hl-number"),
        TokenPattern("""\b\d+(?:\.\d+)?(?:px|em|rem|vh|vw|%|s|ms|deg|fr|ch|ex)\b""", "hl-number"),
        TokenPattern("""\b\d+(?:\.\d+)?\b""", "hl-number"),
        TokenPattern("""[a-zA-Z-]+(?=\s*:)""", "hl-attr"),
        TokenPattern("""@(?:media|keyframes|import|charset|font-face|supports|layer)\b""", "hl-keyword"),
        TokenPattern("""!important\b""", "hl-keyword"),
    )

    private val TOKENIZERS = mapOf(
        "javascript" to JS_PATTERNS,
        "typescript" to TS_PATTERNS,
        "kotlin" to KOTLIN_PATTERNS,
        "html" to HTML_PATTERNS,
        "css" to CSS_PATTERNS,
    )

    /**
     * Escape HTML special characters (`&`, `<`, `>`, `"`) so [text] can be
     * safely embedded in an HTML document.
     *
     * @param text raw text to escape
     * @return HTML-safe string
     */
    private fun escapeHtml(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
