import SwiftUI
import UIKit
import WebKit
import Client

/// Renders a git diff for a single file in a WKWebView with syntax-highlighted
/// additions and deletions. Mirrors the pattern of `MarkdownContentView`.
struct GitDiffView: View {
    let paneId: String
    let filePath: String
    var onBack: () -> Void

    @State private var diffHtml: String?
    @State private var errorMessage: String?
    @State private var uiSettings: Client.UiSettings?

    private var fileName: String {
        filePath.components(separatedBy: "/").last ?? filePath
    }

    var body: some View {
        ZStack {
            if let html = diffHtml {
                DiffWebView(html: html)
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
        .task { await loadDiff() }
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

    private func loadDiff() async {
        guard let socket = ConnectionHolder.shared.windowSocket else {
            errorMessage = "Not connected"
            return
        }
        do {
            let reply = try await socket.gitDiff(paneId: paneId, filePath: filePath, timeoutMs: 10_000)
            if let result = reply as? Client.WindowEnvelope.GitDiffResult {
                diffHtml = buildDiffHtml(result, settings: uiSettings)
            } else if let error = reply as? Client.WindowEnvelope.GitError {
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

private struct DiffWebView: UIViewRepresentable {
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

// MARK: - Diff HTML builder

private func buildDiffHtml(_ result: Client.WindowEnvelope.GitDiffResult, settings: Client.UiSettings? = nil) -> String {
    var body = ""
    for hunk in result.hunks {
        for line in hunk.lines {
            let oldNo = line.oldLineNo.map { String(describing: $0) } ?? ""
            let newNo = line.newLineNo.map { String(describing: $0) } ?? ""
            let cls: String
            let prefix: String
            switch line.type {
            case .addition:
                cls = "add"
                prefix = "+"
            case .deletion:
                cls = "del"
                prefix = "-"
            default:
                cls = "ctx"
                prefix = " "
            }
            body += "<div class=\"line \(cls)\">"
            body += "<span class=\"ln\">\(oldNo)</span>"
            body += "<span class=\"ln\">\(newNo)</span>"
            body += "<span class=\"prefix\">\(prefix)</span>"
            body += "<span class=\"code\">\(line.content)</span>"
            body += "</div>\n"
        }
    }

    let effectiveTheme: Client.ColorScheme
    if let s = settings {
        effectiveTheme = s.schemeForPane(pane: "diff")
    } else {
        let themes = Client.ColorSchemesKt.recommendedColorSchemes
        let defaultName = Client.ColorSchemesKt.DEFAULT_THEME_NAME
        effectiveTheme = themes.first { $0.name == defaultName } ?? themes[0]
    }
    let c = { (v: Int64) -> String in Client.ColorMathKt.argbToCss(argb: v) }

    func diffVars(_ pal: Client.ResolvedPalette) -> String {
        return """
            --background: \(c(pal.surface.base));
            --surface: \(c(pal.surface.raised));
            --text-primary: \(c(pal.text.primary));
            --text-secondary: \(c(pal.text.secondary));
            --add-bg: \(c(pal.diff.addBg));
            --del-bg: \(c(pal.diff.removeBg));
            --ln-color: \(c(pal.text.tertiary));
            --separator: \(c(pal.border.subtle));
        """
    }

    func syntaxVars(_ pal: Client.ResolvedPalette) -> String {
        return """
            .hl-keyword  { color: \(c(pal.syntax.keyword)); }
            .hl-string   { color: \(c(pal.syntax.string)); }
            .hl-comment  { color: \(c(pal.syntax.comment)); font-style: italic; }
            .hl-number   { color: \(c(pal.syntax.number)); }
            .hl-type     { color: \(c(pal.syntax.type)); }
            .hl-function { color: \(c(pal.syntax.function)); }
            .hl-operator { color: \(c(pal.syntax.operator_)); }
            .hl-punctuation { color: \(c(pal.syntax.operator_)); }
        """
    }

    // When the user has explicitly chosen Dark or Light, emit only that
    // palette's CSS vars. When Auto, use media queries for both modes.
    let appearance = settings?.appearance ?? Client.Appearance.auto_
    let cssBlock: String
    switch appearance {
    case .dark:
        let pal = effectiveTheme.resolve(isDark: true)
        cssBlock = """
          :root {
            color-scheme: dark;
            \(diffVars(pal))
          }
          \(syntaxVars(pal))
        """
    case .light:
        let pal = effectiveTheme.resolve(isDark: false)
        cssBlock = """
          :root {
            color-scheme: light;
            \(diffVars(pal))
          }
          \(syntaxVars(pal))
        """
    default:
        let darkPal = effectiveTheme.resolve(isDark: true)
        let lightPal = effectiveTheme.resolve(isDark: false)
        cssBlock = """
          :root {
            color-scheme: light dark;
            \(diffVars(darkPal))
          }
          @media (prefers-color-scheme: light) {
            :root {
              \(diffVars(lightPal))
            }
          }
          \(syntaxVars(darkPal))
          @media (prefers-color-scheme: light) {
            \(syntaxVars(lightPal))
          }
        """
    }

    return """
    <!DOCTYPE html>
    <html>
    <head>
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <style>
      \(cssBlock)
      * { margin: 0; padding: 0; box-sizing: border-box; }
      html, body {
        background: var(--background);
        color: var(--text-primary);
        font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        font-size: 12px;
        line-height: 1.5;
      }
      .line {
        display: flex;
        white-space: pre;
        padding: 0 8px;
        min-height: 20px;
        border-bottom: 1px solid var(--separator);
      }
      .line.add { background: var(--add-bg); }
      .line.del { background: var(--del-bg); }
      .ln {
        display: inline-block;
        width: 40px;
        text-align: right;
        padding-right: 8px;
        color: var(--ln-color);
        user-select: none;
        flex-shrink: 0;
      }
      .prefix {
        display: inline-block;
        width: 16px;
        flex-shrink: 0;
        color: var(--text-secondary);
      }
      .code {
        flex: 1;
        overflow-x: auto;
      }
    </style>
    </head>
    <body>
    \(body)
    </body>
    </html>
    """
}
