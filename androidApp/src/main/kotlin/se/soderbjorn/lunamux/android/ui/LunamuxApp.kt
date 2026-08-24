/**
 * Main app composable and navigation graph for the Lunamux Android app.
 *
 * Defines the [LunamuxApp] composable which sets up a Jetpack Navigation
 * [NavHost] with horizontal-slide transitions, except tree↔terminal which
 * cross-fades under a shared-element "dive" (the tapped overview card morphs
 * into the terminal's content box — see [DiveTransition]). Routes include
 * hosts selection, tree overview, terminal sessions, file browser (list and
 * content), and git (list and diff). All destinations are driven by URI-style
 * route parameters for pane IDs, session IDs, and file paths.
 *
 * On connection, fetches the user's theme config from the server, resolves it
 * to a flat [se.soderbjorn.lunula.core.ResolvedTheme], and provides it via
 * [LocalUiSettings] so that all screens can access the selected theme without
 * independent network calls.
 *
 * Called from [se.soderbjorn.lunamux.android.MainActivity] as the root
 * composable inside the Material theme.
 *
 * @see se.soderbjorn.lunamux.android.MainActivity
 * @see LocalUiSettings
 */
package se.soderbjorn.lunamux.android.ui

import android.content.Context
import android.net.Uri
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import se.soderbjorn.lunamux.android.data.AppLocalRepository
import se.soderbjorn.lunamux.android.net.ConnectionHolder
import se.soderbjorn.lunamux.android.LunamuxDarkColorScheme
import se.soderbjorn.lunamux.android.LunamuxLightColorScheme
import se.soderbjorn.lunamux.client.viewmodel.ThemeBackingViewModel
import se.soderbjorn.lunula.core.ThemeSnapshotV2
import se.soderbjorn.lunula.core.isColorLight
import se.soderbjorn.lunamux.WindowConfig

