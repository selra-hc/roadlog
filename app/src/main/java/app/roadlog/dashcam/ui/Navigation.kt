package app.roadlog.dashcam.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.roadlog.dashcam.R
import app.roadlog.dashcam.dataStore
import app.roadlog.dashcam.ui.enums.Screen
import app.roadlog.dashcam.ui.models.VideoRecorderModel
import app.roadlog.dashcam.ui.screens.AboutScreen
import app.roadlog.dashcam.ui.screens.RecorderScreen
import app.roadlog.dashcam.ui.screens.SettingsScreen
import app.roadlog.dashcam.ui.screens.WelcomeScreen

const val SCALE_IN = 1.25f
const val DEBUG_SKIP_WELCOME = false;

// The 2 bottom-nav destinations (§8.3) — `Welcome` (one-time onboarding) and `About`
// (reached from within Settings) are deliberately excluded: they're full-screen takeovers
// or pushed on top without the bottom bar, not tabs of their own. The Recordings/Gallery
// tab (and its player screen) was removed entirely — it never worked and had no working
// path to fixing it in scope, so rather than leave a broken tab in the bar, the tab itself
// is gone; saved clips are still reachable from wherever they were saved to (the OS Files
// app / gallery for CUSTOM/MEDIA, or the save-success snackbar's "Open" action for
// INTERNAL) — see §9.2 in PLAN.md for the removal note.
private data class BottomNavDestination(
    val screen: Screen,
    val labelRes: Int,
    val icon: ImageVector,
)

private val bottomNavDestinations = listOf(
    BottomNavDestination(Screen.Recorder, R.string.ui_nav_record_label, Icons.Default.Videocam),
    BottomNavDestination(Screen.Settings, R.string.ui_nav_settings_label, Icons.Default.Settings),
)

@Composable
fun Navigation(
    videoRecorder: VideoRecorderModel = viewModel(),
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val settings = context
        .dataStore
        .data
        .collectAsState(initial = null)
        .value ?: return

    DisposableEffect(Unit) {
        videoRecorder.bindToService(context)

        onDispose {
            videoRecorder.unbindFromService(context)
        }
    }

    // One flat `NavHost` holding every route (simplest way to keep `About` pushed on top
    // of, rather than a sibling of, the 2 tab routes) — the bottom bar itself is just
    // conditionally rendered based on which destination is currently on top of the back
    // stack, rather than being wired to a separate nested nav graph. Since every route
    // lives directly in this single flat graph (no nested sub-graphs), a direct
    // `destination.route` comparison is equivalent to (and simpler than) walking
    // `NavDestination.hierarchy`, which exists to handle nested-graph cases we don't have.
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    // Hidden specifically while the Recorder tab is showing an active recording (§9.1's
    // full-bleed live preview should get the space the bar would otherwise take) — not
    // hidden globally just because a recording happens to be running in the background,
    // since the user still needs the bar to navigate away from Settings.
    val showBottomBar = bottomNavDestinations.any { it.screen.route == currentRoute } &&
        !(currentRoute == Screen.Recorder.route && videoRecorder.isInRecording)

    Scaffold(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.screen.route,
                            onClick = {
                                navController.navigate(destination.screen.route) {
                                    // Avoid building up a huge back stack of tab switches —
                                    // pop back to the graph's start tab, save/restore each
                                    // tab's own state, and never push the same tab twice
                                    // (Compose Navigation's standard bottom-bar recipe).
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(destination.icon, contentDescription = null)
                            },
                            label = {
                                Text(stringResource(destination.labelRes))
                            },
                        )
                    }
                }
            }
        },
    ) { scaffoldPadding ->
        NavHost(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(scaffoldPadding),
            navController = navController,
            startDestination = if (settings.hasSeenOnboarding || DEBUG_SKIP_WELCOME) Screen.Recorder.route else Screen.Welcome.route,
        ) {
            composable(Screen.Welcome.route) {
                WelcomeScreen(
                    onNavigateToRecorderScreen = {
                        val mainHandler = ContextCompat.getMainExecutor(context)

                        mainHandler.execute {
                            navController.navigate(Screen.Recorder.route)
                        }
                    },
                )
            }
            composable(
                Screen.Recorder.route,
                enterTransition = {
                    when (initialState.destination.route) {
                        Screen.Welcome.route -> null
                        else -> scaleIn(initialScale = SCALE_IN) + fadeIn()
                    }
                },
                exitTransition = {
                    scaleOut(targetScale = SCALE_IN) + fadeOut(tween(durationMillis = 150))
                }
            ) {
                RecorderScreen(
                    videoRecorder = videoRecorder,
                    settings = settings,
                )
            }
            composable(
                Screen.Settings.route,
                enterTransition = {
                    scaleIn(initialScale = 1 / SCALE_IN) + fadeIn()
                },
                exitTransition = {
                    scaleOut(targetScale = 1 / SCALE_IN) + fadeOut(tween(durationMillis = 150))
                }
            ) {
                SettingsScreen(
                    onNavigateToAboutScreen = { navController.navigate(Screen.About.route) },
                    videoRecorder = videoRecorder,
                )
            }
            composable(
                Screen.About.route,
                enterTransition = {
                    scaleIn()
                },
                exitTransition = {
                    scaleOut() + fadeOut(tween(150))
                }
            ) {
                AboutScreen(
                    onBackNavigate = navController::popBackStack,
                )
            }
        }
    }
}
