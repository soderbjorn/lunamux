# Directional pane navigation (vim-style) — design

**Date:** 2026-06-22
**Scope:** Mac/Electron app (web frontend, `web/src/jsMain`)
**Status:** Approved

## Goal

Let the user move keyboard focus between panes in the active tab by
*direction* (left / down / up / right), vim-style, instead of the
toolkit's current linear *cycle*.

## Bindings

Directional pane focus, four directions, two key families that behave
identically:

| Keys            | Action                  |
| --------------- | ----------------------- |
| `⌃⌥H` / `⌃⌥←`  | Focus pane to the left  |
| `⌃⌥J` / `⌃⌥↓`  | Focus pane below        |
| `⌃⌥K` / `⌃⌥↑`  | Focus pane above        |
| `⌃⌥L` / `⌃⌥→`  | Focus pane to the right |

- Match condition: `ctrlKey && altKey && !metaKey && !shiftKey` and
  `event.code ∈ { KeyH, KeyJ, KeyK, KeyL, ArrowLeft, ArrowDown,
  ArrowUp, ArrowRight }`.
- **`event.code`, not `event.key`** — on macOS Option mutates
  `event.key` (⌥H → "˙"); `code` is layout/modifier independent.
- The toolkit already binds `⌃⌥←/→` to a horizontal pane *cycle* and
  `⌃⌥⇧←/→` to a tab cycle. We **replace** the pane cycle with
  directional movement on the same keys; `⌃⌥↑/↓` were unbound and
  become directional. The tab cycle (`⌃⌥⇧←/→`) is untouched because our
  `!shiftKey` guard lets it fall through.

## Override mechanism

The toolkit's `HotkeyRegistry` attaches its dispatcher to **`window` in
capture phase** (lazily, on its first `register()` during
`mountAppShell`). On a match it calls `preventDefault()` +
`stopPropagation()` — *not* `stopImmediatePropagation()`.

To win cleanly:

1. Install our `window` capture-phase `keydown` listener **before**
   `bootViaToolkitShell()` runs, so ours is earlier in window's
   same-target listener list.
2. On a handled chord, call `preventDefault()` +
   **`stopImmediatePropagation()`** so the toolkit's later
   same-target listener never runs.

Result: the handled chords reach only our handler; everything else
(including `⌃⌥⇧`-arrow tab cycle and plain terminal input) passes
through untouched.

## Geometry — source of truth is the DOM

Pane geometry is toolkit-owned (persisted in `LAYOUT_STATE`); the
server `WindowConfig` coordinates can be stale. So we compute "nearest
pane in direction" from the live on-screen rectangles:

- Candidate panes: `document.querySelectorAll(".dt-pane")` (only the
  active tab's panes are in the DOM). Each carries `data-pane-id`.
- Current pane: `.dt-pane.dt-pane-focused`, falling back to the active
  tab's `focusedPaneId`, then the first `.dt-pane`.
- Skip zero-size rects (hidden / `display:none`, e.g. siblings of a
  maximized pane).

### Pure selection function (testable, DOM-free)

```
enum Direction { LEFT, DOWN, UP, RIGHT }
data class Rect(left, top, right, bottom)   // center = mid of each axis

pickPaneInDirection(rects: List<Rect>, currentIndex: Int,
                    dir: Direction, wrap: Boolean): Int?
```

- **In-direction pass:** candidates strictly in `dir` of the current
  pane's center. Score = `primaryAxisDistance + 2 * perpendicularOffset`
  (favors the pane directly in line, then nearest). Pick min score.
- **Wrap pass** (only if no in-direction candidate and `wrap = true`):
  jump to the farthest pane on the opposite edge, best-aligned on the
  perpendicular axis. e.g. RIGHT-wrap → smallest center-x, tie-break by
  nearest center-y.
- Returns `null` for: < 2 panes, no current pane, or no distinct
  target (no-op).

## Focus dispatch

On a chosen target pane id, send the same pair a sidebar click uses:

```
SetFocusedPane(tabId = activeTabId, paneId = target)
RaisePane(paneId = target)
```

`activeTabId` from `latestWindowConfig?.activeTabId`. No-op if null.

## Gating & cheatsheet

- Installed only when `isElectronClient` (the Mac app), matching the
  ⌘T/⌘D precedent.
- Cheatsheet (`⌘/`): in the app, the two "Previous/Next window" cycle
  rows are replaced by four "Focus pane …" rows showing the **arrow**
  chords (`⌃⌥←/↓/↑/→`). The equivalent `⌃⌥HJKL` vim bindings also work
  but are intentionally omitted from the cheatsheet as a power-user
  feature. In the plain browser build (no override installed) the
  original cycle rows remain. Tab-cycle rows stay in both.

## Files

- **New** `web/src/jsMain/.../PaneNavigation.kt` — `Direction`, `Rect`,
  pure `pickPaneInDirection`, DOM glue, `installDirectionalPaneNav()`.
- `web/src/jsMain/.../main.kt` — call `installDirectionalPaneNav()`
  immediately before `bootViaToolkitShell(appEl)`, gated on
  `isElectronClient`.
- `web/src/jsMain/.../TermtasticHotkeysContent.kt` — directional rows +
  footer note.

## Out of scope

Cross-tab movement (stays within the active tab); pane resize/move;
removing the toolkit's now-shadowed cycle bindings (we suppress them at
dispatch instead).

## Known caveat

`⌃⌥`+arrows overlap macOS **VoiceOver** navigation when VoiceOver is
on. Pre-existing (the toolkit already used `⌃⌥`-arrows); not addressed.

## Testing

The web module has no test source set; adding a karma harness is
disproportionate here. `pickPaneInDirection` is kept pure for future
testability, but verification is manual in the running app
(`:electron:runDemo`): split panes into a grid and confirm each
direction + wrap behaves, including over a focused terminal.
