import SwiftUI
import UIKit
import WebKit
import Client

/// Renders a single file from the remote pane. Markdown and text files load
/// into a WKWebView using `wrapFileHtml`; binary files render a placeholder.
/// Mirrors the Android `FileBrowserContentScreen`.
struct FileBrowserContentView: View {
    let paneId: String
    let relPath: String
    var onBack: () -> Void

    @State private var html: String?
    @State private var kind: Client.FileContentKind?
    @State private var errorMessage: String?
    @State private var theme: Client.ResolvedTheme?

    private var fileName: String {
        relPath.components(separatedBy: "/").last ?? relPath
    }

    var body: some View {
        ZStack {
            if kind == Client.FileContentKind.binary {
                Text("Binary file — preview unavailable.")
                    .foregroundStyle(.gray)
                    .padding()
            } else if let currentHtml = html, let currentKind = kind {
                // For HTML files load the raw document directly so the
                // page's own <html>/<head>/<style> are honored; for Markdown
                // and syntax-highlighted Text we still go through the
                // theming wrapper. Relative assets won't resolve (baseURL =
                // nil in FileWebView) — intentional, matches the web srcdoc
                // fallback.
                let payload: String = (currentKind == Client.FileContentKind.html)
                    ? currentHtml
                    : wrapFileHtml(currentHtml, kind: currentKind, theme: theme)
                FileWebView(html: payload)
            } else if let error = errorMessage {
                Text(error)
                    .foregroundStyle(.gray)
                    .padding()
            } else {
                ProgressView()
                    .tint(Palette.textSecondary)
            }
        }
        .navigationTitle(fileName)
        .navigationBarTitleDisplayMode(.inline)
        .task { await loadContent() }
        .task {
            if let central = Palette.settings {
                theme = central
            } else if let client = ConnectionHolder.shared.client,
               let config = try? await client.fetchThemeConfig() {
                let isDark = UITraitCollection.current.userInterfaceStyle == .dark
                theme = config.resolve(systemIsDark: isDark)
            }
        }
    }

