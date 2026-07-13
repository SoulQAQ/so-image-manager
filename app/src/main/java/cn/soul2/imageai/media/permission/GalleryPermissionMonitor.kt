package cn.soul2.imageai.media.permission

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GalleryPermissionMonitor(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    private val applicationContext = context.applicationContext
    private val mutableState = MutableStateFlow(resolve(canRequestAgain = true))

    val state: StateFlow<GalleryAccessState> = mutableState.asStateFlow()

    fun refresh(canRequestAgain: Boolean) {
        mutableState.value = resolve(canRequestAgain)
    }

    private fun resolve(canRequestAgain: Boolean): GalleryAccessState {
        val granted = GalleryPermissionPolicy.requiredPermissions(sdkInt)
            .filterTo(mutableSetOf()) { permission ->
                ContextCompat.checkSelfPermission(applicationContext, permission) ==
                    PackageManager.PERMISSION_GRANTED
            }
        return GalleryPermissionPolicy.resolve(sdkInt, granted, canRequestAgain)
    }
}
