package cn.examassist.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class AppInfoDocument(val title: String, val asset: String) {
    GUIDE("使用说明", "usage.txt"),
    PRIVACY("隐私说明", "privacy.txt"),
    LICENSES("开源许可证", "project_license.txt")
}

@Composable
internal fun AppInfoDialog(document: AppInfoDocument, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val paragraphs by produceState<List<String>?>(null, document, context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(document.asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
            }.getOrElse { listOf("无法加载说明，请关闭后重试。") }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(document.title) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.55f).dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(paragraphs ?: listOf("正在加载…")) { paragraph ->
                    Text(paragraph, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") } }
    )
}
