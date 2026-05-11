package com.soul2.imageai

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.soul2.imageai.ui.navigation.NavRoutes
import com.soul2.imageai.ui.screens.HomeScreen
import com.soul2.imageai.ui.screens.WebViewScreen

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = NavRoutes.HOME
    ) {
        composable(NavRoutes.HOME) {
            HomeScreen(
                onNavigateToWebView = {
                    navController.navigate(NavRoutes.WEBVIEW)
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
