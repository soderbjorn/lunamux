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
    @State private var uiSettings: Client.UiSettings?

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
                    : wrapFileHtml(currentHtml, kind: currentKind, settings: uiSettings, section: "fileBrowser")
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
                uiSettings = central
            } else if let client = ConnectionHolder.shared.client,
               let config = try? await client.fetchThemeConfig() {
                let isDark = UITraitCollection.current.userInterfaceStyle == .dark
                uiSettings = config.resolve(systemIsDark: isDark)
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
/// Generates CSS custom property declarations from a resolved palette.
private func paletteVars(_ pal: Client.ResolvedPalette) -> String {
    let c = { (v: Int64) -> String in Client.ColorMathKt.argbToCss(argb: v) }
    return """
        --background: \(c(pal.surface.base));
        --surface: \(c(pal.surface.raised));
        --bg-elevated: \(c(pal.surface.overlay));
        --text-primary: \(c(pal.text.primary));
        --text-secondary: \(c(pal.text.secondary));
        --separator: \(c(pal.border.subtle));
        --accent: \(c(pal.accent.primary));
        --hl-keyword: \(c(pal.syntax.keyword));
        --hl-string: \(c(pal.syntax.string));
        --hl-comment: \(c(pal.syntax.comment));
        --hl-number: \(c(pal.syntax.number));
        --hl-tag: \(c(pal.syntax.keyword));
        --hl-attr: \(c(pal.syntax.function));
        --hl-type: \(c(pal.syntax.type));
        --hl-annotation: \(c(pal.syntax.constant));
        --hl-punctuation: \(c(pal.syntax.operator_));
    """
}

private func wrapFileHtml(_ bodyHtml: String, kind: Client.FileContentKind, settings: Client.UiSettings? = nil, section: String = "fileBrowser") -> String {
    let effectiveTheme: Client.ColorScheme
    if let s = settings {
        effectiveTheme = s.schemeForPane(pane: section)
    } else {
        let themes = Client.ColorSchemesKt.recommendedColorSchemes
        let defaultName = Client.ColorSchemesKt.DEFAULT_THEME_NAME
        effectiveTheme = themes.first { $0.name == defaultName } ?? themes[0]
    }

    // When the user has explicitly chosen Dark or Light, emit only that
    // palette's CSS vars. When Auto, use media queries for both modes.
    let appearance = settings?.appearance ?? Client.Appearance.auto_
    let cssVarsBlock: String
    switch appearance {
    case .dark:
        let pal = effectiveTheme.resolve(isDark: true)
        cssVarsBlock = """
          :root {
            color-scheme: dark;
            \(paletteVars(pal))
          }
        """
    case .light:
        let pal = effectiveTheme.resolve(isDark: false)
        cssVarsBlock = """
          :root {
            color-scheme: light;
            \(paletteVars(pal))
          }
        """
    default:
        let darkPal = effectiveTheme.resolve(isDark: true)
        let lightPal = effectiveTheme.resolve(isDark: false)
        cssVarsBlock = """
          :root {
            color-scheme: light dark;
            \(paletteVars(darkPal))
          }
          @media (prefers-color-scheme: light) {
            :root {
              \(paletteVars(lightPal))
            }
          }
        """
    }

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
        background: var(--background);
        color: var(--text-primary);
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        font-size: 15px;
        line-height: 1.6;
        padding: 16px 18px 32px;
        word-wrap: break-word;
      }
      h1, h2, h3, h4, h5, h6 {
        color: var(--text-primary);
        margin-top: 1.5em;
        margin-bottom: 0.5em;
        line-height: 1.25;
      }
      h1 { font-size: 1.8em; border-bottom: 1px solid var(--separator); padding-bottom: 0.3em; }
      h2 { font-size: 1.5em; border-bottom: 1px solid var(--separator); padding-bottom: 0.3em; }
      h3 { font-size: 1.25em; }
      p { margin: 0.75em 0; }
      a { color: var(--accent); text-decoration: none; }
      a:active { text-decoration: underline; }
      code {
        font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        font-size: 0.9em;
        color: var(--text-primary);
        background: var(--bg-elevated);
        border: 1px solid var(--separator);
        padding: 0.15em 0.4em;
        border-radius: 3px;
      }
      pre {
        color: var(--text-primary);
        background: var(--bg-elevated);
        border: 1px solid var(--separator);
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
        border-left: 3px solid var(--separator);
        margin: 0.75em 0;
        padding: 0 1em;
        color: var(--text-secondary);
      }
      ul, ol { margin: 0.5em 0; padding-left: 1.5em; }
      li { margin: 0.25em 0; }
      hr { border: 0; border-top: 1px solid var(--separator); margin: 1.2em 0; }
      table { border-collapse: collapse; margin: 0.75em 0; }
      th, td { border: 1px solid var(--separator); padding: 6px 10px; }
      th { background: var(--surface); }
      img { max-width: 100%; height: auto; }

      .hl-keyword { color: var(--hl-keyword); }
      .hl-string { color: var(--hl-string); }
      .hl-comment { color: var(--hl-comment); font-style: italic; }
      .hl-number { color: var(--hl-number); }
      .hl-tag { color: var(--hl-tag); }
      .hl-attr { color: var(--hl-attr); }
      .hl-type { color: var(--hl-type); }
      .hl-annotation { color: var(--hl-annotation); }
      .hl-punctuation { color: var(--hl-punctuation); }
    </style>
    </head>
    <body>
    \(body)
    </body>
    </html>
    """
}