/**
 * Top-level navigation host for the Lunamux Android app.
 *
 * Sets up a Jetpack Navigation [NavHost] with horizontal-slide enter/exit
 * transitions so forward navigation slides right-to-left and back pops
 * left-to-right, matching the native "push" feel. Routes include:
 * - `hosts` -- [HostsScreen] (start destination)
 * - `tree` -- [TreeScreen]
 * - `terminal/{sessionId}` -- [TerminalScreen]
 * - `files/{paneId}[/{dirRelPath}]` -- [FileBrowserListScreen]
 * - `file/{paneId}/{relPath}` -- [FileBrowserContentScreen]
 * - `git/{paneId}[/{filePath}]` -- [GitListScreen] / [GitDiffScreen]
 *
 * Fetches the theme config from the server when a client connection is
 * available, resolves it for the current appearance, and provides the
 * resulting [se.soderbjorn.lunula.core.ResolvedTheme] via [LocalUiSettings]
 * so all descendant composables (sidebar screens, terminal, diff, file
 * browser) can access the user's selected theme.
 *
 * @param applicationContext the Android application context, forwarded to
 *   [HostsScreen] for repository instantiation.
 * @see se.soderbjorn.lunamux.android.MainActivity
 * @see LocalUiSettings
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LunamuxApp(applicationContext: Context) {
    // First-launch onboarding gate, sourced from the shared LocalRepository.
    // `showOnboarding` is null while local_state.json is still hydrating (render
    // nothing rather than flashing the host list), then resolves to true on a
    // fresh install or false once the walkthrough has been completed.
    val repository = remember { AppLocalRepository.instance }
    val onboardingScope = rememberCoroutineScope()
    val localState by repository.state.collectAsState()
    val showOnboarding = localState?.let { !it.onboardingSeen }
    when (showOnboarding) {
        null -> return
        true -> {
            // Onboarding runs pre-connection, so there is no server theme yet.
            // Resolve the *default* ThemeSnapshotV2 — which is pinned to the
            // branded "Lunamux Dark"/"Lunamux Light" slots — and provide it via
            // the same MaterialTheme + LocalUiSettings wrappers the rest of the
            // app uses. Without these wrappers LocalUiSettings.current is null and
            // every Sidebar* accessor falls back to its neutral hardcoded constant
            // (and Material's green `secondary` can leak through) instead of the
            // on-brand navy surface + cyan accent.
            val onboardingIsDark = isSystemInDarkTheme()
            val onboardingTheme = remember(onboardingIsDark) {
                ThemeSnapshotV2().resolve(onboardingIsDark)
            }
            // Polarity from the resolved surface, not the raw OS flag, for the
            // same reason as the main scheme below: the slot the OS flag selects
            // may hold a theme of either polarity. Benign with today's default
            // snapshot (its slots match by construction) but keeps the base
            // scheme honest if the defaults ever change.
            MaterialTheme(
                colorScheme = if (!isColorLight(onboardingTheme.surface)) LunamuxDarkColorScheme else LunamuxLightColorScheme,
            ) {
                CompositionLocalProvider(LocalUiSettings provides onboardingTheme) {
                    OnboardingScreen(
                        onFinish = {
                            onboardingScope.launch { repository.setOnboardingSeen(true) }
                        },
                    )
                }
            }
            return
        }
        false -> Unit // fall through to the normal app below
    }

    val navController = rememberNavController()

    // The editable canonical theme selection (dual-slot + appearance). Created
    // once per connection; seeded from the server and then *owned* locally so
    // the in-app appearance/theme picker can mutate it and the whole app
    // repaints immediately. The active slot is resolved reactively below so a
    // device light/dark toggle switches *slots*, not just the current scheme's
    // light/dark variant.
    var themeVm by remember { mutableStateOf<ThemeBackingViewModel?>(null) }
    var themeSnapshot by remember { mutableStateOf<ThemeSnapshotV2?>(null) }
    // The active world's theme pair overlays the global snapshot below. Held
    // separately (rather than folded into `themeSnapshot`) so world switches
    // never touch the canonical/global selection the picker edits and persists.
    var worldConfig by remember { mutableStateOf<WindowConfig?>(null) }
    // Incremented on each successful connection to trigger a theme (re)load.
    var connectionGeneration by remember { mutableStateOf(0) }

    LaunchedEffect(connectionGeneration) {
        if (connectionGeneration > 0) {
            val vm = ConnectionHolder.client()?.let { ThemeBackingViewModel(it) }
            themeVm = vm
            vm?.load()
            // Collect for the lifetime of this connection so picker edits
            // (setAppearance / setActiveSlotTheme) repaint the app live.
            vm?.snapshot?.collect { themeSnapshot = it }
        }
    }

    // Track the server config so the active world's theme pair can repaint the
    // whole app when the user switches worlds. Runs for the lifetime of the
    // connection alongside the theme collector above.
    LaunchedEffect(connectionGeneration) {
        if (connectionGeneration > 0) {
            ConnectionHolder.client()?.windowState?.config?.collect { worldConfig = it }
        }
    }

    // Resolve the active slot for the current system appearance. Re-runs when
    // the snapshot, the active world's theme pair, or the system dark-mode flag
    // changes, so the provided ResolvedTheme always reflects the correct
    // light/dark theme slot — overlaid with the active world's pair when one
    // exists (a purely client-side, non-persisted override).
    //
    // The hosts list is the NavHost start destination, so it renders before any
    // server connection exists and themeSnapshot is still null. Fall back to the
    // *default* ThemeSnapshotV2 — pinned to the branded "Lunamux Dark"/"Lunamux
    // Light" slots — rather than leaving LocalUiSettings null. A null theme would
    // make every Sidebar* accessor drop to its neutral hardcoded constant (and
    // let Material's green `secondary` leak through) instead of the on-brand navy
    // surface + cyan accent. This mirrors the onboarding path above.
    val systemIsDark = isSystemInDarkTheme()
    val effectiveSnapshot = remember(themeSnapshot, worldConfig) {
        (themeSnapshot ?: ThemeSnapshotV2()).withActiveWorldTheme(worldConfig)
    }
    val theme = remember(effectiveSnapshot, systemIsDark) {
        effectiveSnapshot.resolve(systemIsDark)
    }

    // Pick the light/dark base Material scheme from the *resolved palette*, not
    // the appearance toggle. The toggle (Auto/Dark/Light) only selects which
    // slot of the theme pair `resolve` painted; either slot may legitimately
    // hold a theme of either polarity (a dark theme in the light slot is a
    // supported configuration — see ThemeBackingViewModel.activeSlotIsDark /
    // issue #97). Keying the base scheme off the toggle let LunamuxLightScheme's
    // un-overridden slots (`background` #F5F5F7, `onBackground` #1C1C1E, plus
    // every M3 default light value) paint light-grey chrome and dark-on-dark
    // text over a dark palette. Deriving polarity from the surface the scheme
    // actually paints keeps base scheme, overrides and palette consistent — the
    // same fix the web shell (applyAppearanceClass) and iOS (backgroundIsDark)
    // use. Falls back correctly before the snapshot loads because the default
    // ThemeSnapshotV2 resolves an on-brand dark surface.
    val effectiveDark = !isColorLight(theme.surface)

    // Re-validate the `/window` connection every time the app returns to
    // the foreground. While the phone sleeps (or the app sits in the
    // background) the OS can kill the TCP connection without an error, so
    // the socket's read loop hangs forever and the tree/state UI silently
    // stops updating. ConnectionHolder kicks the connection only when it
    // has actually been quiet for longer than the server's state-poll
    // cadence allows, so quick app switches don't churn the socket.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                ConnectionHolder.refreshAfterResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Overlay the Material colour scheme's surface-family + on-surface slots
    // with the *resolved theme*, so popup chrome that reads those slots —
    // dropdown/overflow/context menus, the world switcher menu — matches the
    // user's selected theme instead of always painting the fixed brand navy.
    // Only these slots are overridden; the rest of the brand scheme (primary,
    // error, etc.) is preserved. `surfaceContainer*` is what Material 3 uses for
    // a menu's background; `surface`/`onSurface`/`onSurfaceVariant` cover older
    // paths and the item text/icon colours.
    val baseScheme = if (effectiveDark) LunamuxDarkColorScheme else LunamuxLightColorScheme
    val colorScheme = remember(baseScheme, theme) {
        baseScheme.copy(
            surface = Color(theme.surface.toInt()),
            surfaceContainer = Color(theme.surfaceAlt.toInt()),
            surfaceContainerHigh = Color(theme.surfaceAlt.toInt()),
            surfaceContainerHighest = Color(theme.surfaceAlt.toInt()),
            onSurface = Color(theme.text.toInt()),
            onSurfaceVariant = Color(theme.textDim.toInt()),
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
    ) {
      CompositionLocalProvider(LocalUiSettings provides theme) {
       // The dive transition's shared-element overlay lives at this layout; the
       // scope is published so the two ends (overview card, terminal box) can
       // attach diveSharedBounds without threading receivers through screens.
       SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
        NavHost(
            navController = navController,
            startDestination = "hosts",
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(durationMillis = 260),
                )
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it / 4 },
                    animationSpec = tween(durationMillis = 260),
                )
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it / 4 },
                    animationSpec = tween(durationMillis = 260),
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(durationMillis = 260),
                )
            },
        ) {
            composable("hosts") {
                HostsScreen(
                    applicationContext = applicationContext,
                    onConnected = {
                        connectionGeneration++
                        navController.navigate("tree")
                    },
                    onOpenNews = { navController.navigate("news") },
                )
            }
            composable(
                "tree",
                // Toward/from the terminal the tree must not slide — a sliding
                // source/target warps the shared-element flight path — so those
                // legs cross-fade and the dive overlay carries the motion.
                // Returning null falls back to the NavHost-level slides for
                // every other neighbor (files/git/news).
                exitTransition = {
                    if (targetState.destination.route?.startsWith("terminal/") == true) {
                        fadeOut(tween(durationMillis = 300))
                    } else {
                        null
                    }
                },
                popEnterTransition = {
                    if (initialState.destination.route?.startsWith("terminal/") == true) {
                        fadeIn(tween(durationMillis = 300))
                    } else {
                        null
                    }
                },
            ) {
                CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                TreeScreen(
                    themeVm = themeVm,
                    onOpenTerminal = { sessionId ->
                        navController.navigate("terminal/$sessionId")
                    },
                    onOpenFileBrowser = { paneId ->
                        navController.navigate("files/${Uri.encode(paneId)}")
                    },
                    onOpenGit = { paneId ->
                        navController.navigate("git/${Uri.encode(paneId)}")
                    },
                    onDisconnect = {
                        themeVm = null
                        themeSnapshot = null
                        worldConfig = null
                        navController.popBackStack("hosts", inclusive = false)
                    },
                    onOpenNews = { navController.navigate("news") },
                )
                }
            }
            composable("news") {
                NewsUpdatesScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = "terminal/{sessionId}",
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                // Fades on every leg: the dive overlay (shared bounds between the
                // overview card and the terminal box) carries the motion, and a
                // slide underneath it would warp the flight path.
                enterTransition = { fadeIn(tween(durationMillis = 300)) },
                exitTransition = { fadeOut(tween(durationMillis = 300)) },
                popEnterTransition = { fadeIn(tween(durationMillis = 300)) },
                popExitTransition = { fadeOut(tween(durationMillis = 300)) },
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                TerminalScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                )
                }
            }
            composable(
                route = "files/{paneId}",
                arguments = listOf(navArgument("paneId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val paneId = backStackEntry.arguments?.getString("paneId") ?: return@composable
                FileBrowserListScreen(
                    paneId = paneId,
                    dirRelPath = "",
                    onOpenDir = { child ->
                        navController.navigate("files/${Uri.encode(paneId)}/${Uri.encode(child)}")
                    },
                    onOpenFile = { relPath ->
                        navController.navigate("file/${Uri.encode(paneId)}/${Uri.encode(relPath)}")
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "files/{paneId}/{dirRelPath}",
                arguments = listOf(
                    navArgument("paneId") { type = NavType.StringType },
                    navArgument("dirRelPath") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val paneId = backStackEntry.arguments?.getString("paneId") ?: return@composable
                val dirRelPath = backStackEntry.arguments?.getString("dirRelPath") ?: ""
                FileBrowserListScreen(
                    paneId = paneId,
                    dirRelPath = dirRelPath,
                    onOpenDir = { child ->
                        navController.navigate("files/${Uri.encode(paneId)}/${Uri.encode(child)}")
                    },
                    onOpenFile = { relPath ->
                        navController.navigate("file/${Uri.encode(paneId)}/${Uri.encode(relPath)}")
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "file/{paneId}/{relPath}",
                arguments = listOf(
                    navArgument("paneId") { type = NavType.StringType },
                    navArgument("relPath") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val paneId = backStackEntry.arguments?.getString("paneId") ?: return@composable
                val relPath = backStackEntry.arguments?.getString("relPath") ?: return@composable
                FileBrowserContentScreen(
                    paneId = paneId,
                    relPath = relPath,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "git/{paneId}",
                arguments = listOf(navArgument("paneId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val paneId = backStackEntry.arguments?.getString("paneId") ?: return@composable
                GitListScreen(
                    paneId = paneId,
                    onOpenFile = { filePath ->
                        navController.navigate("git/${Uri.encode(paneId)}/${Uri.encode(filePath)}")
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "git/{paneId}/{filePath}",
                arguments = listOf(
                    navArgument("paneId") { type = NavType.StringType },
                    navArgument("filePath") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val paneId = backStackEntry.arguments?.getString("paneId") ?: return@composable
                val filePath = backStackEntry.arguments?.getString("filePath") ?: return@composable
                GitDiffScreen(
                    paneId = paneId,
                    filePath = filePath,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        }
       }
      }
    }
}
