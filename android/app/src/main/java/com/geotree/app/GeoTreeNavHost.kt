package com.geotree.app

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.geotree.app.feature.dashboard.DashboardScreen
import com.geotree.app.feature.leafassessment.AssessmentDetailScreen
import com.geotree.app.feature.leafassessment.LeafAssessmentScreen
import com.geotree.app.feature.locator.LocatorScreen
import com.geotree.app.feature.login.LoginScreen
import com.geotree.app.feature.settings.SettingsScreen
import com.geotree.app.feature.splash.SplashDestination
import com.geotree.app.feature.splash.SplashScreen
import com.geotree.app.feature.tagtree.TagTreeScreen
import com.geotree.app.feature.treedetail.TreeDetailScreen

private object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    /** Login with an optional re-authentication flag ("Sign In to Sync" with an expired session). */
    const val LOGIN_PATTERN = "login?reauth={reauth}"
    const val REAUTH = "login?reauth=true"

    /** Authenticated shell graph: Dashboard | Map | Settings with a persistent bottom bar. */
    const val MAIN = "main"
    const val DASHBOARD = "dashboard"
    const val MAP = "map"
    const val SETTINGS = "settings"

    const val TAG_TREE = "tag_tree"
    const val TREE_DETAIL = "tree/{id}"
    fun treeDetail(id: String) = "tree/$id"

    const val LEAF_ASSESSMENT = "tree/{id}/leaf"
    fun leafAssessment(id: String) = "tree/$id/leaf"

    /** One scan by its stable id: the result page right after a scan, or a read-only history entry. */
    const val ASSESSMENT = "assessment/{assessmentId}?result={result}"
    fun assessment(assessmentId: String, asResult: Boolean = false) = "assessment/$assessmentId?result=$asResult"

    /** One-shot request on the Tree Detail entry: scroll to its history (from "View History"). */
    const val SCROLL_TO_HISTORY_KEY = "scroll_to_history"

    /** One-shot requests for the Map, stored on the MAIN graph entry (always on the back stack while signed in). */
    const val FOCUS_TREE_KEY = "focus_tree_id"
    const val SAVED_TREE_CODE_KEY = "saved_tree_code"
    const val NAVIGATE_TREE_KEY = "navigate_tree_id"
}

private enum class TopLevelTab(val route: String, val label: String) {
    Dashboard(Routes.DASHBOARD, "Dashboard"),
    Map(Routes.MAP, "Map"),
    Settings(Routes.SETTINGS, "Settings"),
}

/**
 * Bottom-navigation semantics: one copy of each tab, tab state saved and restored.
 * Dashboard is the shell's start destination, so it always stays at the bottom of the stack.
 */