    private func loadContent() async {
        guard let socket = ConnectionHolder.shared.windowSocket else {
            errorMessage = "Not connected"
            return
        }
        do {
            let reply = try await socket.fileBrowserOpenFile(
                paneId: paneId,
                relPath: relPath,
                timeoutMs: 10_000
            )
            if let content = reply as? Client.WindowEnvelope.FileBrowserContentMsg {
                kind = content.kind
                html = content.html
            } else if let error = reply as? Client.WindowEnvelope.FileBrowserError {
                errorMessage = error.message
            } else {
                errorMessage = "No response"
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - WKWebView wrapper

private struct FileWebView: UIViewRepresentable {
    let html: String

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.isOpaque = false
        webView.backgroundColor = UIColor(Palette.background)
        webView.scrollView.backgroundColor = UIColor(Palette.background)
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        webView.loadHTMLString(html, baseURL: nil)
    }
}

// MARK: - HTML wrapper

/// Wraps server-generated HTML with the markdown preview stylesheet, plus
/// `.hl-\*` rules so pre-tokenised source files match the diff viewer's
/// palette.
/// Generates CSS custom property declarations from a resolved theme.
///
/// Emits the flat `--t-*` token names (matching the web client) so the
/// stylesheet below can reference them directly.
private func paletteVars(_ t: Client.ResolvedTheme) -> String {
    let c = { (v: Int64) -> String in Client.ColorMathKt.argbToCss(argb: v) }
    return """
        --t-bg: \(c(t.bg));
        --t-surface: \(c(t.surface));
        --t-surface-alt: \(c(t.surfaceAlt));
        --t-border: \(c(t.border));
        --t-text: \(c(t.text));
        --t-text-dim: \(c(t.textDim));
        --t-text-bright: \(c(t.textBright));
        --t-accent: \(c(t.accent));
        --t-accent-soft: \(c(t.accentSoft));
        --t-glow: \(c(t.glow));
        --t-warn: \(c(t.warn));
        --t-danger: \(c(t.danger));
        --t-add: \(c(t.add));
        --t-add-bg: \(c(t.addBg));
        --t-add-text: \(c(t.addText));
        --t-syn-keyword: \(c(t.synKeyword));
        --t-syn-string: \(c(t.synString));
        --t-syn-number: \(c(t.synNumber));
        --t-syn-comment: \(c(t.synComment));
        --t-syn-function: \(c(t.synFunction));
        --t-syn-type: \(c(t.synType));
        --t-syn-operator: \(c(t.synOperator));
        --t-syn-constant: \(c(t.synConstant));
    """
}

private func wrapFileHtml(_ bodyHtml: String, kind: Client.FileContentKind, theme: Client.ResolvedTheme? = nil) -> String {
    // The flat ResolvedTheme already encodes a single resolved appearance, so
    // no per-pane scheme map / media-query split is needed: a config is
    // resolved upstream for the host's current appearance. Fall back to the
    // default theme resolved for the current appearance.
    let t: Client.ResolvedTheme = theme ?? {
        let isDark = UITraitCollection.current.userInterfaceStyle == .dark
        return Client.ThemeConfigKt.defaultThemeConfig().resolve(systemIsDark: isDark)
    }()
    let cssVarsBlock = """
      :root {
        \(paletteVars(t))
      }
    """

    let body: String
    if kind == Client.FileContentKind.text {
        body = "<pre class=\"file-source\"><code>\(bodyHtml)</code></pre>"
    } else {
        body = bodyHtml
    }
    return """
    <!DOCTYPE html>
    <html>
    <head>
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <style>
      \(cssVarsBlock)
      * { margin: 0; padding: 0; box-sizing: border-box; }
      html, body {
        background: var(--t-bg);
        color: var(--t-text);
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        font-size: 15px;
        line-height: 1.6;
        padding: 16px 18px 32px;
        word-wrap: break-word;
      }
      h1, h2, h3, h4, h5, h6 {
        color: var(--t-text);
        margin-top: 1.5em;
        margin-bottom: 0.5em;
        line-height: 1.25;
      }
      h1 { font-size: 1.8em; border-bottom: 1px solid var(--t-border); padding-bottom: 0.3em; }
      h2 { font-size: 1.5em; border-bottom: 1px solid var(--t-border); padding-bottom: 0.3em; }
      h3 { font-size: 1.25em; }
      p { margin: 0.75em 0; }
      a { color: var(--t-accent); text-decoration: none; }
      a:active { text-decoration: underline; }
      code {
        font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        font-size: 0.9em;
        color: var(--t-text);
        background: var(--t-surface-alt);
        border: 1px solid var(--t-border);
        padding: 0.15em 0.4em;
        border-radius: 3px;
      }
      pre {
        color: var(--t-text);
        background: var(--t-surface-alt);
        border: 1px solid var(--t-border);
        padding: 12px 14px;
        border-radius: 4px;
        overflow-x: auto;
        margin: 0.75em 0;
      }
      pre code { background: transparent; border: none; padding: 0; font-size: 0.9em; }
      pre.file-source {
        white-space: pre;
        font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        font-size: 13px;
        line-height: 1.45;
      }
      blockquote {
        border-left: 3px solid var(--t-border);
        margin: 0.75em 0;
        padding: 0 1em;
        color: var(--t-text-dim);
      }
      ul, ol { margin: 0.5em 0; padding-left: 1.5em; }
      li { margin: 0.25em 0; }
      hr { border: 0; border-top: 1px solid var(--t-border); margin: 1.2em 0; }
      table { border-collapse: collapse; margin: 0.75em 0; }
      th, td { border: 1px solid var(--t-border); padding: 6px 10px; }
      th { background: var(--t-surface); }
      img { max-width: 100%; height: auto; }

      .hl-keyword { color: var(--t-syn-keyword); }
      .hl-string { color: var(--t-syn-string); }
      .hl-number { color: var(--t-syn-number); }
      .hl-comment { color: var(--t-syn-comment); font-style: italic; }
      .hl-function { color: var(--t-syn-function); }
      .hl-type { color: var(--t-syn-type); }
      .hl-operator { color: var(--t-syn-operator); }
      .hl-constant { color: var(--t-syn-constant); }
      .hl-tag { color: var(--t-syn-keyword); }
      .hl-attr { color: var(--t-syn-function); }
      .hl-annotation { color: var(--t-syn-function); }
      .hl-punctuation { color: var(--t-syn-operator); }
    </style>
    </head>
    <body>
    \(body)
    </body>
    </html>
    """
}
