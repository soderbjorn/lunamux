/**
 * Main app composable and navigation graph for the Lunamux Android app.
 *
 * Defines the [LunamuxApp] composable which sets up a Jetpack Navigation
 * [NavHost] with horizontal-slide transitions. Routes include hosts selection,
 * tree overview, terminal sessions, file browser (list and content), and git
 * (list and diff). All destinations are driven by URI-style route parameters
 * for pane IDs, session IDs, and file paths.
 *
 * On connection, fetches the user's theme config from the server, resolves it
 * to a flat [se.soderbjorn.darkness.core.ResolvedTheme], and provides it via
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
import androidx.compose.animation.core.tween
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
import se.soderbjorn.lunamux.android.data.AppLocalRepository
import se.soderbjorn.lunamux.android.net.ConnectionHolder
import se.soderbjorn.lunamux.android.LunamuxDarkColorScheme
import se.soderbjorn.lunamux.android.LunamuxLightColorScheme
import se.soderbjorn.lunamux.client.viewmodel.ThemeBackingViewModel
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.ThemeSnapshotV2

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
 * resulting [se.soderbjorn.darkness.core.ResolvedTheme] via [LocalUiSettings]
 * so all descendant composables (sidebar screens, terminal, diff, file
 * browser) can access the user's selected theme.
 *
 * @param applicationContext the Android application context, forwarded to
 *   [HostsScreen] for repository instantiation.
 * @see se.soderbjorn.lunamux.android.MainActivity
 * @see LocalUiSettings
 */
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
            // every Sidebar* accessor falls back to its hardcoded (green) constant
            // instead of the on-brand cyan accent.
            val onboardingIsDark = isSystemInDarkTheme()
            val onboardingTheme = remember(onboardingIsDark) {
                ThemeSnapshotV2().resolve(onboardingIsDark)
            }
            MaterialTheme(
                colorScheme = if (onboardingIsDark) LunamuxDarkColorScheme else LunamuxLightColorScheme,
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

    // Resolve the active slot for the current system appearance. Re-runs when
    // either the snapshot or the system dark-mode flag changes, so the provided
    // ResolvedTheme always reflects the correct light/dark theme slot.
    val systemIsDark = isSystemInDarkTheme()
    val theme = remember(themeSnapshot, systemIsDark) {
        themeSnapshot?.resolve(systemIsDark)
    }

    // Drive the Material colour scheme from the *chosen* appearance (resolved
    // against the system flag), not the raw OS setting, so the Material chrome
    // (bottom sheets, dialogs, chips) flips light/dark together with the themed
    // surfaces. Without this the app looks half-switched when the user forces an
    // appearance that differs from the device's. Falls back to the OS flag until
    // the snapshot has loaded.
    val effectiveDark = when (themeSnapshot?.appearance) {
        Appearance.Dark -> true
        Appearance.Light -> false
        Appearance.Auto, null -> systemIsDark
    }

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

    MaterialTheme(
        colorScheme = if (effectiveDark) LunamuxDarkColorScheme else LunamuxLightColorScheme,
    ) {
      CompositionLocalProvider(LocalUiSettings provides theme) {
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
            composable("tree") {
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
                        navController.popBackStack("hosts", inclusive = false)
                    },
                    onOpenNews = { navController.navigate("news") },
                )
            }
            composable("news") {
                NewsUpdatesScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = "terminal/{sessionId}",
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                TerminalScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                )
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