private fun NavController.navigateToTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(Routes.DASHBOARD) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun GeoTreeNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = TopLevelTab.entries.any { it.route == destination?.route }

    // Screens own their status-bar insets (the map draws edge-to-edge); the bar owns the bottom.
    Scaffold(
        bottomBar = { if (showBottomBar) GeoTreeBottomBar(destination) { navController.navigateToTab(it.route) } },
        contentWindowInsets = WindowInsets(0),
        modifier = modifier,
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SPLASH,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.SPLASH) {
                SplashScreen(onReady = { next ->
                    val route = if (next == SplashDestination.Main) Routes.MAIN else Routes.LOGIN
                    navController.navigate(route) { popUpTo(Routes.SPLASH) { inclusive = true } }
                })
            }
            composable(
                Routes.LOGIN_PATTERN,
                arguments = listOf(navArgument("reauth") { type = NavType.BoolType; defaultValue = false }),
            ) { entry ->
                val reauth = entry.arguments?.getBoolean("reauth") == true
                LoginScreen(
                    reauth = reauth,
                    // Re-sign-in returns to where the user was; the shell and its state are untouched.
                    onBack = if (reauth) ({ navController.popBackStack() }) else null,
                    onSignedIn = {
                        if (reauth) navController.popBackStack()
                        else navController.navigate(Routes.MAIN) { popUpTo(Routes.LOGIN_PATTERN) { inclusive = true } }
                    },
                )
            }
            navigation(route = Routes.MAIN, startDestination = Routes.DASHBOARD) {
                composable(Routes.DASHBOARD) {
                    DashboardScreen(
                        onTagTree = { navController.navigate(Routes.TAG_TREE) },
                        onOpenMap = { navController.navigateToTab(Routes.MAP) },
                        onOpenTree = { id -> navController.navigate(Routes.treeDetail(id)) },
                        onSignInToSync = { navController.navigate(Routes.REAUTH) },
                    )
                }
                composable(Routes.MAP) { entry ->
                    val handle = remember(entry) { navController.mainHandle() }
                    LocatorScreen(
                        focusTreeId = handle.getStateFlow<String?>(Routes.FOCUS_TREE_KEY, null),
                        savedTreeCode = handle.getStateFlow<String?>(Routes.SAVED_TREE_CODE_KEY, null),
                        navigateTreeId = handle.getStateFlow<String?>(Routes.NAVIGATE_TREE_KEY, null),
                        onFocusConsumed = {
                            handle[Routes.FOCUS_TREE_KEY] = null
                            handle[Routes.SAVED_TREE_CODE_KEY] = null
                        },
                        onNavigateConsumed = { handle[Routes.NAVIGATE_TREE_KEY] = null },
                        onTagTree = { navController.navigate(Routes.TAG_TREE) },
                        onOpenTree = { id -> navController.navigate(Routes.treeDetail(id)) },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onSignedOut = { navController.navigate(Routes.LOGIN) { popUpTo(Routes.MAIN) { inclusive = true } } },
                        onSignInToSync = { navController.navigate(Routes.REAUTH) },
                        onOpenMap = { navController.navigateToTab(Routes.MAP) },
                    )
                }
            }
            composable(Routes.TAG_TREE) {
                TagTreeScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { id, code ->
                        // Show the new marker immediately: hand the tree to the Map tab.
                        navController.mainHandle().apply {
                            set(Routes.FOCUS_TREE_KEY, id)
                            set(Routes.SAVED_TREE_CODE_KEY, code)
                        }
                        navController.popBackStack()
                        navController.navigateToTab(Routes.MAP)
                    },
                )
            }
            composable(Routes.TREE_DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val scrollToHistory by entry.savedStateHandle.getStateFlow(Routes.SCROLL_TO_HISTORY_KEY, false).collectAsStateWithLifecycle()
                TreeDetailScreen(
                    treeId = requireNotNull(entry.arguments?.getString("id")),
                    onBack = { navController.popBackStack() },
                    onCenterOnMap = { id ->
                        navController.mainHandle()[Routes.FOCUS_TREE_KEY] = id
                        navController.popBackStack()
                        navController.navigateToTab(Routes.MAP)
                    },
                    onNavigate = { id ->
                        navController.mainHandle()[Routes.NAVIGATE_TREE_KEY] = id
                        navController.popBackStack()
                        navController.navigateToTab(Routes.MAP)
                    },
                    onAssessLeaf = { id -> navController.navigate(Routes.leafAssessment(id)) },
                    onOpenAssessment = { assessmentId -> navController.navigate(Routes.assessment(assessmentId)) },
                    scrollToHistory = scrollToHistory,
                    onScrolledToHistory = { entry.savedStateHandle[Routes.SCROLL_TO_HISTORY_KEY] = false },
                )
            }
            composable(Routes.LEAF_ASSESSMENT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                LeafAssessmentScreen(
                    treeId = requireNotNull(entry.arguments?.getString("id")),
                    onBack = { navController.popBackStack() },
                    // Tree Detail → Assess Leaf → Result: the capture screen is replaced, so Back returns to the tree.
                    onStarted = { assessmentId ->
                        navController.navigate(Routes.assessment(assessmentId, asResult = true)) {
                            popUpTo(Routes.LEAF_ASSESSMENT) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                Routes.ASSESSMENT,
                arguments = listOf(
                    navArgument("assessmentId") { type = NavType.StringType },
                    navArgument("result") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                AssessmentDetailScreen(
                    assessmentId = requireNotNull(entry.arguments?.getString("assessmentId")),
                    asResult = entry.arguments?.getBoolean("result") == true,
                    onBack = { navController.popBackStack() },
                    onViewHistory = {
                        navController.previousBackStackEntry?.savedStateHandle?.set(Routes.SCROLL_TO_HISTORY_KEY, true)
                        navController.popBackStack()
                    },
                    // "Assess again" is a new scan: its result page replaces this one.
                    onOpenAssessment = { newId ->
                        navController.navigate(Routes.assessment(newId, asResult = true)) {
                            popUpTo(Routes.ASSESSMENT) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

private fun NavController.mainHandle(): SavedStateHandle = getBackStackEntry(Routes.MAIN).savedStateHandle

@Composable
private fun GeoTreeBottomBar(destination: NavDestination?, onSelect: (TopLevelTab) -> Unit) {
    NavigationBar {
        TopLevelTab.entries.forEach { tab ->
            val selected = destination?.hierarchy?.any { it.route == tab.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = { TabIcon(tab) },
                label = { Text(tab.label) },
                modifier = Modifier.testTag("tab_${tab.route}"),
            )
        }
    }
}

@Composable
private fun TabIcon(tab: TopLevelTab) {
    when (tab) {
        TopLevelTab.Dashboard -> DrawableIcon(R.drawable.ic_dashboard)
        TopLevelTab.Map -> DrawableIcon(R.drawable.ic_map)
        TopLevelTab.Settings -> VectorIcon(Icons.Default.Settings)
    }
}

@Composable
private fun DrawableIcon(@DrawableRes id: Int) = Icon(painterResource(id), contentDescription = null)

@Composable
private fun VectorIcon(icon: ImageVector) = Icon(icon, contentDescription = null)
