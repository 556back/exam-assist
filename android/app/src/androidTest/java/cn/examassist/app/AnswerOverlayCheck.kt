package cn.examassist.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Dependency-free instrumentation check. Run only on an emulator:
 * adb -s emulator-5554 shell appops set cn.examassist.app SYSTEM_ALERT_WINDOW allow
 * adb -s emulator-5554 shell am instrument -w cn.examassist.app.test/cn.examassist.app.AnswerOverlayCheck
 * For isolated question-bank checks, add -e check bank; overlay permission is unnecessary.
 * The default overlay check records WindowManager mutations without starting the service.
 * Use -e check page_text_session for a real emulator session with explicit accessibility consent.
 */
class AnswerOverlayCheck : Instrumentation() {
    private lateinit var service: CaptureService
    private lateinit var windows: RecordingWindows
    private val passed = mutableListOf<String>()
    private var requestedCheck: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        requestedCheck = arguments?.getString("check")
        start()
    }

    override fun onStart() {
        super.onStart()
        var failure: Throwable? = null
        try {
            if (requestedCheck == "automatic") {
                passed += AutomaticSessionCheck.run(this)
            } else if (requestedCheck == "automatic_ai") {
                TestAiTransport.install()
                TestAiTransport.answer = "AC"
                passed += AutomaticSessionCheck.run(this, aiFallback = true)
            } else if (requestedCheck == "bank_ai") {
                passed += BankAiFallbackCheck.run(targetContext)
            } else if (requestedCheck == "automatic_compatibility") {
                passed += AutomaticSessionCheck.run(this, compatibility = true)
            } else if (requestedCheck == "automatic_labels") {
                passed += AutomaticSessionCheck.run(this, labels = true)
            } else if (requestedCheck == "threshold") {
                passed += MatchThresholdCheck.run(this)
            } else if (requestedCheck == "bank") {
                passed += BankImportDeviceCheck.run(this)
            } else if (requestedCheck == "screenshot") {
                passed += ScreenshotSolveCheck.run(targetContext)
            } else if (requestedCheck == "page_text") {
                passed += PageTextCheck.run(targetContext)
                passed += ProtectedPageTextCheck.run(this)
            } else if (requestedCheck == "page_text_session") {
                passed += PageTextSessionCheck.run(this)
            } else {
                check(Settings.canDrawOverlays(targetContext)) {
                    "Grant SYSTEM_ALERT_WINDOW to cn.examassist.app on the test emulator first."
                }
                main {
                    service = CaptureService()
                    ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                        .apply { isAccessible = true }.invoke(service, targetContext)
                    windows = RecordingWindows(targetContext.getSystemService(WindowManager::class.java))
                    field("windowManager").set(service, windows.proxy)
                }
                checkSizing()
                checkReplacementAndExpiry()
                checkRemeasureDoesNotRenewExpiry()
                checkManualCleanupAndStop()
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            if (::service.isInitialized) main {
                call("removeAnswer")
                handler().removeCallbacksAndMessages(null)
                (field("scope").get(service) as CoroutineScope).cancel()
            }
        }
        val results = Bundle().apply {
            putString("stream", if (failure == null) {
                "\nPASS (${passed.size} checks):\n${passed.joinToString("\n")}\n"
            } else {
                "\nFAIL after ${passed.size} checks:\n${failure.stackTraceToString()}\n"
            })
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    private fun checkSizing() = main {
        show("A")
        val smallView = panel()
        val small = windows.onlyParams()
        val bounds = if (Build.VERSION.SDK_INT >= 30) windows.real.maximumWindowMetrics.bounds else {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windows.real.defaultDisplay.getRealSize(point)
            android.graphics.Rect(0, 0, point.x, point.y)
        }
        check(small.width in 1..dp(100)) { "Short answer was not compact: ${small.width}px" }
        check(small.gravity == (Gravity.TOP or Gravity.CENTER_HORIZONTAL) && small.x == 0 && small.y == dp(8)) {
            "Answer is not top-centered."
        }
        check(small.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        check(small.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)

        show("答案内容需要按文字长度自动换行并且限制最大尺寸。".repeat(100))
        val large = windows.onlyParams()
        check(large.width > small.width && large.height > small.height) {
            "Long answer did not grow: short=${small.width}x${small.height}, long=${large.width}x${large.height}"
        }
        check(large.width <= minOf(dp(280), bounds.width() - dp(32))) { "Width exceeded cap." }
        check(large.height <= minOf(dp(240), bounds.height() / 3)) { "Height exceeded cap." }
        val text = (panel().getChildAt(0) as ScrollView).getChildAt(0) as TextView
        check(text.lineCount > 1) { "Long answer did not wrap." }
        check(windows.removed.contains(smallView) && windows.maxActive == 1) { "Old answer overlapped new answer." }

        call("showPanel", "识题失败", "请重试。", false)
        check(windows.onlyParams().height > small.height) { "Failure heading/actions were not measured." }
        call("showPanel", "", "A", true)
        check(windows.onlyParams().width > small.width) { "Copy retry actions were not measured." }
        call("removeAnswer")
        passed += "compact short answer; wrapping, width/height caps, top-center, failure/copy controls, one window"
    }

    private fun checkReplacementAndExpiry() {
        main { show("旧答案") }
        SystemClock.sleep(1200)
        val replacement = main { show("新答案"); panel() }
        SystemClock.sleep(1950)
        main {
            check(panel() === replacement && windows.active.size == 1) { "Old timeout removed the new answer." }
            check(windows.maxActive == 1) { "More than one answer window was present." }
        }
        SystemClock.sleep(1200)
        main {
            check(field("answerView").get(service) == null && windows.active.isEmpty()) {
                "Answer remained after its three-second timeout."
            }
        }
        passed += "replacement cancels old timer; replacement disappears after three seconds"
    }

    private fun checkRemeasureDoesNotRenewExpiry() {
        main { show("重新测量布局") }
        SystemClock.sleep(1500)
        main {
            val current = panel()
            val params = call("answerLayoutParams", current) as WindowManager.LayoutParams
            windows.proxy.updateViewLayout(current, params)
        }
        SystemClock.sleep(1650)
        main { check(windows.active.isEmpty()) { "Remeasuring extended the answer lifetime." } }
        passed += "layout remeasurement does not renew dismissal timer"
    }

    private fun checkManualCleanupAndStop() {
        main {
            show("手动收起")
            call("removeAnswer")
            check(windows.active.isEmpty() && field("answerView").get(service) == null)
            if (Build.VERSION.SDK_INT >= 29) {
                check(!handler().hasCallbacks(field("dismissAnswer").get(service) as Runnable)) {
                    "Manual dismissal retained its timer."
                }
            }
            show("停止前答案")
            service.onStartCommand(Intent().setAction(CaptureService.ACTION_STOP), 0, 0)
            check(field("ending").getBoolean(service)) { "Stop did not mark service as ending." }
            val before = windows.active.keys.single()
            show("停止后不可显示")
            check(windows.active.keys.single() === before) { "Stopping allowed a new answer." }
            // An unattached Service has no AMS lifecycle; reproduce its window/handler cleanup only.
            call("removeAnswer")
            handler().removeCallbacksAndMessages(null)
            check(windows.active.isEmpty())
            if (Build.VERSION.SDK_INT >= 29) {
                check(!handler().hasCallbacks(field("dismissAnswer").get(service) as Runnable))
            }
        }
        SystemClock.sleep(3150)
        main { check(windows.active.isEmpty()) { "A callback left a window after cleanup." } }
        passed += "manual dismissal cancels timer; stop rejects new answers; cleanup leaves no window/callback"
    }

    private fun show(body: String) { call("showPanel", "", body, false) }
    private fun panel() = field("answerView").get(service) as LinearLayout
    private fun handler() = field("handler").get(service) as Handler
    private fun dp(value: Int) = (value * targetContext.resources.displayMetrics.density).toInt()
    private fun field(name: String) = CaptureService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun call(name: String, vararg arguments: Any): Any? {
        val types = arguments.map { when (it) {
            is Boolean -> Boolean::class.javaPrimitiveType!!
            is LinearLayout -> LinearLayout::class.java
            else -> it.javaClass
        } }.toTypedArray()
        return try {
            CaptureService::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
                .invoke(service, *arguments)
        } catch (error: InvocationTargetException) { throw error.targetException }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private class RecordingWindows(val real: WindowManager) {
        val active = linkedMapOf<View, WindowManager.LayoutParams>()
        val removed = mutableListOf<View>()
        var maxActive = 0
        val proxy = Proxy.newProxyInstance(WindowManager::class.java.classLoader, arrayOf(WindowManager::class.java)) {
            _, method, args -> when (method.name) {
                "addView" -> {
                    val view = args!![0] as View
                    check(!active.containsKey(view)) { "View added twice." }
                    active[view] = snapshot(args[1] as WindowManager.LayoutParams)
                    maxActive = maxOf(maxActive, active.size)
                    null
                }
                "removeView", "removeViewImmediate" -> {
                    val view = args!![0] as View
                    check(active.remove(view) != null) { "Removing absent view." }
                    removed += view
                    null
                }
                "updateViewLayout" -> {
                    val view = args!![0] as View
                    check(active.containsKey(view)) { "Updating absent view." }
                    active[view] = snapshot(args[1] as WindowManager.LayoutParams)
                    null
                }
                else -> try { method.invoke(real, *(args ?: emptyArray())) }
                    catch (error: InvocationTargetException) { throw error.targetException }
            }
        } as WindowManager

        fun onlyParams() = active.values.single()
        private fun snapshot(params: WindowManager.LayoutParams) = WindowManager.LayoutParams().apply { copyFrom(params) }
    }
}
