package cn.soul2.imageai.ui.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.soul2.imageai.media.permission.GalleryAccessState
import cn.soul2.imageai.ui.onboarding.GalleryOnboardingScreen
import cn.soul2.imageai.ui.onboarding.GalleryPartialAccessBanner
import cn.soul2.imageai.ui.screens.HomeScreen
import cn.soul2.imageai.ui.screens.LibraryScreen
import cn.soul2.imageai.ui.screens.SettingsScreen
import cn.soul2.imageai.ui.screens.TasksScreen

@Composable
fun SoImageManagerApp(
    navController: NavHostController = rememberNavController(),
    galleryAccessState: GalleryAccessState = GalleryAccessState.Full,
    showGalleryOnboarding: Boolean = false,
    isGalleryPermissionRecovery: Boolean = false,
    isGalleryPermissionRequestInFlight: Boolean = false,
    onRequestGalleryPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onDismissGalleryOnboarding: () -> Unit = {},
    onRequestGalleryReselection: () -> Unit = {},
) {
    val deniedState = galleryAccessState as? GalleryAccessState.Denied
    if (showGalleryOnboarding && deniedState != null) {
        GalleryOnboardingScreen(
            deniedState = deniedState,
            isPermissionRecovery = isGalleryPermissionRecovery,
            isPermissionRequestInFlight = isGalleryPermissionRequestInFlight,
            onRequestPermission = onRequestGalleryPermission,
            onOpenAppSettings = onOpenAppSettings,
            onDismiss = onDismissGalleryOnboarding,
        )
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppDestination.start.route

    Scaffold(
        bottomBar = {
            NavigationBar(Modifier.testTag("bottom_navigation")) {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(AppDestination.start.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                        modifier = Modifier.testTag("destination_${destination.route}"),
                    )
                }
            }
        },
    ) { contentPadding ->
        Column(Modifier.padding(contentPadding)) {
            if (galleryAccessState is GalleryAccessState.Partial) {
                GalleryPartialAccessBanner(
                    isPermissionRequestInFlight = isGalleryPermissionRequestInFlight,
                    onRequestReselection = onRequestGalleryReselection,
                )
            }
            NavHost(
                navController = navController,
                startDestination = AppDestination.start.route,
                modifier = Modifier.weight(1f),
            ) {
                composable(AppDestination.HOME.route) { HomeScreen() }
                composable(AppDestination.LIBRARY.route) { LibraryScreen() }
                composable(AppDestination.TASKS.route) { TasksScreen() }
                composable(AppDestination.SETTINGS.route) { SettingsScreen() }
            }
        }
    }
}
