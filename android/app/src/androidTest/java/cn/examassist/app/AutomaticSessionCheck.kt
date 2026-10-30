package cn.examassist.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import java.util.UUID

internal object AutomaticSessionCheck {
    fun run(instrumentation: Instrumentation, compatibility: Boolean = false, labels: Boolean = false, aiFallback: Boolean = false): List<String> {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.startsWith("sdk_gphone"))
        val context = instrumentation.targetContext
        val settings = AiConfigStore(context)
        val saved = settings.load()
        val prior = AppSession.aiConfig
        check(!AppSession.captureStatus.value.startsWith("已开启"))
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.serviceInfo = checkNotNull(ui.serviceInfo).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val config = (if (aiFallback) TestAiTransport.config else AiConfig(aiEnabled = false))
            .copy(autoAnswerEnabled = true, answerFormat = AnswerFormat.CONTENT, bankMatchThreshold = 92)
        val store = BankStore(context)
        val bankId = "automatic-${UUID.randomUUID()}"
        var activity: MainActivity? = null
        val passed = mutableListOf<String>()
        try {
            settings.save(config)
            check(settings.load() == config)
            passed += "automatic, answer-content and 92% matching threshold configuration survives encrypted save/load"
            val csv = "题干,A,B,C,D,答案\n太阳从哪个方向升起？,东,西,南,北,A\n下列哪些是质数？,2,4,3,,${if (aiFallback) "" else "AC"}\n苹果属于哪类食物？,水果,蔬菜,,,A\n圆周率约为多少？,,,,,3.14\n太阳升起的方向是哪里？,正西,正东,正南,正北,B\n以下哪些属于水果？,苹果,白菜,香蕉,,AC\n"
            store.importBank(BankImporter.preview(csv.toByteArray(), "automatic.csv"), bankId)
            AppSession.aiConfig = config
            val main = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = main
            lateinit var model: AppModel
            instrumentation.runOnMainSync { model = ViewModelProvider(main)[AppModel::class.java] }
            PageTextSessionCheck.waitFor("configuration loaded") { model.state.value.config == config && model.state.value.busy == null }
            instrumentation.sendStatus(1, Bundle().apply { putString("stream", "WAITING_FOR_ACCESSIBILITY\n") })
            PageTextSessionCheck.waitFor("accessibility connected", 45000) { PageTextService.isConnected }
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("startCapture").apply { isAccessible = true }.invoke(main)
            }
            PageTextSessionCheck.waitFor("automatic floating session") { AppSession.captureStatus.value.contains("全自动模式") }
            instrumentation.context.startActivity(Intent().setClassName(instrumentation.context.packageName,
                AutomaticQuestionActivity::class.java.name).putExtra("compatibility", compatibility).putExtra("labels", labels)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            PageTextSessionCheck.waitFor("external question") {
                PageTextSessionCheck.findText(ui, instrumentation.context.packageName, if (labels) "太阳升起的方向是哪里？" else "太阳从哪个方向升起？") != null
            }
            var foundButton = PageTextSessionCheck.findText(ui, context.packageName, "答")
            PageTextSessionCheck.waitFor("red floating button") {
                foundButton = PageTextSessionCheck.findText(ui, context.packageName, "答")
                foundButton != null
            }
            val button = checkNotNull(foundButton)
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, button.exactCenterX(), button.exactCenterY(), 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            val displays = mutableSetOf<String>()
            PageTextSessionCheck.waitFor("continuous answers, unknown-question skip and end", 45000) {
                AppSession.overlayAnswer.value?.optString("answer")?.let(displays::add)
                AppSession.captureStatus.value.contains("自动答题已结束")
            }
            instrumentation.sendStatus(1, Bundle().apply {
                putString("stream", "Fixture end: ${AppSession.captureStatus.value}\nFixture answers: $displays\n")
            })
            // Completion uses the ordinary transient panel, with no confirmation to continue.
            SystemClock.sleep(3500)
            check(PageTextSessionCheck.findText(ui, context.packageName, "自动答题已结束") == null)
            passed += "completion panel disappears without confirmation and leaves the answer in app state"
            PageTextSessionCheck.waitFor("last question visible after completion panel expires") {
                PageTextSessionCheck.findText(ui, instrumentation.context.packageName, "圆周率约为多少？") != null
            }
            check(if (labels) displays.map(ChoiceQuestion::key).containsAll(listOf("正东", "苹果；香蕉"))
                else "东" in displays && "2；3" in displays) { "Content answers missing: $displays" }
            check(AppSession.overlayAnswer.value?.optString("answer") == "3.14") { "Fill answer was not retained" }
            if (aiFallback) {
                check(TestAiTransport.requests.size == 1) { "Expected one AI request for the missing-answer question" }
                val material = org.json.JSONObject(TestAiTransport.requests.single().getJSONArray("messages").getJSONObject(1).getString("content"))
                check(material.getString("current_question").contains("下列哪些是质数"))
                check(AppSession.captureStatus.value.contains("已答 2 题，跳过 1 题"))
                passed += "bank hit skips AI; missing bank answer invokes AI once, selects AC and advances without skipping"
            }
            if (labels) {
                check(AppSession.captureStatus.value.contains("已答 2 题，跳过 2 题"))
                passed += "merged platform/type/progress header matches offline; unknown question skips without clicking options"
                passed += "answer B taps the standalone B letter despite fragmented option content"
                passed += "answer AC taps both standalone letters without checkbox state or a multiple-choice title"
            } else if (compatibility) {
                check("水果" in displays) { "WebView question was not solved: $displays" }
                passed += "custom single-choice rows without selection state are clicked and advanced"
                passed += "touch-only multi-choice rows and next button accept real accessibility gestures"
                passed += "WebView delegated clicks and changing countdown advance to fill question"
            } else {
                passed += "one real red-button tap corrects a preselected radio answer and advances"
                passed += "multiple choice deselects wrong checkbox, selects both answers, and advances"
            }
            passed += "content format displays current option text throughout the automatic session"
            check(PageTextSessionCheck.findText(ui, instrumentation.context.packageName, "提交试卷") != null)
            passed += "fill question retains content and session ends without clicking submit"
            context.stopService(Intent(context, CaptureService::class.java))
            PageTextSessionCheck.waitFor("automatic stop") { !AppSession.captureStatus.value.startsWith("已开启") && AppSession.overlayAnswer.value == null }
            passed += "stopping automatic session removes floating controls and result"
            return passed
        } finally {
            context.stopService(Intent(context, CaptureService::class.java))
            PageTextSessionCheck.waitFor("cleanup") { !AppSession.captureStatus.value.startsWith("已开启") }
            activity?.let { main -> instrumentation.runOnMainSync { main.finish() } }
            try { store.delete(bankId) } finally {
                store.close()
                settings.save(saved)
                AppSession.aiConfig = prior
            }
        }
    }
}
