/**
 * Appearance + theme picker bottom sheet for the Lunamux Android sessions view.
 *
 * Brings the Mac/Electron app's appearance toggle + theme manager to mobile in a
 * deliberately simple form: the user picks the appearance ([Appearance] Auto /
 * Light / Dark) and taps a theme thumbnail to assign it to the currently-active
 * slot. Long-pressing a theme opens a context menu to star / unstar it (issue
 * #107); the catalog is a single list with no "Dark"/"Light" headings, ordered
 * starred dark → starred light → unstarred dark → unstarred light. There is no
 * semantic-colour editing and no clone/delete.
 *
 * Every choice is routed through [ThemeBackingViewModel], which writes the same
 * canonical server selection the desktop writes ([se.soderbjorn.darkness.core.PersistKeys.THEME_V2_SELECTION]),
 * so the change persists and syncs to every connected client. The sheet stays
 * open after a change so the user can preview the live repaint behind it.
 *
 * Mirrors the structure of [LayoutSheet] (Material [ModalBottomSheet] + a native
 * [Canvas] miniature). The theme thumbnail [ThemeThumbnail] ports the token →
 * region mapping of the web `buildThemeThumb` silhouette.
 *
 * @see ThemeBackingViewModel
 * @see LayoutSheet
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.ResolvedTheme
import se.soderbjorn.darkness.core.Theme
import se.soderbjorn.darkness.core.allThemes
import se.soderbjorn.darkness.core.orderThemesForPicker
import se.soderbjorn.lunamux.client.viewmodel.ThemeBackingViewModel

/**
 * Number of full-span grid rows that precede the theme cards (the "Dark mode"
 * header and the appearance Auto/Light/Dark toggle). Added to a theme's index in
 * `orderedThemes` to get its flattened `LazyVerticalGrid` item index, used when
 * scrolling the active theme into view on open (issue #105).
 */
private const val LEADING_GRID_ITEMS = 2

/**
 * The appearance + theme picker bottom sheet.
 *
 * @param vm        the shared theme model; its [ThemeBackingViewModel.snapshot]
 *   drives the UI and its setters apply + persist each change.
 * @param onDismiss invoked when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSheet(
    vm: ThemeBackingViewModel,
    onDismiss: () -> Unit,
) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // On a tablet / large-screen device (≥ 600 dp wide, Android's standard
    // "expanded" breakpoint) a fixed 2-up grid capped at 560 dp would blow each
    // thumbnail up to half the width and waste most of the sheet — the owner's
    // "this wastes a lot of space, we could fit many more themes by making the
    // thumbnails smaller" feedback on issue #99. On wide screens the grid instead
    // switches to an adaptive layout that packs as many compact cards per row as
    // the width allows, and is given more vertical room so more rows are visible
    // at once; phones keep the familiar 2-up grid.
    val isWideScreen = LocalConfiguration.current.screenWidthDp >= 600
    val gridColumns = if (isWideScreen) GridCells.Adaptive(minSize = 132.dp) else GridCells.Fixed(2)
    val gridMaxHeight = if (isWideScreen) 900.dp else 560.dp

    // Single ordered list (issue #107): starred dark → starred light → unstarred
    // dark → unstarred light. `orderThemesForPicker` and the favorites set both
    // read the live snapshot, so re-ordering happens automatically after a star.
    val favorites = snapshot.favorites.toSet()
    val orderedThemes = orderThemesForPicker(allThemes(snapshot.customThemes), favorites)

    // Which slot a tap fills — whichever is *currently painted* (the appearance
    // preference, or the OS dark flag when Auto), exactly like clicking a card
    // in the Mac/Electron theme manager (issue #97). Only that slot's assigned
    // theme is highlighted.
    val systemIsDark = isSystemInDarkTheme()
    val activeIsDark = when (snapshot.appearance) {
        Appearance.Dark -> true
        Appearance.Light -> false
        Appearance.Auto -> systemIsDark
    }
    val activeThemeName = if (activeIsDark) snapshot.darkThemeName else snapshot.lightThemeName

    // Scroll the active theme into view the first time the sheet opens (issue
    // #105), so the currently-painted theme is visible instead of the grid
    // always starting at the top. The catalog may populate a frame after the
    // sheet appears, so the effect is keyed on the list size (0 → N) and a
    // one-shot [didScrollToActive] guard stops it from ever yanking the grid
    // back once the user has scrolled away. The two leading full-span rows (the
    // "Dark mode" header + the appearance toggle) offset every theme's grid
    // index by [LEADING_GRID_ITEMS].
    val gridState = rememberLazyGridState()
    var didScrollToActive by remember { mutableStateOf(false) }
    LaunchedEffect(orderedThemes.size, activeThemeName) {
        if (didScrollToActive || orderedThemes.isEmpty()) return@LaunchedEffect
        val index = orderedThemes.indexOfFirst { it.name == activeThemeName }
        if (index < 0) return@LaunchedEffect
        didScrollToActive = true
        gridState.scrollToItem(LEADING_GRID_ITEMS + index)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SidebarSurface,
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = gridColumns,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = gridMaxHeight)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            // The "Dark mode" header + appearance toggle live inside the grid as
            // full-span rows so they scroll together with the theme sections
            // rather than occupying a fixed strip above the scroll area.
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Dark mode",
                    color = SidebarTextBright,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 10.dp),
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (option in listOf(Appearance.Auto, Appearance.Light, Appearance.Dark)) {
                        AppearanceSegment(
                            label = option.label(),
                            selected = snapshot.appearance == option,
                            onClick = { scope.launch { vm.setAppearance(option) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            // One flat list, no section headings (issue #107). Tapping a card
            // assigns that theme to the currently-active slot (issue #97);
            // long-pressing opens the star / unstar context menu.
            items(orderedThemes, key = { it.name }) { theme ->
                ThemeCard(
                    theme = theme,
                    selected = theme.name == activeThemeName,
                    favorite = theme.name in favorites,
                    onClick = { scope.launch { vm.setActiveTheme(theme.name, systemIsDark) } },
                    onToggleFavorite = { scope.launch { vm.toggleFavorite(theme.name) } },
                )
            }
        }
    }
}

/**
 * A single Auto/Light/Dark segment in the appearance control. The selected
 * segment is filled with the theme accent (unambiguous on the dark surface);
 * the rest sit on the elevated surface. Themed to the resolved palette.
 *
 * @param label    the segment label.
 * @param selected whether this is the current appearance.
 * @param onClick  invoked when tapped.
 * @param modifier layout modifier (the row applies an equal `weight`).
 */
