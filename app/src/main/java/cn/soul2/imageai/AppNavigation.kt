package cn.soul2.imageai

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cn.soul2.imageai.ui.navigation.NavRoutes
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
                onNavigateToSettings = {
                    navController.navigate(NavRoutes.SETTINGS)
                },
                onNavigateToWebView = {
                    navController.navigate(NavRoutes.WEBVIEW)
                }
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
