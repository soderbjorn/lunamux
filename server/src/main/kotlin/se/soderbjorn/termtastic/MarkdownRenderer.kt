/**
 * Server-side Markdown-to-HTML rendering pipeline.
 *
 * This file contains [MarkdownRenderer], which parses Markdown text using
 * flexmark-java (with GFM extensions for tables, strikethrough, task lists,
 * and autolinks), renders it to HTML, and sanitises the output with jsoup to
 * prevent script injection. The resulting HTML string is sent verbatim to
 * the client for display in the file-browser pane when a `.md` file is opened.
 *
 * Called by [handleWindowCommand] in Application.kt when processing a
 * `FileBrowserOpenFile` command for a Markdown file.
 *
 * @see FileBrowserCatalog
 * @see SyntaxHighlighter
 */
package se.soderbjorn.termtastic

import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/**
 * Server-side markdown → sanitized HTML pipeline used by the markdown
 * overview pane. The client receives the HTML string verbatim and assigns
 * it via `innerHTML` — sanitization here is the only line of defence
 * against script injection from a markdown file the user happens to open.
 *
 * Termtastic is a local-only tool over files the user already has on disk,
 * so the threat model is "don't accidentally execute a script tag pasted
 * into someone's notes," not a hostile attacker. The jsoup `Safelist.relaxed`
 * baseline is plenty, augmented with task-list `<input>` tags so GFM
 * checkboxes survive (rendered read-only).
 */
object MarkdownRenderer {
    private val parser: Parser
    private val renderer: HtmlRenderer
    private val safelist: Safelist

    init {
        val options = MutableDataSet().apply {
            set(
                Parser.EXTENSIONS,
                listOf(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    AutolinkExtension.create(),
                    TaskListExtension.create(),
                )
            )
            // Generate id attributes on heading elements so TOC anchor links
            // (e.g. [Foo](#foo)) resolve to the correct heading.
            set(HtmlRenderer.GENERATE_HEADER_ID, true)
            set(HtmlRenderer.RENDER_HEADER_ID, true)
        }
        parser = Parser.builder(options).build()
        renderer = HtmlRenderer.builder(options).build()
        safelist = Safelist.relaxed()
            .addTags("input")
            .addAttributes("input", "type", "checked", "disabled")
            // Allow common code-block class hooks so future syntax-highlighting
            // can attach selectors without us re-touching the safelist.
            .addAttributes("code", "class")
            .addAttributes("pre", "class")
            // Allow id on headings so TOC anchor links can resolve.
            .addAttributes("h1", "id")
            .addAttributes("h2", "id")
            .addAttributes("h3", "id")
            .addAttributes("h4", "id")
            .addAttributes("h5", "id")
            .addAttributes("h6", "id")
    }

    /** Parse [markdown], render to HTML, then sanitize with jsoup. */
    fun render(markdown: String): String {
        val rawHtml = renderer.render(parser.parse(markdown))
        return Jsoup.clean(rawHtml, safelist)
    }
}