@Composable
private fun AppearanceSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SidebarAccent else SidebarSurfaceAlt)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) SidebarBackground else SidebarTextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * One theme card: the theme name above its [ThemeThumbnail]. When [selected] the
 * thumbnail is encircled with an accent ring (the assigned-theme highlight); when
 * [favorite] a filled star badge sits in the thumbnail's top-right corner.
 *
 * Tapping the card assigns the theme; long-pressing opens a small context menu
 * to star / unstar it (issue #107). The menu is anchored to the card.
 *
 * @param theme            the theme to render.
 * @param selected         whether this theme is bound to the active slot.
 * @param favorite         whether this theme is starred.
 * @param onClick          invoked when the card is tapped.
 * @param onToggleFavorite invoked when the context-menu star / unstar item is chosen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThemeCard(
    theme: Theme,
    selected: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = theme.name,
            color = if (selected) SidebarAccent else SidebarTextPrimary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.5f)
                .clip(RoundedCornerShape(6.dp))
                .then(
                    if (selected) {
                        Modifier.border(1.5.dp, SidebarAccent, RoundedCornerShape(6.dp))
                    } else {
                        Modifier
                    },
                ),
        ) {
            ThemeThumbnail(
                resolved = theme.resolve(),
                modifier = Modifier.fillMaxWidth().aspectRatio(1.5f),
            )
            if (favorite) {
                // Filled star badge, top-right, so favorites are recognisable at
                // a glance (they're also hoisted to the top of the list).
                Text(
                    text = "★",
                    color = SidebarAccent,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(if (favorite) "Unstar theme" else "Star theme") },
                    onClick = {
                        menuOpen = false
                        onToggleFavorite()
                    },
                )
            }
        }
    }
}

/**
 * Draws a miniature app silhouette coloured entirely from [resolved] — a tab
 * strip (with one active tab), a sidebar column, a focused pane with a titlebar
 * and a few syntax-coloured code lines, and a bottom accent strip. The token →
 * region mapping mirrors the web `buildThemeThumb`, so a card previews the real
 * app chrome at a glance.
 *
 * @param resolved the resolved palette to paint with.
 * @param modifier layout modifier (caller sets the aspect ratio).
 */
