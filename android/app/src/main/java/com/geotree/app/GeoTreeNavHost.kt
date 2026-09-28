package com.geotree.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.geotree.app.feature.locator.LocatorScreen
import com.geotree.app.feature.login.LoginScreen
import com.geotree.app.feature.splash.SplashDestination
import com.geotree.app.feature.splash.SplashScreen
import com.geotree.app.feature.tagtree.TagTreeScreen
import com.geotree.app.feature.treedetail.TreeDetailScreen

private object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val LOCATOR = "locator"
    const val TAG_TREE = "tag_tree"
    const val TREE_DETAIL = "tree/{id}"
    fun treeDetail(id: String) = "tree/$id"

    /** Handed back from Tag Tree / Tree Detail to the locator via SavedStateHandle. */
    const val FOCUS_TREE_KEY = "focus_tree_id"
    const val SAVED_TREE_CODE_KEY = "saved_tree_code"
}

@Composable
fun GeoTreeNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.SPLASH, modifier = modifier) {
        composable(Routes.SPLASH) {
            SplashScreen(onReady = { destination ->
                val route = if (destination == SplashDestination.Locator) Routes.LOCATOR else Routes.LOGIN
                navController.navigate(route) { popUpTo(Routes.SPLASH) { inclusive = true } }
            })
        }
        composable(Routes.LOGIN) {
            LoginScreen(onSignedIn = {
                navController.navigate(Routes.LOCATOR) { popUpTo(Routes.LOGIN) { inclusive = true } }
            })
        }
        composable(Routes.LOCATOR) { entry ->
            val handle = entry.savedStateHandle
            LocatorScreen(
                focusTreeId = handle.getStateFlow<String?>(Routes.FOCUS_TREE_KEY, null),
                savedTreeCode = handle.getStateFlow<String?>(Routes.SAVED_TREE_CODE_KEY, null),
                onFocusConsumed = {
                    handle[Routes.FOCUS_TREE_KEY] = null
                    handle[Routes.SAVED_TREE_CODE_KEY] = null
                },
                onTagTree = { navController.navigate(Routes.TAG_TREE) },
                onOpenTree = { id -> navController.navigate(Routes.treeDetail(id)) },
                onSignedOut = {
                    navController.navigate(Routes.LOGIN) { popUpTo(Routes.LOCATOR) { inclusive = true } }
                },
            )
        }
        composable(Routes.TAG_TREE) {
            TagTreeScreen(
                onBack = { navController.popBackStack() },
                onSaved = { id, code ->
                    navController.previousBackStackEntry?.savedStateHandle?.apply {
                        set(Routes.FOCUS_TREE_KEY, id)
                        set(Routes.SAVED_TREE_CODE_KEY, code)
                    }
                    navController.popBackStack()
                },
            )
        }
        composable(Routes.TREE_DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            TreeDetailScreen(
                treeId = requireNotNull(entry.arguments?.getString("id")),
                onBack = { navController.popBackStack() },
                onCenterOnMap = { id ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(Routes.FOCUS_TREE_KEY, id)
                    navController.popBackStack()
                },
            )
        }
    }
}
