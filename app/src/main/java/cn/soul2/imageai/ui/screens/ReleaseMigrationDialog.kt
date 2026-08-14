package cn.soul2.imageai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.soul2.imageai.backup.BackupPreflightResult
import cn.soul2.imageai.update.ReleaseMigrationState
import java.io.File

@Composable
fun ReleaseMigrationDialog(
    state: ReleaseMigrationState,
    onDismiss: () -> Unit,
    onCreateBackup: () -> Unit,
    onDownload: () -> Unit,
    onSaveApk: (File) -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("迁移到正式版") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Debug 版与正式版签名不同，需要先备份，再保存正式安装包并卸载重装。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state) {
                    is ReleaseMigrationState.Unavailable -> Text(state.reason)
                    ReleaseMigrationState.AwaitingBackup -> MigrationChecklist(0, null)
                    is ReleaseMigrationState.BackupSaved -> MigrationChecklist(1, state.summary)
                    is ReleaseMigrationState.Downloading -> {
                        MigrationChecklist(1, state.summary)
                        val progress = if (state.totalBytes > 0L) {
                            state.downloadedBytes.toFloat() / state.totalBytes.toFloat()
                        } else 0f
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        Text("正在下载 ${state.release.tagName}")
                    }
                    is ReleaseMigrationState.ApkReady -> MigrationChecklist(2, state.summary)
                    is ReleaseMigrationState.Complete -> MigrationChecklist(3, state.summary)
                    is ReleaseMigrationState.Failed -> {
                        state.summary?.let { MigrationChecklist(1, it) }
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                ReleaseMigrationState.AwaitingBackup -> Button(onClick = onCreateBackup) { Text("生成并保存备份") }
                is ReleaseMigrationState.BackupSaved -> Button(onClick = onDownload) { Text("下载正式版") }
                is ReleaseMigrationState.ApkReady -> Button(onClick = { onSaveApk(state.apk) }) { Text("保存安装包") }
                is ReleaseMigrationState.Failed -> Button(onClick = onRetry) { Text("重试") }
                else -> Unit
            }
        },
        dismissButton = {
            Row {
                if (state !is ReleaseMigrationState.Unavailable && state !is ReleaseMigrationState.AwaitingBackup) {
                    TextButton(onClick = onReset) { Text("重新开始") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun MigrationChecklist(completedStep: Int, summary: BackupPreflightResult?) {
    summary?.let {
        Text(
            "备份预检：${it.imageCount} 张图片，${it.analysisCount} 条分析，" +
                "${it.providerCount} 个供应方；${it.credentialReentryCount} 个凭据需重新填写。",
        )
    }
    val steps = listOf(
        "备份已保存并通过预检",
        "正式安装包已下载并校验证书",
        "安装包已保存到用户选择的位置",
        "卸载 Debug 版",
        "从文件管理器安装正式版",
        "恢复备份并重新填写 API Key",
    )
    steps.forEachIndexed { index, text ->
        Text("${if (index < completedStep) "✓" else "○"} $text")
    }
}
