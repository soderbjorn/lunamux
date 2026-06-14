/**
 * Termtastic's pane → universal-section mapping.
 *
 * Each Darkness app declares the concrete panes it renders and the universal
 * [se.soderbjorn.darkness.core.Sections] role each one inherits from in the
 * active [se.soderbjorn.darkness.core.Theme]. The toolkit's resolvers
 * ([se.soderbjorn.darkness.core.resolveActiveTheme],
 * [se.soderbjorn.darkness.core.resolvePaneSchemes]) consume this map.
 *
 * Termtastic-specific panes (`diff`, `fileBrowser`, `git`) collapse onto the
 * universal `auxiliary` section so themes authored against the universal
 * vocabulary still look coherent without the pane authors having to touch
 * each individual scheme assignment.
 *
 * Sibling apps (notegrow) declare their own map at
 * `notegrow/.../main/AppPanes.kt`; the two maps share keys for panes that
 * exist in both apps (`sidebar`, `tabs`, `chrome`, `active`, `windows`,
 * `bottomBar`).
 *
 * @see se.soderbjorn.darkness.core.Sections
 * @see se.soderbjorn.darkness.core.resolveActiveTheme
 * @see se.soderbjorn.darkness.core.resolvePaneSchemes
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.core.Sections

/**
 * Termtastic's concrete-pane → universal-section map.
 *
 * Read by the toolkit when the active theme is resolved against this app's
 * pane set. Apps that don't render a particular pane simply omit it — the
 * toolkit ignores section assignments for unknown panes.
 *
 * Pane names match the strings termtastic already uses throughout its
 * code base (`sidebar`, `terminal`, `tabs`, …) so callers that look schemes
 * up by pane name (e.g. [sectionPalette]) need no translation step.
 *
 * @see Sections
 */
val termtasticPanes: Map<String, String> = mapOf(
    "terminal"    to Sections.Main,
    "sidebar"     to Sections.Sidebar,
    "tabs"        to Sections.Tabs,
    "chrome"      to Sections.Chrome,
    "active"      to Sections.Active,
    "windows"     to Sections.Windows,
    "diff"        to Sections.Auxiliary,
    "fileBrowser" to Sections.Auxiliary,
    "git"         to Sections.Auxiliary,
    "bottomBar"   to Sections.BottomBar,
)
