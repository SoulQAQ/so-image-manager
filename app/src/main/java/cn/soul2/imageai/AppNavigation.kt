package cn.soul2.imageai

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.soul2.imageai.ui.navigation.NavRoutes
import cn.soul2.imageai.ui.screens.GalleryScreenDetailBridge
import cn.soul2.imageai.ui.screens.GalleryScreen
import cn.soul2.imageai.ui.screens.SettingsScreen
import cn.soul2.imageai.ui.screens.WebViewScreen

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = NavRoutes.GALLERY
    ) {
        composable(NavRoutes.GALLERY) {
            GalleryScreen(
                onOpenDetail = { index ->
                    navController.navigate(NavRoutes.imageDetail(index))
                },
                onNavigateToSettings = {
                    navController.navigate(NavRoutes.SETTINGS)
                },
                onNavigateToWebView = {
                    navController.navigate(NavRoutes.WEBVIEW)
                }
            )
        }

        composable(
            route = NavRoutes.IMAGE_DETAIL,
            arguments = listOf(navArgument("index") { defaultValue = 0 })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: 0
            GalleryScreenDetailBridge(
                index = index,
                onBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(NavRoutes.WEBVIEW) {
            WebViewScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
