package cn.examassist.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** Saved native slider -> encrypted settings -> real SQLite, page text and screenshot OCR. */
internal object MatchThresholdCheck {
    fun run(instrumentation: Instrumentation): List<String> {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.startsWith("sdk_gphone"))
        val context = instrumentation.targetContext
        val settings = AiConfigStore(context)
        val saved = settings.load()
        val session = AppSession.aiConfig
        check(!AppSession.captureStatus.value.startsWith("已开启"))
        val initial = AiConfig(aiEnabled = false, bankMatchThreshold = 85)
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.serviceInfo = checkNotNull(ui.serviceInfo).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val store = BankStore(context)
        val bankId = "threshold-${UUID.randomUUID()}"
        var main: MainActivity? = null
        val passed = mutableListOf<String>()
        try {
            settings.save(initial)
            store.importBank(BankImporter.preview("题干,答案\n中国山河风雨,内容\n中国山河云雨,候选答案\n".toByteArray(), "threshold.csv"), bankId)
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            main = activity
            lateinit var model: AppModel
            instrumentation.runOnMainSync { model = ViewModelProvider(activity)[AppModel::class.java] }
            PageTextSessionCheck.waitFor("saved threshold loaded") { model.state.value.config == initial && model.state.value.busy == null && activity.hasWindowFocus() }
            clickText(instrumentation, ui, "AI 配置")
            withVisibleNode(ui, { it.contentDescription?.toString() == "题库匹配阈值" }) { node ->
                check(node.isEnabled)
                check(node.rangeInfo?.min == 0f && node.rangeInfo?.max == 100f)
                check(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 80f) }))
            }
            // Allow Compose to render the action before taking the visual snapshot.
            // Saved configuration below verifies the actual value; do not scroll while a
            // cached accessibility range is still publishing its change event.
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
            check(settings.load().bankMatchThreshold == 85) { "Draft unexpectedly saved before Save." }
            ui.takeScreenshot()?.let { image ->
                try { File(context.getExternalFilesDir(null), "threshold-setting.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { image.recycle() }
            }
            passed += "native accessible slider exposes 0–100%, updates draft to 80%, and keeps saved value until Save"
            withVisibleNode(ui, { it.text?.toString() == "保存配置" }) { node ->
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                tap(instrumentation, bounds.exactCenterX(), bounds.exactCenterY())
            }
            PageTextSessionCheck.waitFor("save threshold") { model.state.value.config.bankMatchThreshold == 80 && model.state.value.busy == null }
            check(settings.load() == initial.copy(bankMatchThreshold = 80))
            check(AppSession.aiConfig.bankMatchThreshold == 80)
            passed += "Save persists threshold through Android Keystore and updates the floating-session configuration"

            val config = settings.load()
            val answer = runBlocking { QuestionSolver.solveText(config, "中国山河风雪", store) { _, _ -> } }
            check(answer.getString("answer") == "内容" && answer.getDouble("match_score") == 0.8)
            val miss = runCatching { runBlocking { QuestionSolver.solveText(config.copy(bankMatchThreshold = 81), "中国山河风雪", store) { _, _ -> } } }.exceptionOrNull()
            check(miss is ApiException && miss.message.orEmpty().contains("81%"))
            passed += "page text / automatic solver accepts 80% equality and rejects it at 81% without a model request"

            val bitmap = Bitmap.createBitmap(1080, 1600, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    drawText("中国山河风雪", 64f, 320f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f })
                }
                val ocr = runBlocking { QuestionSolver.solve(config, bitmap, store) { _, _ -> } }
                check(ocr.getString("answer") == "内容" && ocr.getInt("match_threshold") == 80)
                val rejected = runCatching { runBlocking { QuestionSolver.solve(config.copy(bankMatchThreshold = 100), bitmap, store) { _, _ -> } } }.exceptionOrNull()
                check(rejected is ApiException && rejected.message.orEmpty().contains("100%"))
                passed += "real Chinese screenshot OCR uses the same configured threshold: 80% passes and 100% misses"
            } finally { bitmap.recycle() }

            instrumentation.runOnMainSync { model.saveAi(config.copy(bankMatchThreshold = 100)) }
            PageTextSessionCheck.waitFor("stricter setting saved") {
                model.state.value.config.bankMatchThreshold == 100 && model.state.value.busy == null
            }
            val stricter = runCatching { runBlocking { QuestionSolver.solveText(settings.load(), "中国山河风雪", store) { _, _ -> } } }.exceptionOrNull()
            check(stricter is ApiException && stricter.message.orEmpty().contains("100%"))
            passed += "saving a stricter threshold rejects lower scores through the active solver"
            return passed
        } finally {
            main?.let { instrumentation.runOnMainSync { it.finish() } }
            try { store.delete(bankId) } finally {
                store.close()
                settings.save(saved)
                AppSession.aiConfig = session
            }
        }
    }

    private fun clickText(instrumentation: Instrumentation, ui: UiAutomation, text: String) {
        withVisibleNode(ui, { text in it.text?.toString().orEmpty() }) { node ->
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            tap(instrumentation, bounds.exactCenterX(), bounds.exactCenterY())
        }
    }

    private fun tap(instrumentation: Instrumentation, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
    }

    @Suppress("DEPRECATION")
    private fun withVisibleNode(ui: UiAutomation, predicate: (AccessibilityNodeInfo) -> Boolean, action: (AccessibilityNodeInfo) -> Unit) {
        repeat(18) {
            val root = ui.rootInActiveWindow
            if (root != null) try {
                fun visit(node: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean, apply: (AccessibilityNodeInfo) -> Unit): Boolean {
                    if (node.isVisibleToUser && match(node)) { apply(node); return true }
                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index) ?: continue
                        try { if (visit(child, match, apply)) return true } finally { child.recycle() }
                    }
                    return false
                }
                if (visit(root, predicate, action)) return
                visit(root, { it.isScrollable }) { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            } finally { root.recycle() }
            SystemClock.sleep(400)
        }
        error("Expected accessible configuration control was not found")
    }
}
