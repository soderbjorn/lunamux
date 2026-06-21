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
    @State private var theme: Client.ResolvedTheme?

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
                theme = central
            } else if let client = ConnectionHolder.shared.client,
               let config = try? await client.fetchThemeConfig() {
                let isDark = UITraitCollection.current.userInterfaceStyle == .dark
                theme = config.resolve(systemIsDark: isDark)
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
                diffHtml = buildDiffHtml(result, theme: theme)
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

private func buildDiffHtml(_ result: Client.WindowEnvelope.GitDiffResult, theme: Client.ResolvedTheme? = nil) -> String {
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

    // The flat ResolvedTheme already encodes a single resolved appearance, so
    // there is no per-pane scheme map or media-query split here: a config is
    // resolved to one theme for the host's current appearance upstream. Fall
    // back to the default theme resolved for the current appearance.
    let t: Client.ResolvedTheme = theme ?? {
        let isDark = UITraitCollection.current.userInterfaceStyle == .dark
        return Client.ThemeConfigKt.defaultThemeConfig().resolve(systemIsDark: isDark)
    }()
    let c = { (v: Int64) -> String in Client.ColorMathKt.argbToCss(argb: v) }

    // Emit the flat `--t-*` token vars (matching the web client), plus the
    // diff-specific aliases the stylesheet below references.
    let cssBlock = """
      :root {
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
      }
      .hl-keyword     { color: var(--t-syn-keyword); }
      .hl-string      { color: var(--t-syn-string); }
      .hl-number      { color: var(--t-syn-number); }
      .hl-comment     { color: var(--t-syn-comment); font-style: italic; }
      .hl-function    { color: var(--t-syn-function); }
      .hl-type        { color: var(--t-syn-type); }
      .hl-operator    { color: var(--t-syn-operator); }
      .hl-constant    { color: var(--t-syn-constant); }
      .hl-tag         { color: var(--t-syn-keyword); }
      .hl-attr        { color: var(--t-syn-function); }
      .hl-annotation  { color: var(--t-syn-function); }
      .hl-punctuation { color: var(--t-syn-operator); }
    """

    return """
    <!DOCTYPE html>
    <html>
    <head>
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <style>
      \(cssBlock)
      * { margin: 0; padding: 0; box-sizing: border-box; }
      html, body {
        background: var(--t-bg);
        color: var(--t-text);
        font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        font-size: 12px;
        line-height: 1.5;
      }
      .line {
        display: flex;
        white-space: pre;
        padding: 0 8px;
        min-height: 20px;
        border-bottom: 1px solid var(--t-border);
      }
      .line.add { background: var(--t-add-bg); }
      .line.del { background: var(--t-surface-alt); }
      .ln {
        display: inline-block;
        width: 40px;
        text-align: right;
        padding-right: 8px;
        color: var(--t-text-dim);
        user-select: none;
        flex-shrink: 0;
      }
      .prefix {
        display: inline-block;
        width: 16px;
        flex-shrink: 0;
        color: var(--t-text-dim);
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
