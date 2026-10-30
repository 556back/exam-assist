package cn.examassist.app

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Bank(val id: String, val name: String, val questionCount: Int, val enabled: Boolean = true)
data class Attachment(val bitmap: Bitmap, val jpeg: ByteArray)
data class UiState(
    val config: AiConfig = AiConfig(),
    val busy: String? = null,
    val message: String? = null,
    val banks: List<Bank> = emptyList(),
    val preview: JSONObject? = null,
    val useAiImport: Boolean = true,
    val importFilename: String? = null,
    val parsingImport: Boolean = false
)
object AppSession {
    @Volatile var aiConfig = AiConfig()
    val overlayAnswer = MutableStateFlow<JSONObject?>(null)
    val captureStatus = MutableStateFlow("未开启浮窗")
    // One user-requested lookup snapshot in memory; never written to disk or uploaded.
    internal val bankDiagnostic = MutableStateFlow<JSONObject?>(null)
}
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private val layoutWhitespace = Regex("[\\s\\p{Z}]+")
/** Ignore layout spacing while retaining word/numeric boundaries such as "now here" and "1 2". */
internal fun compactBankText(raw: String): String {
    val text = raw.replace("\uFEFF", "")
    fun alphanumeric(char: Char?) = char != null && (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9')
    return layoutWhitespace.replace(text) { gap ->
        if (alphanumeric(text.getOrNull(gap.range.first - 1)) && alphanumeric(text.getOrNull(gap.range.last + 1))) " " else ""
    }
}
