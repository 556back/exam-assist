package cn.examassist.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal object BankDiagnostic {
    fun text(snapshot: JSONObject): String = buildString {
        appendLine("最近一次题库匹配：${snapshot.optString("status")}")
        if (snapshot.has("read_at")) appendLine("时间：${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(snapshot.getLong("read_at")))}")
        appendLine("启用题数：${snapshot.optInt("enabled_questions")}；可解析题数：${snapshot.optInt("parseable_questions")}")
        appendLine("匹配阈值：${snapshot.optInt("threshold")}%")
        appendLine("结果：${snapshot.optString("reason")}")
        snapshot.optJSONArray("questions").objects().forEach { question ->
            appendLine()
            appendLine("识别出的第 ${question.optInt("number")} 题")
            if (question.has("parse_error")) appendLine(question.optString("parse_error"))
            appendLine("题干：${question.optString("stem")}")
            val options = question.optJSONObject("options")
            appendLine("读到选项：${options?.length() ?: 0} 项")
            options?.keys()?.asSequence()?.sorted()?.forEach { appendLine("$it. ${options.optString(it)}") }
            question.optJSONObject("best")?.let { best ->
                appendLine("最高匹配度：${BankMatchSettings.percent(question.optDouble("score"))}；同分记录：${question.optInt("tied_candidates")}")
                appendLine("最相近题库：${best.optString("bank_name").ifBlank { best.optString("source") }}")
                appendLine("题库题干：${best.optString("stem")}")
                best.optJSONArray("options").objects().forEach { appendLine("${it.optString("label")}. ${it.optString("text")}") }
                appendLine("题库参考答案：${best.optString("reference_answer").ifBlank { "未提供" }}")
            }
        }
        appendLine()
        appendLine("实际读取文字（未经题目清理）")
        append(snapshot.optString("raw_text"))
    }
}

@Composable
internal fun BankDiagnosticDialog(snapshot: JSONObject, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val report = BankDiagnostic.text(snapshot)
    val maxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.55f).toDp() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("最近读取文字") },
        text = {
            SelectionContainer {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(report.lines()) { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        dismissButton = { TextButton(onClick = {
            runCatching {
                context.getSystemService(Context.CLIPBOARD_SERVICE).let { it as ClipboardManager }
                    .setPrimaryClip(ClipData.newPlainText("题库匹配排查", report))
            }.onSuccess { onMessage("排查信息已复制。") }.onFailure { onMessage("复制失败，请重试。") }
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("复制排查信息") } },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") } }
    )
}
