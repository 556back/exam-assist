package cn.examassist.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[AppModel::class.java] }
    private val pickBank = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::preview) }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) startCapture()
        else model.message("未授予浮窗权限，请重新开启浮窗并授权。")
    }
    private val accessibilitySettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (PageTextService.isConnected) startCapture()
        else model.message("请在系统无障碍设置中开启「${getString(R.string.page_text_service_name)}」，再重新开启浮窗。")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestProjection() }
    private val projectionPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            launchCapture(Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.RESULT_CODE, result.resultCode).putExtra(CaptureService.RESULT_DATA, result.data))
        } else model.message("已取消屏幕捕获授权。")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ExamAssistTheme { AppScreen(model, {
            pickBank.launch(arrayOf("text/*", "application/json", "application/pdf", "image/png", "image/jpeg", "image/webp",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream"))
        }, ::startCapture, ::stopCapture, { moveTaskToBack(true) }) } }
    }
    private fun startCapture() {
        Api.validateAnswerMode(AppSession.aiConfig)?.let { model.message(it); return }
        if (AppSession.aiConfig.usesPageText && !PageTextService.isConnected) {
            runCatching { accessibilitySettings.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { model.message("无法打开系统无障碍设置，请在手机设置中开启「${getString(R.string.page_text_service_name)}」。") }
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else requestProjection()
    }
    private fun requestProjection() {
        if (AppSession.aiConfig.usesPageText) {
            if (!PageTextService.isConnected) {
                model.message("页面文字读取尚未连接，请重新开启浮窗并在系统设置中授权。")
                return
            }
            launchCapture(Intent(this, CaptureService::class.java))
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
        projectionPermission.launch(intent)
    }
    private fun launchCapture(intent: Intent) {
        runCatching {
            ContextCompat.startForegroundService(this, intent)
            lifecycleScope.launch {
                val started = withTimeoutOrNull(5000) { AppSession.captureStatus.first { it.startsWith("已开启") } }
                if (started != null) moveTaskToBack(true)
            }
        }.onFailure { model.message("无法开启浮窗，请重试授权。") }
    }
    private fun stopCapture() { stopService(Intent(this, CaptureService::class.java)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(model: AppModel, pickBank: () -> Unit, startCapture: () -> Unit, stopCapture: () -> Unit, returnToTask: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val capture by AppSession.captureStatus.collectAsStateWithLifecycle()
    val diagnostic by AppSession.bankDiagnostic.collectAsStateWithLifecycle()
    val active = capture.startsWith("已开启")
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var draft by remember(state.config) { mutableStateOf(state.config) }
    var revealKey by remember { mutableStateOf(false) }
    var deleteBank by remember { mutableStateOf<Bank?>(null) }
    var leavePreview by remember { mutableStateOf(false) }
    var leaveDraft by remember { mutableStateOf(false) }
    var visibleBanks by rememberSaveable { mutableIntStateOf(20) }
    var visibleWarnings by remember(state.preview) { mutableIntStateOf(5) }
    var visibleQuestions by remember(state.preview) { mutableIntStateOf(3) }
    var infoDocument by rememberSaveable { mutableStateOf<AppInfoDocument?>(null) }
    var diagnosticSnapshot by remember { mutableStateOf<org.json.JSONObject?>(null) }
    val bankCount = state.banks.filter { it.enabled }.sumOf { it.questionCount }
    val canStart = Api.validateAnswerMode(state.config) == null && (state.config.aiEnabled || bankCount > 0)
    BackHandler(enabled = state.preview != null || draft != state.config) {
        if (state.preview != null) leavePreview = true else leaveDraft = true
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
            actions = { TextButton(onClick = { infoDocument = AppInfoDocument.GUIDE }) { Text("使用说明") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Background)) },
        bottomBar = { NavigationBar(containerColor = AppColors.Surface) {
            listOf("浮窗", "题库", "AI 配置").forEachIndexed { index, label ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("浮", "库", "AI")[index]) }, label = { Text(label) })
            }
        } }, containerColor = AppColors.Background
    ) { padding ->
        key(tab) {
            Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                state.busy?.let { label ->
                    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        if (state.parsingImport) TextButton(onClick = model::cancelParsing) { Text("取消解析") }
                    }
                }
                state.message?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                            Text(message)
                            TextButton(onClick = { model.message(null) }) { Text("关闭提示") }
                        }
                    }
                }
                when (tab) {
                    0 -> {
                        Text("一键识题", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Surface(shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Box(Modifier.size(64.dp).background(AppColors.Capture, CircleShape), contentAlignment = Alignment.Center) {
                                    Text("答", color = AppColors.Surface, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "红色浮窗按钮预览" })
                                }
                                Text(if (state.config.autoAnswerEnabled) "点红钮连续答题，再点停止" else "点红钮获取答案并复制", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(capture, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = if (active) AppColors.Primary else AppColors.Ink)
                        Text(if (!state.config.aiEnabled) "本地题库模式 · 无需 Key 或网络"
                            else if (Api.validate(state.config) == null) "当前 AI：${state.config.model}" else "请先配置 AI，或在设置中关闭大模型解答", style = MaterialTheme.typography.bodyMedium)
                        Text("${if (state.config.usesPageText) "页面文字" else "截图识题"} · 已启用 ${state.banks.count { it.enabled }} 个题库 / $bankCount 题", style = MaterialTheme.typography.bodyMedium)
                        if (active) {
                            Button(onClick = returnToTask, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("返回刷题页面") }
                            OutlinedButton(onClick = stopCapture, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("停止浮窗") }
                        } else {
                            Button(onClick = { if (canStart) startCapture() else tab = if (state.config.aiEnabled) 2 else 1 }, enabled = state.busy == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(if (canStart) "开启浮窗" else if (state.config.aiEnabled) "配置 AI" else "导入并启用题库")
                            }
                        }
                        Text(when {
                            state.config.usesPageText -> "开启需浮窗及无障碍授权。"
                            else -> "开启需浮窗及屏幕捕获授权。"
                        } + if (state.config.aiEnabled) "题库无可用答案时，题目发送给所选 AI。" else "题目仅在本机处理。", style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        Text("我的题库", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { diagnosticSnapshot = diagnostic }, enabled = diagnostic != null,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("查看最近读取文字") }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("AI 整理复杂排版并补答案", Modifier.weight(1f))
                            Switch(state.useAiImport, model::setImportAi, enabled = state.busy == null && state.preview == null,
                                modifier = Modifier.semantics { contentDescription = "用大模型整理复杂排版并补全缺失答案" })
                        }
                        if (state.useAiImport) Text("整理时将题库文字发送给已配置的 AI。", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = pickBank, enabled = state.busy == null && state.preview == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("导入题库") }
                        Text("表格、文本、文档或图片 · 最大 10 MiB / 5000 题", style = MaterialTheme.typography.bodySmall)
                        if (state.importFilename != null && state.preview == null && state.busy == null) {
                            Text("上次选择：${state.importFilename}", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = model::retryImport) { Text("重试解析") }
                        }
                        if (state.banks.isEmpty()) Text("还没有题库，请导入文件。")
                        state.banks.take(visibleBanks).forEach { bank ->
                            Surface(shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(bank.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("${bank.questionCount} 题", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                        Text(if (bank.enabled) "已启用" else "未启用", style = MaterialTheme.typography.bodySmall)
                                        Switch(bank.enabled, { model.select(bank.id, it) }, enabled = state.busy == null, modifier = Modifier.semantics { contentDescription = "启用题库：${bank.name}" })
                                        TextButton(onClick = { deleteBank = bank }, enabled = state.busy == null) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }
                        if (state.banks.size > visibleBanks) OutlinedButton(onClick = { visibleBanks += 20 }) { Text("加载更多题库") }
                        state.preview?.let { preview ->
                            Surface(shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("导入预览 · ${preview.optString("name")}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("共 ${preview.optInt("total")} 道题")
                                    preview.optJSONObject("quality")?.let { quality ->
                                        Text("可用于本地答题 ${quality.optInt("usable_answers")} / ${quality.optInt("total")} 题", style = MaterialTheme.typography.bodyMedium)
                                        Text("题库检查：缺答案 ${quality.optInt("missing_answers")} · 无法映射 ${quality.optInt("unusable_answers")} · 冲突 ${quality.optInt("conflict_groups")} 组 · 模型补全 ${quality.optInt("model_answers")}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    val warnings = (preview.optJSONArray("warnings").strings() + preview.optJSONObject("quality")?.optJSONArray("warnings").strings()).distinct()
                                    warnings.take(visibleWarnings).forEach { Text(it, color = AppColors.Warning, style = MaterialTheme.typography.bodySmall) }
                                    if (warnings.size > visibleWarnings) TextButton(onClick = { visibleWarnings += 20 }) { Text("更多导入提示（${warnings.size}）") }
                                    Text("请核对题目、选项及答案，尤其是模型补全的答案。", style = MaterialTheme.typography.bodySmall)
                                    val questions = preview.optJSONArray("questions").objects()
                                    questions.take(visibleQuestions).forEachIndexed { index, question ->
                                        HorizontalDivider()
                                        Text("${index + 1}. ${question.optString("stem")}")
                                        question.optJSONArray("options").objects().forEach { option -> Text("${option.optString("label")}. ${option.optString("text")}", style = MaterialTheme.typography.bodySmall) }
                                        Text("参考答案：${question.optString("reference_answer").ifBlank { "未提供" }}", style = MaterialTheme.typography.bodySmall)
                                        if (question.has("original_reference_answer")) Text("原答案写法：${question.optString("original_reference_answer")}", style = MaterialTheme.typography.bodySmall)
                                        Text("答案来源：${when (question.optString("answer_origin")) { "model" -> "模型补全（${question.optString("answer_model").ifBlank { "请核对" }}）"; "missing" -> "未提供"; else -> "原题库" }}", style = MaterialTheme.typography.bodySmall)
                                        if (question.optString("source_text").isNotBlank()) Text("原文：${question.optString("source_text")}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (questions.size > visibleQuestions) TextButton(onClick = { visibleQuestions += 20 }) { Text("查看更多题目（已显示 ${minOf(visibleQuestions, questions.size)}/${questions.size}）") }
                                    if (preview.optString("import_method") == "local") {
                                        OutlinedButton(onClick = model::reparseWithAi, enabled = state.busy == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("用模型重新整理整个文件") }
                                    }
                                    Button(onClick = model::importPreview, enabled = state.busy == null && preview.optInt("total") > 0, modifier = Modifier.fillMaxWidth()) { Text("确认导入") }
                                    TextButton(onClick = model::clearPreview, enabled = state.busy == null) { Text("取消导入") }
                                }
                            }
                        }
                    }
                    2 -> {
                        Text("配置 AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("全自动答题", Modifier.weight(1f))
                            Switch(draft.autoAnswerEnabled, { draft = draft.copy(autoAnswerEnabled = it) }, enabled = state.busy == null && !active,
                                modifier = Modifier.semantics { contentDescription = "全自动答题，自动选择选项并点击下一题" })
                        }
                        if (draft.autoAnswerEnabled) Text("点红钮开始或停止；不自动交卷。", style = MaterialTheme.typography.bodySmall)
                        Text("答案返回形式", style = MaterialTheme.typography.titleMedium)
                        AnswerFormat.entries.forEach { format ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = draft.answerFormat == format,
                                enabled = state.busy == null && !active, role = Role.RadioButton,
                                onClick = { draft = draft.copy(answerFormat = format) }), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(draft.answerFormat == format, onClick = null, enabled = state.busy == null && !active)
                                Text(format.title, Modifier.padding(start = 12.dp))
                            }
                        }
                        Text("题库匹配阈值：${draft.bankMatchThreshold}%", style = MaterialTheme.typography.titleMedium)
                        Slider(value = draft.bankMatchThreshold.toFloat(), onValueChange = { draft = draft.copy(bankMatchThreshold = it.roundToInt()) },
                            valueRange = 0f..100f, steps = 99, enabled = state.busy == null && !active,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                contentDescription = "题库匹配阈值"
                                stateDescription = "${draft.bankMatchThreshold}%"
                            })
                        Text("越高越严格，默认 85%。", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("启用大模型解答", Modifier.weight(1f))
                            Switch(draft.aiEnabled, { draft = draft.copy(aiEnabled = it) }, enabled = state.busy == null && !active,
                                modifier = Modifier.semantics { contentDescription = "启用大模型解答，关闭后仅匹配本地题库" })
                        }
                        Text(if (draft.aiEnabled) "题库优先，无可用答案时调用 AI。请填写模型和 Key。"
                            else "仅本地匹配，无需 Key 或网络。", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("读取页面文字", Modifier.weight(1f))
                            Switch(draft.usesPageText, { draft = draft.copy(pageTextEnabled = it) }, enabled = state.busy == null && !active && !draft.autoAnswerEnabled,
                                modifier = Modifier.semantics { contentDescription = "读取页面文字，使用系统无障碍授权，无需截屏" })
                        }
                        Text(if (draft.autoAnswerEnabled) "需无障碍授权，请从未作答的题目开始。"
                            else "需无障碍授权，适合禁止截屏但可读取文字的页面。", style = MaterialTheme.typography.bodySmall)
                        AiProviders.forEach { provider ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = draft.provider == provider.id, enabled = state.busy == null && !active, role = Role.RadioButton, onClick = {
                                if (draft.provider != provider.id) draft = draft.copy(provider = provider.id, model = provider.model, apiKey = "", baseUrl = "", imageInput = null)
                            }), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(draft.provider == provider.id, onClick = null, enabled = state.busy == null && !active)
                                Text(provider.title, Modifier.padding(start = 12.dp))
                            }
                        }
                        OutlinedTextField(draft.model, { draft = draft.copy(model = it) }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = state.busy == null && !active)
                        OutlinedTextField(draft.apiKey, { draft = draft.copy(apiKey = it) }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = { TextButton(onClick = { revealKey = !revealKey }) { Text(if (revealKey) "隐藏" else "显示") } }, enabled = state.busy == null && !active)
                        if (draft.provider == "custom") OutlinedTextField(draft.baseUrl, { draft = draft.copy(baseUrl = it) }, label = { Text("API 地址") }, placeholder = { Text("https://example.com/v1") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = state.busy == null && !active)
                        if (draft.provider == "custom") Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("模型支持图片", Modifier.weight(1f))
                            Switch(draft.supportsImages, { draft = draft.copy(imageInput = it) }, enabled = state.busy == null && !active, modifier = Modifier.semantics { contentDescription = "发送图片给视觉模型" })
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("启用 OCR", Modifier.weight(1f))
                            Switch(!draft.usesPageText && draft.usesOcr, { draft = draft.copy(ocrEnabled = it) }, enabled = state.busy == null && !active && !draft.usesPageText && draft.aiEnabled && draft.supportsImages,
                                modifier = Modifier.semantics { contentDescription = "启用 OCR 文字识别" })
                        }
                        Text(when {
                            draft.usesPageText -> "文字模式无需 OCR。"
                            !draft.aiEnabled || !draft.supportsImages -> "截图识题需要 OCR。"
                            draft.usesOcr -> "先识别文字匹配题库，未命中时由模型读图。"
                            else -> "直接读图；开启 OCR 可优先匹配题库。"
                        }, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { model.saveAi(draft.copy(model = draft.model.trim(), apiKey = draft.apiKey.trim(), baseUrl = draft.baseUrl.trim())) }, enabled = state.busy == null && !active, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保存配置") }
                        if (active) Text("请先停止浮窗，再修改 AI 配置。", color = AppColors.Warning)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("v${BuildConfig.VERSION_NAME}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { infoDocument = AppInfoDocument.PRIVACY },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("隐私说明", style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { infoDocument = AppInfoDocument.LICENSES },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("许可证", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    infoDocument?.let { AppInfoDialog(it) { infoDocument = null } }
    diagnosticSnapshot?.let { snapshot -> BankDiagnosticDialog(snapshot, { diagnosticSnapshot = null }) { model.message(it) } }
    deleteBank?.let { bank -> AlertDialog(onDismissRequest = { deleteBank = null }, title = { Text("删除「${bank.name}」？") }, text = { Text("将从手机删除 ${bank.questionCount} 道题，无法恢复。") },
        confirmButton = { TextButton(onClick = { deleteBank = null; model.delete(bank) }) { Text("删除题库", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleteBank = null }) { Text("取消") } }) }
    if (leavePreview) AlertDialog(onDismissRequest = { leavePreview = false }, title = { Text("取消未确认的导入？") }, text = { Text("题库尚未保存，取消后需重新选择文件。") },
        confirmButton = { TextButton(onClick = { leavePreview = false; model.clearPreview() }) { Text("取消导入") } }, dismissButton = { TextButton(onClick = { leavePreview = false }) { Text("保留预览") } })
    if (leaveDraft) AlertDialog(onDismissRequest = { leaveDraft = false }, title = { Text("放弃未保存的配置？") }, text = { Text("修改尚未保存。") },
        confirmButton = { TextButton(onClick = { leaveDraft = false; draft = state.config }) { Text("放弃修改") } }, dismissButton = { TextButton(onClick = { leaveDraft = false }) { Text("继续编辑") } })
}
