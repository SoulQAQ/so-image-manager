package cn.soul2.imageai.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.soul2.imageai.R
import cn.soul2.imageai.media.permission.GalleryAccessState

@Composable
fun GalleryOnboardingScreen(
    deniedState: GalleryAccessState.Denied,
    isPermissionRecovery: Boolean,
    isPermissionRequestInFlight: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val requiresSettings = isPermissionRecovery && !deniedState.canRequestAgain
    val primaryLabel = when {
        !isPermissionRecovery -> R.string.gallery_permission_authorize
        requiresSettings -> R.string.gallery_permission_open_settings
        else -> R.string.gallery_permission_retry
    }
    val primaryAction = if (requiresSettings) onOpenAppSettings else onRequestPermission
    val primaryIcon = if (requiresSettings) Icons.Outlined.Settings else Icons.Outlined.PhotoLibrary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = stringResource(
                    if (isPermissionRecovery) {
                        R.string.gallery_permission_denied_title
                    } else {
                        R.string.gallery_permission_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(
                    when {
                        !isPermissionRecovery -> R.string.gallery_permission_description
                        requiresSettings -> R.string.gallery_permission_settings_description
                        else -> R.string.gallery_permission_retry_description
                    },
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = primaryAction,
                enabled = requiresSettings || !isPermissionRequestInFlight,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(primaryIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(primaryLabel))
            }
            if (isPermissionRecovery) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.gallery_permission_later))
                }
            }
        }
    }
}

@Composable
fun GalleryPartialAccessBanner(
    isPermissionRequestInFlight: Boolean,
    onRequestReselection: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.gallery_permission_partial),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onRequestReselection,
                enabled = !isPermissionRequestInFlight,
            ) {
                Text(stringResource(R.string.gallery_permission_reselect))
            }
        }
    }
}
