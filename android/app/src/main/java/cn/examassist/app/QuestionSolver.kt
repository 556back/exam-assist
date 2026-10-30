package cn.examassist.app

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal object QuestionSolver {
    suspend fun solveText(config: AiConfig, question: String, banks: BankStore, progress: (String, String) -> Unit): JSONObject {
        currentCoroutineContext().ensureActive()
        val text = validatePageText(question)
        progress("正在匹配本地题库", "匹配")
        bankAnswer(config, withContext(Dispatchers.IO) { banks.lookup(text, config.answerFormat, config.bankMatchThreshold) })?.let { return it }
        Api.validateAnswerMode(config)?.let { throw ApiException(it) }
        val references = withContext(Dispatchers.IO) { banks.references(text) }
        progress("AI 正在解答", "解答")
        return Api.solve(config, text, image = null, references = references)
    }

    /** Shared by page text, automatic answering and screenshot/OCR requests. */
    internal fun bankAnswer(config: AiConfig, lookup: LocalBankMatching.Lookup): JSONObject? = when (lookup) {
        is LocalBankMatching.Lookup.Hit -> lookup.result
        is LocalBankMatching.Lookup.Blocked -> if (config.aiEnabled) null else throw ApiException(lookup.reason)
        is LocalBankMatching.Lookup.Miss -> if (config.aiEnabled) null else throw ApiException(lookup.reason)
    }

    internal fun validatePageText(question: String): String {
        val text = question.trim()
        if (text.isBlank()) throw ApiException("未读取到题目文字，请完整显示题干与选项，或切换到截图模式。")
        if (text.length > 20000) throw ApiException("页面文字过多，请只显示当前题目后重试。")
        return text
    }

    suspend fun solve(config: AiConfig, bitmap: Bitmap, banks: BankStore, progress: (String, String) -> Unit): JSONObject {
        if (withContext(Dispatchers.Default) { ImageTools.isBlack(bitmap) }) {
            throw ApiException("未捕获到题目画面，请重试；受保护页面可尝试开启「读取页面文字」。")
        }
        if (config.usesOcr) progress("正在识别题目", "识别")
        val question = readQuestion(config) { ImageTools.recognize(bitmap) }
        if (question.isNotBlank() || !config.aiEnabled) {
            progress("正在匹配本地题库", "匹配")
            bankAnswer(config, withContext(Dispatchers.IO) { banks.lookup(question, config.answerFormat, config.bankMatchThreshold) })?.let { return it }
        }
        Api.validateAnswerMode(config)?.let { throw ApiException(it) }
        if (config.usesOcr && question.isBlank()) progress("文字识别未完成，正在直接读取截图", "解答")
        val references = withContext(Dispatchers.IO) { banks.references(question) }
        val jpeg = if (config.supportsImages) withContext(Dispatchers.Default) {
            val attachment = ImageTools.attachment(bitmap)
            try { attachment.jpeg } finally { if (attachment.bitmap !== bitmap) attachment.bitmap.recycle() }
        } else null
        progress("AI 正在解答", "解答")
        return Api.solve(config, question, jpeg, references)
    }

    internal suspend fun readQuestion(config: AiConfig, timeoutMillis: Long = if (config.aiEnabled && config.supportsImages) 2500 else 15000, recognize: suspend () -> String): String {
        currentCoroutineContext().ensureActive()
        if (!config.usesOcr) return ""
        val question = try { withTimeoutOrNull(timeoutMillis) { recognize() }?.trim().orEmpty() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { "" }
        currentCoroutineContext().ensureActive()
        val canReadImage = config.aiEnabled && config.supportsImages
        if (canReadImage) return if (question.length > 20000) "" else question
        if (question.isBlank()) throw ApiException(if (config.aiEnabled) {
            "未识别到题目文字，请完整显示题干与选项后重试，或使用视觉模型。"
        } else {
            "本地匹配需要题目文字，请完整显示题干与选项，或开启「读取页面文字」后重试。"
        })
        return question
    }
}
