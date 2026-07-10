package cn.soul2.imageai.ui.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import cn.soul2.imageai.R

enum class AppDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.nav_home, Icons.Outlined.Home),
    LIBRARY("library", R.string.nav_library, Icons.Outlined.PhotoLibrary),
    TASKS("tasks", R.string.nav_tasks, Icons.Outlined.Checklist),
    SETTINGS("settings", R.string.nav_settings, Icons.Outlined.Settings);

    companion object {
        val start: AppDestination = HOME
    }
}