@Composable
fun ThemeThumbnail(
    resolved: ResolvedTheme,
    modifier: Modifier,
) {
    fun c(v: Long) = Color(v.toInt())
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Canvas background.
        drawRect(color = c(resolved.bg), size = Size(w, h))

        // Tab strip across the top.
        val tabH = h * 0.18f
        drawRect(color = c(resolved.surfaceAlt), size = Size(w, tabH))
        val tabW = w * 0.22f
        val tabPad = w * 0.02f
        val tabInsetY = tabH * 0.22f
        // Two inactive tabs then one active tab.
        for (i in 0 until 2) {
            val x = tabPad + i * (tabW + tabPad)
            drawRoundRect(
                color = c(resolved.textDim).copy(alpha = 0.5f),
                topLeft = Offset(x, tabInsetY),
                size = Size(tabW, tabH - tabInsetY * 2),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
        val activeX = tabPad + 2 * (tabW + tabPad)
        drawRoundRect(
            color = c(resolved.surface),
            topLeft = Offset(activeX, tabInsetY),
            size = Size(tabW, tabH - tabInsetY * 2),
            cornerRadius = CornerRadius(2f, 2f),
        )
        drawRoundRect(
            color = c(resolved.accent),
            topLeft = Offset(activeX, tabInsetY),
            size = Size(tabW, tabH - tabInsetY * 2),
            cornerRadius = CornerRadius(2f, 2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
        )

        // Body region below the tab strip.
        val bodyTop = tabH
        val bodyH = h - tabH

        // Sidebar column on the left.
        val sidebarW = w * 0.24f
        drawRect(
            color = c(resolved.surface),
            topLeft = Offset(0f, bodyTop),
            size = Size(sidebarW, bodyH),
        )
        // A few sidebar item bars.
        val lineH = bodyH * 0.07f
        for (i in 0 until 4) {
            val y = bodyTop + bodyH * 0.12f + i * (lineH * 2)
            drawRoundRect(
                color = c(resolved.textDim).copy(alpha = 0.7f),
                topLeft = Offset(sidebarW * 0.18f, y),
                size = Size(sidebarW * 0.64f, lineH),
                cornerRadius = CornerRadius(1.5f, 1.5f),
            )
        }

        // Focused pane filling the main area.
        val paneX = sidebarW + w * 0.05f
        val paneY = bodyTop + bodyH * 0.12f
        val paneW = w - paneX - w * 0.06f
        val paneH = bodyH * 0.7f
        drawRoundRect(
            color = c(resolved.surface),
            topLeft = Offset(paneX, paneY),
            size = Size(paneW, paneH),
            cornerRadius = CornerRadius(3f, 3f),
        )
        // Pane titlebar.
        val titleH = paneH * 0.22f
        drawRoundRect(
            color = c(resolved.surfaceAlt),
            topLeft = Offset(paneX, paneY),
            size = Size(paneW, titleH),
            cornerRadius = CornerRadius(3f, 3f),
        )
        drawRoundRect(
            color = c(resolved.textBright),
            topLeft = Offset(paneX + paneW * 0.08f, paneY + titleH * 0.32f),
            size = Size(paneW * 0.4f, titleH * 0.36f),
            cornerRadius = CornerRadius(1.5f, 1.5f),
        )
        // A few syntax-coloured code lines in the pane body.
        val codeColors = listOf(
            c(resolved.synKeyword),
            c(resolved.synString),
            c(resolved.text),
            c(resolved.synFunction),
        )
        val codeLineH = paneH * 0.09f
        for (i in 0 until 4) {
            val y = paneY + titleH + paneH * 0.12f + i * (codeLineH * 1.7f)
            val lineW = paneW * (0.7f - i * 0.1f)
            drawRoundRect(
                color = codeColors[i],
                topLeft = Offset(paneX + paneW * 0.08f, y),
                size = Size(lineW, codeLineH),
                cornerRadius = CornerRadius(1.5f, 1.5f),
            )
        }
        // Accent focus ring on the pane.
        drawRoundRect(
            color = c(resolved.accent),
            topLeft = Offset(paneX, paneY),
            size = Size(paneW, paneH),
            cornerRadius = CornerRadius(3f, 3f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
        )

        // Bottom accent strip.
        drawRect(
            color = c(resolved.accent),
            topLeft = Offset(0f, h - h * 0.05f),
            size = Size(w, h * 0.05f),
        )
    }
}

/**
 * Human-readable label for an [Appearance] option, shown on the toggle chips.
 *
 * @return "Auto", "Light", or "Dark".
 */
private fun Appearance.label(): String = when (this) {
    Appearance.Auto -> "Auto"
    Appearance.Light -> "Light"
    Appearance.Dark -> "Dark"
}
