package cn.examassist.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream

class AppModel(application: Application) : AndroidViewModel(application) {
    private val settings = AiConfigStore(application)
    private val store = BankStore(application)
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private var lastImport: Uri? = null
    private var parsing: Job? = null

    init {
        run("正在读取配置") {
            val config = withContext(Dispatchers.IO) {
                runCatching { settings.load() }.getOrElse { error ->
                    message(error.message ?: "无法读取配置，请重新保存。")
                    AiConfig()
                }
            }
            AppSession.aiConfig = config
            mutable.update { it.copy(config = config) }
            refreshBanks()
        }
    }
    fun message(text: String?) { mutable.update { it.copy(message = text) } }
    fun saveAi(config: AiConfig) {
        if (AppSession.captureStatus.value.startsWith("已开启")) {
            message("请先停止浮窗，再修改 AI 配置。")
            return
        }
        Api.validateAnswerMode(config)?.let { message(it); return }
        run("正在保存 AI 配置") {
            withContext(Dispatchers.IO) { settings.save(config) }
            AppSession.aiConfig = config
            mutable.update { it.copy(config = config, message = "配置已保存。") }
        }
    }
    fun loadBanks() = run("正在读取题库") { refreshBanks() }
    private suspend fun refreshBanks() {
        val banks = withContext(Dispatchers.IO) { store.list() }
        mutable.update { it.copy(banks = banks) }
    }
    fun select(id: String, checked: Boolean) = run("正在保存启用状态") {
        withContext(Dispatchers.IO) { store.setEnabled(id, checked) }
        refreshBanks()
    }
    fun clearPreview() {
        if (mutable.value.busy == null) {
            lastImport = null
            mutable.update { it.copy(preview = null, importFilename = null) }
        }
    }
    fun setImportAi(enabled: Boolean) {
        if (mutable.value.busy == null && mutable.value.preview == null) mutable.update { it.copy(useAiImport = enabled) }
    }
    fun retryImport() { lastImport?.let(::preview) }
    fun reparseWithAi() {
        if (mutable.value.busy != null) return
        lastImport?.let { uri ->
            mutable.update { it.copy(useAiImport = true) }
            previewFile(uri, forceAi = true)
        }
    }
    fun cancelParsing() {
        if (mutable.value.parsingImport) {
            parsing?.cancel()
            message("解析已取消，可以重新选择文件或重试。")
        }
    }
    fun preview(uri: Uri) = previewFile(uri)
    private fun previewFile(uri: Uri, forceAi: Boolean = false) {
        if (mutable.value.busy != null) return
        lastImport = uri
        parsing = run("正在读取题库") {
            mutable.update { it.copy(parsingImport = true, importFilename = null) }
            try {
                val useAi = mutable.value.useAiImport
                val config = mutable.value.config
                val resolver = getApplication<Application>().contentResolver
                val (bytes, filename) = withContext(Dispatchers.IO) {
                    val filename = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: throw ApiException("无法获取文件名，请重新选择带扩展名的题库文件。")
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (output.size() + n > 10 * 1024 * 1024) throw ApiException("题库超过 10 MiB，请拆分后导入。")
                            output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    } ?: throw ApiException("无法读取题库文件。")
                    bytes to filename
                }
                mutable.update { it.copy(importFilename = filename) }
                val extractionWarnings = mutableListOf<String>()
                val text = BankDocumentReader.extract(getApplication(), bytes, filename,
                    { label -> mutable.update { it.copy(busy = label) } }, extractionWarnings::add)
                val local = if (forceAi) null else try {
                    withContext(Dispatchers.Default) {
                        if (text == null) BankImporter.preview(bytes, filename) else BankImporter.previewText(text, filename)
                    }
                } catch (error: IllegalArgumentException) {
                    if (!useAi) throw error
                    null
                }
                val preview = if (local != null && (!useAi || !local.optBoolean("requires_ai_normalization"))) {
                    mutable.update { it.copy(busy = "正在规范题库格式") }
                    withContext(Dispatchers.Default) {
                        BankTemplate.normalize(local)
                        if (useAi) AiBankImporter.completeMissingAnswers(config, local) { current, total ->
                            mutable.update { it.copy(busy = "大模型正在补全缺失答案 $current/$total") }
                        } else local
                    }
                } else {
                    Api.validate(config)?.let { throw ApiException("文件需要大模型整理，请先在 AI 配置中保存模型和 Key。$it") }
                    val source = text ?: withContext(Dispatchers.Default) { BankImporter.extractText(bytes, filename) }
                    withContext(Dispatchers.Default) {
                        AiBankImporter.preview(config, source, filename) { current, total ->
                            mutable.update { it.copy(busy = "大模型正在整理题库 $current/$total") }
                        }
                    }
                }
                withContext(Dispatchers.Default) { BankTemplate.normalize(preview) }
                preview.put("source_file", filename)
                if (text != null) preview.optJSONArray("warnings")?.put("内容由 OCR 提取，请核对题干、选项与原答案，尤其是数字、公式和表格。")
                extractionWarnings.forEach { preview.optJSONArray("warnings")?.put(it) }
                mutable.update { it.copy(preview = preview) }
            } finally { mutable.update { it.copy(parsingImport = false) } }
        }
    }
    fun importPreview() = run("正在保存题库") {
        val preview = mutable.value.preview ?: return@run
        val bank = withContext(Dispatchers.IO) { store.importBank(preview) }
        lastImport = null
        mutable.update { it.copy(preview = null, importFilename = null, message = if (bank.enabled) "题库已保存在手机，并已启用。" else "题库已保存。启用总题数超过 5000，请先停用其他题库后再启用。") }
        refreshBanks()
    }
    fun delete(bank: Bank) = run("正在删除题库") {
        withContext(Dispatchers.IO) { store.delete(bank.id) }
        refreshBanks()
        mutable.update { it.copy(message = "题库已删除。") }
    }
    // Local imports and writes finish atomically; disabling controls avoids duplicate imports.
    private fun run(label: String, task: suspend () -> Unit): Job? {
        if (mutable.value.busy != null) return null
        mutable.update { it.copy(busy = label, message = null) }
        return viewModelScope.launch {
            try { task() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(message = e.message ?: "操作失败，请重试。") } }
            finally { mutable.update { it.copy(busy = null) } }
        }
    }
    override fun onCleared() { super.onCleared(); store.close() }
}
