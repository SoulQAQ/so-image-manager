package cn.soul2.imageai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageDetailScreen(
    image: SelectedImage,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图片详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Surface(shape = MaterialTheme.shapes.medium) {
                AsyncImage(
                    model = image.uri,
                    contentDescription = image.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                )
            }

            Spacer(Modifier.height(12.dp))

            Text("文件名", style = MaterialTheme.typography.labelMedium)
            Text(image.name, style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(12.dp))

            Text("标注状态", style = MaterialTheme.typography.labelMedium)
            Text(
                when (image.tagStatus) {
                    TagStatus.IDLE -> "未标注"
                    TagStatus.PROCESSING -> "标注中"
                    TagStatus.SUCCESS -> "已完成"
                    TagStatus.FAILED -> "失败"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            if (!image.aiCaption.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("模型原始描述", style = MaterialTheme.typography.labelMedium)
                Text(image.aiCaption, style = MaterialTheme.typography.bodyMedium)
            }

            if (image.aiTags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("标签", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val chunks = image.aiTags.chunked(3)
                    chunks.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { tag ->
                                AssistChip(onClick = {}, label = { Text(tag) })
                            }
                        }
                    }
                }
            }

            if (!image.tagMessage.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("处理信息", style = MaterialTheme.typography.labelMedium)
                Text(image.tagMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
