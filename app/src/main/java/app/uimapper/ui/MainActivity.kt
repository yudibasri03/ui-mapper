package app.uimapper.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.uimapper.ui.screens.AppPickerScreen
import app.uimapper.ui.screens.HelpScreen
import app.uimapper.ui.screens.HomeScreen
import app.uimapper.ui.screens.ScreenDetailScreen
import app.uimapper.ui.screens.SessionDetailScreen
import app.uimapper.ui.screens.SessionsScreen
import app.uimapper.ui.screens.SettingsScreen
import app.uimapper.ui.theme.UiMapperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UiMapperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    UiMapperNavHost()
                }
            }
        }
    }
}

private const val ARG_SESSION_ID = "sessionId"
private const val ARG_SCREEN_ID = "screenId"
private const val NAV_ANIM_MS = 240

/**
 * Navigates only when [entry] is still the top of the back stack. This swallows double taps and
 * taps on a screen that is already animating out, which would otherwise push duplicate
 * destinations or pop past the start destination.
 */
private fun NavHostController.navigateFrom(entry: NavBackStackEntry, route: String) {
    if (currentBackStackEntry == entry) navigate(route)
}

private fun NavHostController.popFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry == entry) popBackStack()
}

/** Path arguments are URL-encoded here; navigation decodes them before they reach the screen. */
private fun NavHostController.openSession(entry: NavBackStackEntry, sessionId: String) {
    if (sessionId.isBlank()) return
    navigateFrom(entry, Routes.session(Uri.encode(sessionId)))
}

private fun NavHostController.openScreen(entry: NavBackStackEntry, sessionId: String, screenId: String) {
    if (sessionId.isBlank() || screenId.isBlank()) return
    navigateFrom(entry, Routes.screen(Uri.encode(sessionId), Uri.encode(screenId)))
}

@Composable
private fun UiMapperNavHost() {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(NAV_ANIM_MS),
                initialOffset = { it / 8 },
            ) + fadeIn(animationSpec = tween(NAV_ANIM_MS))
        },
        exitTransition = { fadeOut(animationSpec = tween(NAV_ANIM_MS)) },
        popEnterTransition = { fadeIn(animationSpec = tween(NAV_ANIM_MS)) },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(NAV_ANIM_MS),
                targetOffset = { it / 8 },
            ) + fadeOut(animationSpec = tween(NAV_ANIM_MS))
        },
    ) {
        composable(Routes.HOME) { entry ->
            HomeScreen(
                onPickApp = { nav.navigateFrom(entry, Routes.APPS) },
                onOpenSessions = { nav.navigateFrom(entry, Routes.SESSIONS) },
                onOpenSettings = { nav.navigateFrom(entry, Routes.SETTINGS) },
                onOpenHelp = { nav.navigateFrom(entry, Routes.HELP) },
                onOpenSession = { sessionId -> nav.openSession(entry, sessionId) },
            )
        }

        composable(Routes.APPS) { entry ->
            AppPickerScreen(
                onPicked = { nav.popFrom(entry) },
                onBack = { nav.popFrom(entry) },
            )
        }

        composable(Routes.SETTINGS) { entry ->
            SettingsScreen(onBack = { nav.popFrom(entry) })
        }

        composable(Routes.HELP) { entry ->
            HelpScreen(onBack = { nav.popFrom(entry) })
        }

        composable(Routes.SESSIONS) { entry ->
            SessionsScreen(
                onOpenSession = { sessionId -> nav.openSession(entry, sessionId) },
                onBack = { nav.popFrom(entry) },
            )
        }

        composable(
            route = Routes.SESSION,
            arguments = listOf(
                navArgument(ARG_SESSION_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            val sessionId = entry.arguments?.getString(ARG_SESSION_ID).orEmpty()
            SessionDetailScreen(
                sessionId = sessionId,
                onOpenScreen = { screenId -> nav.openScreen(entry, sessionId, screenId) },
                onBack = { nav.popFrom(entry) },
            )
        }

        composable(
            route = Routes.SCREEN,
            arguments = listOf(
                navArgument(ARG_SESSION_ID) { type = NavType.StringType },
                navArgument(ARG_SCREEN_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            val sessionId = entry.arguments?.getString(ARG_SESSION_ID).orEmpty()
            val screenId = entry.arguments?.getString(ARG_SCREEN_ID).orEmpty()
            ScreenDetailScreen(
                sessionId = sessionId,
                screenId = screenId,
                onOpenScreen = { nextScreenId -> nav.openScreen(entry, sessionId, nextScreenId) },
                onBack = { nav.popFrom(entry) },
            )
        }
    }
}
