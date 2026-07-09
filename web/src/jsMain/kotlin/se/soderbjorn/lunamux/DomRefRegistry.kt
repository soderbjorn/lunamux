/**
 * Cached references to the layout's top-level DOM elements (tab bar,
 * terminal wrapper, sidebar, dividers, usage bar, app header). Set by
 * `start()` during initialisation; nullable so renderers can short-circuit
 * cleanly during early boot before the DOM is fully wired.
 *
 * Lives in the same package as [WebState] so unqualified references in
 * the rest of the web client continue to resolve.
 */
package se.soderbjorn.lunamux

import org.w3c.dom.HTMLElement

internal var tabBarEl: HTMLElement? = null
internal var terminalWrapEl: HTMLElement? = null
internal var sidebarEl: HTMLElement? = null
internal var usageBar: HTMLElement? = null
internal var usageBarDividerEl: HTMLElement? = null
internal var appHeaderEl: HTMLElement? = null
internal var headerDividerEl: HTMLElement? = null
