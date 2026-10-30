package cn.examassist.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

class CaptureService : Service() {
    companion object {
        const val RESULT_CODE = "result_code"
        const val RESULT_DATA = "result_data"
        const val ACTION_STOP = "cn.examassist.STOP_CAPTURE"
        private const val CHANNEL = "capture_session"
        private const val NOTIFICATION_ID = 41
    }
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var banks: BankStore
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureButton: Button? = null
    private var controlsParams: WindowManager.LayoutParams? = null
    private var answerView: LinearLayout? = null
    private val dismissAnswer = Runnable { removeAnswer() }
    private var captureRequested = false
    private var busy = false
    private var ending = false
    private var ready = false
    private var pageTextMode = false
    private var width = 0
    private var height = 0
    private var density = 0
    private var requestConfig = AiConfig()
    private var requestStarted = 0L
    private var autoJob: Job? = null
    private val automaticMemory = AutoAnswerMemory()
    private val captureTimeout = Runnable {
        if (captureRequested) {
            detachCapture()
            busy = false
            revealButton()
            showFailure("未收到画面，请重新开启浮窗；受保护页面可尝试「读取页面文字」。")
        }
    }
    private val permissionCheck = object : Runnable {
        override fun run() {
            if (!Settings.canDrawOverlays(this@CaptureService)) {
                end("浮窗权限已关闭，请重新授权后开启。")
            } else if (pageTextMode && !PageTextService.isConnected) {
                end("页面文字读取服务已关闭，请重新授权后开启浮窗。")
            } else if (!ending) handler.postDelayed(this, 1500)
        }
    }
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { end("屏幕捕获已结束，请重新授权后开启。") }
        override fun onCapturedContentResize(newWidth: Int, newHeight: Int) {
            if (ready && !ending) resizeReader(newWidth, newHeight)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        banks = BankStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "浮窗识题", NotificationManager.IMPORTANCE_LOW)
        )
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { end("浮窗已停止。"); return START_NOT_STICKY }
        if (ready || projection != null) return START_NOT_STICKY
        if (!Settings.canDrawOverlays(this)) { end("请先授予浮窗权限。"); return START_NOT_STICKY }
        Api.validateAnswerMode(AppSession.aiConfig)?.let { end(it); return START_NOT_STICKY }
        pageTextMode = AppSession.aiConfig.usesPageText
        if (pageTextMode && !PageTextService.isConnected) {
            end("请先在系统无障碍设置中开启页面文字读取服务。")
            return START_NOT_STICKY
        }
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(RESULT_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") (intent?.getParcelableExtra(RESULT_DATA) as? Intent)
        if (!pageTextMode && data == null) { end("屏幕捕获授权无效，请在 App 中重新开启。"); return START_NOT_STICKY }
        try {
            if (pageTextMode) {
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), 0)
                else startForeground(NOTIFICATION_ID, notification())
            } else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(NOTIFICATION_ID, notification())
            val size = displaySize()
            width = size.x; height = size.y; density = resources.configuration.densityDpi
            if (!pageTextMode) {
                projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(
                    intent!!.getIntExtra(RESULT_CODE, android.app.Activity.RESULT_CANCELED), data!!
                ) ?: throw IllegalStateException("Projection missing")
                projection!!.registerCallback(projectionCallback, handler)
                reader = createReader(width, height)
                // 空闲时不连接截图 surface；用户点击并隐藏自身浮窗后才读取一帧。
                virtualDisplay = projection!!.createVirtualDisplay("ExamAssistOneTapCapture", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, handler)
            }
            addButton()
            ready = true
            AppSession.overlayAnswer.value = null
            status(if (AppSession.aiConfig.autoAnswerEnabled) "全自动模式 · 点红钮开始，再点停止"
                else if (pageTextMode) "页面文字模式 · 点红色按钮解答" else "点红色按钮自动解答")
            handler.post(permissionCheck)
        } catch (_: Exception) { end(if (pageTextMode) "无法开启文字识题浮窗，请检查系统无障碍及浮窗权限。" else "无法开启屏幕捕获，请停止其他捕获后重新授权。") }
        return START_NOT_STICKY
    }
    private fun displaySize(): Point = if (Build.VERSION.SDK_INT >= 30) {
        windowManager.maximumWindowMetrics.bounds.let { Point(it.width(), it.height()) }
    } else Point().also {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(it)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if ((pageTextMode || Build.VERSION.SDK_INT < 34) && ready && !ending) {
            val size = displaySize()
            resizeReader(size.x, size.y)
        }
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("${getString(R.string.app_name)}浮窗已开启")
            .setContentText(if (AppSession.aiConfig.autoAnswerEnabled) "全自动选项并翻题；点红钮开始或停止。" else "点红色按钮自动识题、解答并复制答案。")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "停止浮窗", stop)
            .build()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun drawable(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun layoutParams(w: Int, h: Int, x: Int = 0, y: Int = 0) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.LEFT; this.x = x; this.y = y }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 14f; contentDescription = text
        minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(AppColors.Ink.toArgb())
        setOnClickListener { action() }
    }
    private fun addButton() {
        val capture = button("答", ::capture).apply {
            textSize = 20f
            setPadding(0, 0, 0, 0)
            setTextColor(Color.WHITE)
            backgroundTintList = null
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AppColors.Capture.toArgb()) }
            elevation = dp(4).toFloat()
            contentDescription = "识题并自动解答，可拖动移动"
        }
        val params = layoutParams(dp(56), dp(56), (width - dp(68)).coerceAtLeast(0), (height - dp(152)).coerceAtLeast(0))
        captureButton = capture
        controlsParams = params
        val moveLeft = View.generateViewId()
        val moveRight = View.generateViewId()
        capture.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(moveLeft, "移到左侧"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(moveRight, "移到右侧"))
            }
            override fun performAccessibilityAction(host: View, action: Int, arguments: Bundle?): Boolean {
                if (action != moveLeft && action != moveRight) return super.performAccessibilityAction(host, action, arguments)
                params.x = if (action == moveLeft) dp(8) else (width - dp(64)).coerceAtLeast(0)
                params.y = params.y.coerceIn(0, (height - dp(80)).coerceAtLeast(0))
                runCatching { windowManager.updateViewLayout(capture, params) }
                return true
            }
        }
        var originX = 0
        var originY = 0
        var rawX = 0f
        var rawY = 0f
        var moving = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        capture.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    originX = params.x; originY = params.y; rawX = event.rawX; rawY = event.rawY; moving = false
                    capture.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - rawX
                    val dy = event.rawY - rawY
                    if (abs(dx) > slop || abs(dy) > slop) moving = true
                    if (moving) {
                        capture.isPressed = false
                        params.x = (originX + dx.toInt()).coerceIn(0, (width - dp(56)).coerceAtLeast(0))
                        params.y = (originY + dy.toInt()).coerceIn(0, (height - dp(80)).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(capture, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { capture.isPressed = false; if (!moving) capture.performClick(); true }
                MotionEvent.ACTION_CANCEL -> { capture.isPressed = false; moving = false; true }
                else -> false
            }
        }
        windowManager.addView(capture, params)
    }
    private fun createReader(w: Int, h: Int) = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).also {
        it.setOnImageAvailableListener(::onFrame, handler)
    }
    private fun onFrame(source: ImageReader) {
        if (ending || source !== reader) return
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        if (!captureRequested) { image.close(); return }
        detachCapture()
        revealButton()
        // ImageReader.close 会使已取得的 Image 失效；在同一 handler 上复制后立即关闭。
        val captured = try { frameBitmap(image) }
        catch (_: Exception) {
            busy = false
            revealButton()
            showFailure("截图失败，请重新开启浮窗后重试。")
            return
        } finally { image.close() }
        val capturedAt = SystemClock.elapsedRealtime()
        scope.launch {
            try {
                val config = requestConfig
                val answer = QuestionSolver.solve(config, captured, banks, ::status)
                ensureActive()
                AppSession.overlayAnswer.value = answer
                val copied = copyAnswer(answer)
                showAnswer(answer, copied)
                if (BuildConfig.DEBUG) Log.d("CaptureTiming", "ocr=${config.usesOcr} capture_ms=${capturedAt - requestStarted} " +
                    "solve_ms=${SystemClock.elapsedRealtime() - capturedAt} total_ms=${SystemClock.elapsedRealtime() - requestStarted}")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!ending) showFailure(e.message ?: "识题失败，请检查网络后重试。") }
            finally {
                captured.recycle()
                busy = false
                revealButton()
            }
        }
    }
    private fun frameBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val paddedWidth = image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        try {
            padded.copyPixelsFromBuffer(plane.buffer)
            return Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                .also { if (it !== padded) padded.recycle() }
        } catch (e: Exception) { padded.recycle(); throw e }
    }
    private fun resizeReader(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == width && h == height)) return
        try {
            width = w; height = h
            if (!pageTextMode) {
                val old = reader
                val next = createReader(w, h)
                reader = next
                virtualDisplay?.resize(w, h, density)
                virtualDisplay?.surface = if (captureRequested) next.surface else null
                old?.close()
            }
            controlsParams?.let { params ->
                params.x = params.x.coerceIn(0, (width - dp(56)).coerceAtLeast(0))
                params.y = params.y.coerceIn(0, (height - dp(80)).coerceAtLeast(0))
                captureButton?.let { windowManager.updateViewLayout(it, params) }
            }
            answerView?.let { windowManager.updateViewLayout(it, answerLayoutParams(it)) }
        } catch (_: Exception) { end("页面尺寸变化后捕获结束，请重新授权。") }
    }
    private fun capture() {
        if (autoJob != null) {
            autoJob?.cancel()
            status("自动答题已停止 · 点红钮继续")
            return
        }
        if (!ready || ending || busy) return
        if (!Settings.canDrawOverlays(this)) { end("浮窗权限已关闭。"); return }
        requestConfig = AppSession.aiConfig
        Api.validateAnswerMode(requestConfig)?.let { showFailure(it); return }
        if (requestConfig.autoAnswerEnabled) { startAutomatic(); return }
        requestStarted = SystemClock.elapsedRealtime()
        busy = true
        AppSession.overlayAnswer.value = null
        removeAnswer()
        captureButton?.visibility = View.INVISIBLE
        status(if (pageTextMode) "正在读取页面文字" else "正在读取页面", if (pageTextMode) "文字" else "截图")
        // 先等待浮窗从屏幕消失，再连接 surface，避免自身按钮和旧答案入图。
        handler.postDelayed({
            if (!ending && ready && busy) {
                if (pageTextMode) {
                    readPageText()
                    return@postDelayed
                }
                try {
                    reader?.acquireLatestImage()?.close()
                    captureRequested = true
                    virtualDisplay?.surface = reader?.surface
                    handler.postDelayed(captureTimeout, 5000)
                } catch (_: Exception) {
                    detachCapture(); busy = false; revealButton()
                    showFailure("截图失败，请重新开启浮窗后重试。")
                }
            }
        }, 250)
    }
    private fun startAutomatic() {
        busy = true
        AppSession.overlayAnswer.value = null
        removeAnswer()
        autoJob = scope.launch {
            try {
                status("正在读取题目 · 再点红钮停止", "停")
                val config = requestConfig
                val summary = AutoAnswerController(object : AutoAnswerPort {
                    override suspend fun read(): AnswerPage = PageTextService.readAnswerPage(packageName)
                    override suspend fun solve(page: AnswerPage): JSONObject {
                        AppSession.overlayAnswer.value = null
                        removeAnswer()
                        return QuestionSolver.solveText(config.copy(answerFormat = AnswerFormat.LABELS),
                            page.rawText, banks, ::status)
                    }
                    override fun show(answer: JSONObject) {
                        AppSession.overlayAnswer.value = answer
                        showAnswer(answer, copyAnswer(answer))
                    }
                    override fun progress(message: String) = status(message, "停")
                    override suspend fun select(page: AnswerPage, label: String, selected: Boolean) =
                        automaticClick(page, label, selected)
                    override suspend fun next(page: AnswerPage) = automaticClick(page, null)
                }, config.answerFormat, memory = automaticMemory).run()
                status(summary.message)
                showPanel("自动答题已结束", listOfNotNull(
                    AppSession.overlayAnswer.value?.optString("answer")?.let { "答案：$it" }, summary.message).joinToString("\n\n"))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!ending) {
                    status("自动答题已停止 · ${error.message ?: "自动操作失败"}")
                    val answer = AppSession.overlayAnswer.value?.optString("answer")
                    showPanel("自动答题已停止", listOfNotNull(answer?.let { "答案：$it" },
                        error.message ?: "自动操作失败，请检查题目页面。", "点红钮重新开始。").joinToString("\n\n"))
                    handler.removeCallbacks(dismissAnswer)
                }
            } finally {
                autoJob = null
                busy = false
                revealButton()
            }
        }
    }
    private suspend fun automaticClick(page: AnswerPage, label: String?, selected: Boolean = true) {
        // Overlays can intercept gestures or make protected touch targets reject obscured taps.
        captureButton?.visibility = View.INVISIBLE
        answerView?.visibility = View.INVISIBLE
        try {
            kotlinx.coroutines.delay(100)
            PageTextService.clickAnswerPage(packageName, page, label, selected)
        } finally {
            if (!ending) {
                captureButton?.visibility = View.VISIBLE
                answerView?.visibility = View.VISIBLE
            }
        }
    }
    private fun readPageText() {
        scope.launch {
            try {
                val question = PageTextService.readCurrentQuestion(packageName)
                revealButton()
                val answer = QuestionSolver.solveText(requestConfig, question, banks, ::status)
                ensureActive()
                AppSession.overlayAnswer.value = answer
                showAnswer(answer, copyAnswer(answer))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!ending) showFailure(e.message ?: "页面文字读取失败，请完整显示题目后重试。") }
            finally { busy = false; revealButton() }
        }
    }
    private fun detachCapture() {
        captureRequested = false
        handler.removeCallbacks(captureTimeout)
        runCatching { virtualDisplay?.surface = null }
    }
    private fun status(text: String, short: String? = null) {
        AppSession.captureStatus.value = "已开启浮窗 · $text"
        captureButton?.apply {
            this.text = if (autoJob?.isActive == true) "停" else short ?: "答"
            textSize = if (short == null) 20f else 12f
            contentDescription = if (autoJob?.isActive == true) "$text，点击停止自动答题"
                else if (short == null) idleDescription() else text
        }
    }
    private fun revealButton() {
        if (ending) return
        captureButton?.visibility = View.VISIBLE
        if (!busy) captureButton?.apply { text = "答"; textSize = 20f; contentDescription = idleDescription() }
    }
    private fun idleDescription() = if (AppSession.aiConfig.autoAnswerEnabled) "开始自动答题，可拖动移动" else "识题并自动解答，可拖动移动"
    private fun copyAnswer(answer: JSONObject): Boolean = runCatching {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("题目答案", answer.getString("answer")))
    }.isSuccess
    private fun showAnswer(answer: JSONObject, copied: Boolean) {
        status(if (copied) "答案已复制" else "答案已显示，剪贴板复制失败")
        showPanel("", answer.getString("answer"), !copied)
    }
    private fun showFailure(message: String) {
        status("识题失败，点红色按钮重试")
        showPanel("识题失败", "$message\n\n点红色按钮重试。")
    }
    private fun showPanel(title: String, body: String, retryCopy: Boolean = false) {
        if (ending || !Settings.canDrawOverlays(this)) return
        removeAnswer()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = drawable(AppColors.Surface.toArgb())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            elevation = dp(6).toFloat()
        }
        if (title.isNotBlank()) panel.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(AppColors.Primary.toArgb())
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(this)
        scroll.addView(TextView(this).apply {
            text = body; textSize = 16f; minHeight = dp(32)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, if (title.isBlank()) 0 else dp(8), 0, 0)
            setTextColor(AppColors.Ink.toArgb()); setTextIsSelectable(true)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f))
        if (retryCopy || title.isNotBlank()) {
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (retryCopy) actions.addView(button("复制答案") {
                AppSession.overlayAnswer.value?.let { showAnswer(it, copyAnswer(it)) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(button("收起", ::removeAnswer), LinearLayout.LayoutParams(0, dp(48), 1f))
            panel.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        try {
            windowManager.addView(panel, answerLayoutParams(panel))
            answerView = panel
            handler.postDelayed(dismissAnswer, 3000)
        } catch (_: Exception) { end("浮窗无法显示，请重新检查浮窗权限。") }
    }
    private fun answerLayoutParams(panel: LinearLayout): WindowManager.LayoutParams {
        val size = displaySize()
        val maxWidth = minOf(dp(280), (size.x - dp(32)).coerceAtLeast(1))
        val maxHeight = minOf(dp(240), (size.y / 3).coerceAtLeast(1))
        panel.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        return layoutParams(panel.measuredWidth, panel.measuredHeight, y = dp(8)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
    }
    private fun removeAnswer() {
        handler.removeCallbacks(dismissAnswer)
        answerView?.let { runCatching { windowManager.removeView(it) } }
        answerView = null
    }
    private fun end(message: String) {
        if (ending) return
        ending = true
        autoJob?.cancel()
        AppSession.captureStatus.value = message
        stopSelf()
    }
    override fun onDestroy() {
        ending = true; ready = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        removeAnswer()
        captureButton?.let { runCatching { windowManager.removeView(it) } }
        captureButton = null
        virtualDisplay?.release()
        reader?.close()
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        banks.close()
        AppSession.overlayAnswer.value = null
        if (AppSession.captureStatus.value.startsWith("已开启")) AppSession.captureStatus.value = "浮窗已停止。"
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
