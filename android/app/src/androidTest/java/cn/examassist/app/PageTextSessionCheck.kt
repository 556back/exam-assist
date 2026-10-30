package cn.examassist.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import java.util.UUID

/** Actual floating-button session on a disposable emulator with permissions granted by its tester. */
internal object PageTextSessionCheck {
    fun run(instrumentation: Instrumentation): List<String> {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.startsWith("sdk_gphone")) { "Run this session check only on a disposable Android emulator." }
        val context = instrumentation.targetContext
        check(Settings.canDrawOverlays(context)) { "Grant the emulator's floating-window permission first." }
        check(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            "Grant POST_NOTIFICATIONS on the disposable emulator before this session check."
        }
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.serviceInfo = checkNotNull(ui.serviceInfo).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        check(checkNotNull(ui.serviceInfo).flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0) { "UiAutomation must retrieve interactive windows for this session check." }
        val previousSessionConfig = AppSession.aiConfig
        val previousStatus = AppSession.captureStatus.value
        val previousAnswer = AppSession.overlayAnswer.value
        check(!previousStatus.startsWith("已开启")) { "Stop the existing floating session before running this check." }
        val settings = AiConfigStore(context)
        val previousSavedConfig = settings.load()
        val temporaryConfig = previousSavedConfig.copy(apiKey = "", aiEnabled = false, pageTextEnabled = true,
            autoAnswerEnabled = false, answerFormat = AnswerFormat.LABELS)
        val bankId = "page-text-session-${UUID.randomUUID()}"
        val banks = BankStore(context)
        var mainActivity: MainActivity? = null
        var fixtureStarted = false
        var sessionStartRequested = false
        val passed = mutableListOf<String>()
        try {
            val csv = "题干,A,B,C,D,答案\n太阳从哪个方向升起？,东,西,南,北,A\n"
            check(banks.importBank(BankImporter.preview(csv.toByteArray(Charsets.UTF_8), "session-check.csv"), bankId).enabled) { "The disposable emulator must have room for one enabled test question." }
            settings.save(temporaryConfig)
            AppSession.aiConfig = temporaryConfig
            AppSession.overlayAnswer.value = null
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            mainActivity = activity
            lateinit var model: AppModel
            instrumentation.runOnMainSync { model = ViewModelProvider(activity)[AppModel::class.java] }
            waitFor("MainActivity loaded the temporary local text configuration") {
                model.state.value.busy == null && model.state.value.config.pageTextEnabled && !model.state.value.config.aiEnabled && activity.hasWindowFocus()
            }
            waitFor("PageTextService connection after MainActivity startup; enable the service after instrumentation starts if the emulator retained a dead binding", 30000) {
                PageTextService.isConnected
            }
            waitFor("MainActivity is foreground before starting its text session") { activity.hasWindowFocus() }
            // Invoke the existing button callback on the real foreground Activity, without test hooks.
            sessionStartRequested = true
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("startCapture").apply { isAccessible = true }.invoke(activity)
            }
            waitFor("real page-text floating service startup") { AppSession.captureStatus.value.startsWith("已开启") }
            check(AppSession.captureStatus.value.contains("页面文字模式")) { "The real floating session started in the wrong mode." }
            passed += "real foreground MainActivity starts the text-only floating service without a projection result"

            instrumentation.context.startActivity(Intent().setClassName(instrumentation.context.packageName,
                ProtectedQuestionActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            fixtureStarted = true
            waitFor("external protected question is visible") {
                findText(ui, instrumentation.context.packageName, "太阳从哪个方向升起？") != null
            }
            var button: Rect? = null
            waitFor("real red floating button") {
                button = findText(ui, context.packageName, "答", "识题并自动解答")
                button != null
            }
            val point = checkNotNull(button)
            val downTime = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    point.exactCenterX(), point.exactCenterY(), 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            waitFor("protected page is answered after a real floating-button tap") { AppSession.overlayAnswer.value != null }
            val answer = checkNotNull(AppSession.overlayAnswer.value)
            check(answer.getString("answer") == "A" && answer.getString("answer_source") == "local_bank") { "The floating page-text session did not return the original local answer A." }
            passed += "real red-button pointer tap reads external FLAG_SECURE question and returns local answer A"

            context.stopService(Intent(context, CaptureService::class.java))
            waitFor("service stop clears the current answer and floating button") {
                !AppSession.captureStatus.value.startsWith("已开启") && AppSession.overlayAnswer.value == null &&
                    findText(ui, context.packageName, "答", "识题并自动解答") == null
            }
            passed += "stopping the real text session removes its floating button and clears the answer"
            return passed
        } finally {
            try {
                context.stopService(Intent(context, CaptureService::class.java))
                if (sessionStartRequested) waitFor("session cleanup") { !AppSession.captureStatus.value.startsWith("已开启") && AppSession.overlayAnswer.value == null }
                if (fixtureStarted) ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                mainActivity?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            } finally {
                try { banks.delete(bankId) } finally {
                    banks.close()
                    settings.save(previousSavedConfig)
                    AppSession.aiConfig = previousSessionConfig
                    AppSession.captureStatus.value = previousStatus
                    AppSession.overlayAnswer.value = previousAnswer
                }
            }
        }
    }

    internal fun waitFor(label: String, timeoutMillis: Long = 10000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for $label." }
            SystemClock.sleep(100)
        }
    }

    @Suppress("DEPRECATION")
    internal fun findText(ui: UiAutomation, packageName: String, text: String, descriptionPrefix: String? = null): Rect? {
        val windows = ui.windows
        try {
            for (window in windows) {
                val root = window.root ?: continue
                try {
                    if (root.packageName?.toString() != packageName) continue
                    // WebView virtual nodes need not implement the provider's text-search API.
                    // Walk the real tree, as the production page reader does.
                    var remaining = 2000
                    fun visit(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int): Rect? {
                        if (--remaining < 0 || depth > 64 || !node.isVisibleToUser) return null
                        if (node.text?.toString()?.trim() == text &&
                            (descriptionPrefix == null || node.contentDescription?.toString()?.startsWith(descriptionPrefix) == true))
                            Rect().also(node::getBoundsInScreen).takeUnless(Rect::isEmpty)?.let { return it }
                        for (index in 0 until node.childCount) {
                            val child = node.getChild(index) ?: continue
                            try { visit(child, depth + 1)?.let { return it } } finally { child.recycle() }
                        }
                        return null
                    }
                    visit(root, 0)?.let { return it }
                } finally { root.recycle() }
            }
            return null
        } finally { windows.forEach { it.recycle() } }
    }
}
