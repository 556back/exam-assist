package cn.examassist.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Rect
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** No event text is read or retained; reads belong to a user-started request or automatic session. */
class PageTextService : AccessibilityService() {
    companion object {
        private const val MAX_NODES = 2000
        private const val MAX_DEPTH = 64
        private const val MAX_CHARACTERS = 20000
        @Volatile private var connected: PageTextService? = null
        val isConnected: Boolean get() = connected != null

        suspend fun readCurrentQuestion(ownPackage: String): String = withCurrentRoot(ownPackage) { root ->
            val context = currentCoroutineContext()
            extract(root, ownPackage) { context.ensureActive() }
        }

        internal suspend fun readAnswerPage(ownPackage: String): AnswerPage = withCurrentRoot(ownPackage) { root ->
            val context = currentCoroutineContext()
            PageAutomation.inspect(root, ownPackage) { context.ensureActive() }
        }

        internal suspend fun clickAnswerPage(ownPackage: String, expected: AnswerPage, label: String?, selected: Boolean = true) {
            fun target(current: AnswerPage): PageTarget? {
                if (current.identity != expected.identity) throw ApiException("当前题目已变化，已暂停自动操作。")
                val target = if (label == null) {
                    if (current.options.map { Triple(it.label, it.selected, it.checkable) } !=
                        expected.options.map { Triple(it.label, it.selected, it.checkable) })
                        throw ApiException("选中状态发生变化，已暂停自动翻题。")
                    current.next ?: throw ApiException("未找到下一题，已暂停。")
                } else {
                    val option = current.options.singleOrNull { it.label == label }
                        ?: throw ApiException("选项无法唯一定位，已暂停。")
                    if (option.checkable && option.selected == selected) return null
                    option.target
                }
                if (!target.enabled) throw ApiException("目标选项或下一题不可点击，已暂停。")
                return target
            }
            val accepted = withCurrentRoot(ownPackage) { root ->
                val context = currentCoroutineContext()
                val current = PageAutomation.inspect(root, ownPackage) { context.ensureActive() }
                val located = target(current) ?: return@withCurrentRoot true
                PageAutomation.click(root, located) { context.ensureActive() }
            }
            if (accepted) return
            // Re-read the owner, question and bounds immediately before the fallback tap.
            withCurrentRoot(ownPackage) { root ->
                val context = currentCoroutineContext()
                val current = PageAutomation.inspect(root, ownPackage) { context.ensureActive() }
                val located = target(current) ?: return@withCurrentRoot
                val bounds = located.bounds?.takeIf { it.usable }
                    ?: throw ApiException("页面未提供可点击的位置，请完整显示选项后重试。")
                val x = (bounds.left + bounds.right) / 2f
                val y = (bounds.top + bounds.bottom) / 2f
                val windowBounds = Rect().also(root::getBoundsInScreen)
                if (!windowBounds.contains(x.toInt(), y.toInt())) throw ApiException("目标不在当前题目窗口内，已暂停。")
                val window = root.window
                val displayId = try { if (Build.VERSION.SDK_INT >= 30) window?.displayId ?: 0 else 0 }
                    finally { recycleWindow(window) }
                val service = connected ?: throw ApiException("无障碍服务已断开，请重新开启。")
                val completed = withContext(Dispatchers.Main) {
                    withTimeoutOrNull(2000) {
                        suspendCancellableCoroutine<Boolean> { continuation ->
                            val path = Path().apply { moveTo(x, y) }
                            val builder = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                            if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(displayId)
                            val callback = object : AccessibilityService.GestureResultCallback() {
                                override fun onCompleted(gestureDescription: GestureDescription?) {
                                    if (continuation.isActive) continuation.resume(true)
                                }
                                override fun onCancelled(gestureDescription: GestureDescription?) {
                                    if (continuation.isActive) continuation.resume(false)
                                }
                            }
                            if (continuation.isActive && !service.dispatchGesture(builder.build(), callback, null))
                                continuation.resume(false)
                        }
                    } == true
                }
                if (!completed) throw ApiException("位置点击未完成，请在系统设置中关闭再开启 R答题助手无障碍服务后重试。")
            }
        }

        private suspend fun <T> withCurrentRoot(ownPackage: String, block: suspend (AccessibilityNodeInfo) -> T): T = withContext(Dispatchers.IO) {
            val service = connected ?: throw ApiException("请先在系统无障碍设置中开启页面文字读取服务。")
            if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                throw ApiException("屏幕已锁定，已暂停，请解锁并返回题目页面。")
            val context = currentCoroutineContext()
            try {
                context.ensureActive()
                val excluded = service.getSystemService(InputMethodManager::class.java).enabledInputMethodList
                    .mapTo(mutableSetOf(ownPackage, "android", "com.android.systemui")) { it.packageName }
                val active = service.rootInActiveWindow
                if (active != null) {
                    try {
                        val window = active.window
                        val application = try { window == null || window.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                        finally { recycleWindow(window) }
                        if (application && eligible(active, excluded)) {
                            return@withContext block(active)
                        }
                    } finally { recycleNode(active) }
                }
                // A click on our overlay or the keyboard can temporarily become the active window.
                val windows = service.windows
                try {
                    val candidates = windows.filter {
                        it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused)
                    }
                        .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isActive }
                            .thenByDescending { it.isFocused }.thenByDescending { it.layer })
                    for (window in candidates) {
                        currentCoroutineContext().ensureActive()
                        val root = window.root ?: continue
                        try {
                            if (eligible(root, excluded)) {
                                return@withContext block(root)
                            }
                        } finally { recycleNode(root) }
                    }
                } finally { windows.forEach(::recycleWindow) }
                throw ApiException("没有找到可读取的题目页面，请返回题目页面、收起键盘后重试。")
            } catch (error: CancellationException) { throw error }
            catch (error: ApiException) { throw error }
            catch (_: Exception) { throw ApiException("页面文字暂时无法读取，请返回题目页面后重试。") }
        }

        private fun eligible(root: AccessibilityNodeInfo, excluded: Set<String>): Boolean =
            root.packageName?.toString()?.let { it !in excluded } == true

        /** childAt is a test seam for synthetic native nodes that are not attached to a window. */
        internal fun extract(
            root: AccessibilityNodeInfo,
            ownPackage: String,
            childAt: (AccessibilityNodeInfo, Int) -> AccessibilityNodeInfo? = { node, index -> node.getChild(index) },
            checkActive: () -> Unit = {}
        ): String {
            if (root.packageName?.toString() == ownPackage) {
                throw ApiException("请返回题目页面，再点击页面文字读取按钮。")
            }
            var slots = 1
            var characters = 0
            fun visit(node: AccessibilityNodeInfo, depth: Int): List<PageTextOrder.Piece> {
                checkActive()
                if (depth > MAX_DEPTH) {
                    throw ApiException("页面内容过多，请只显示当前题干与选项后重试。")
                }
                if (!node.isVisibleToUser || node.isPassword || node.isEditable || node.packageName?.toString() == ownPackage ||
                    (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) ||
                    (Build.VERSION.SDK_INT >= 29 && node.isTextEntryKey)) return emptyList()
                val bounds = Rect().also(node::getBoundsInScreen)
                val own = linkedSetOf<PageTextOrder.Piece>()
                for (value in listOf(node.text, node.contentDescription?.toString()?.let(PageAnalysis::descriptionText))) {
                    if (value == null) continue
                    if (value.length > MAX_CHARACTERS) throw ApiException("页面文字过多，请只显示当前题干与选项后重试。")
                    val text = value.toString().trim()
                    if (text.isEmpty()) continue
                    // Equal text at different positions may be a legitimate repeated question or option.
                    val piece = PageTextOrder.Piece(text, PageBounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
                    if (own.add(piece)) {
                        characters += text.length + 1
                        // Account for merged parent/child duplication before final text deduplication.
                        if (characters > MAX_CHARACTERS * 2) throw ApiException("页面文字过多，请只显示当前题干与选项后重试。")
                    }
                }
                if (node.childCount > MAX_NODES - slots) throw ApiException("页面内容过多，请只显示当前题干与选项后重试。")
                val children = mutableListOf<PageTextOrder.Piece>()
                for (index in 0 until node.childCount) {
                    checkActive()
                    slots++
                    val child = childAt(node, index) ?: continue
                    try { children += visit(child, depth + 1) } finally { recycleNode(child) }
                }
                val ordered = PageTextOrder.order(children.distinct())
                if (own.any { PageAnalysis.duplicatesChildren(it.text, ordered.map { it.text }) }) return ordered
                // Equal text in separate sibling rows is retained; this only removes a parent's
                // redundant description of its own descendants.
                return own.filter { piece -> ordered.none { it.text == piece.text } } + ordered
            }
            val pieces = visit(root, 0).distinct()
            return PageTextOrder.order(pieces).joinToString("\n") { it.text }
                .also { if (it.length > MAX_CHARACTERS) throw ApiException("页面文字过多，请只显示当前题干与选项后重试。") }
                .ifBlank { throw ApiException("当前 App 没有向系统提供可读取的题目文字，无法通过文字模式读取。可手动粘贴题干与选项。") }
        }

        @Suppress("DEPRECATION")
        private fun recycleNode(node: AccessibilityNodeInfo) { node.recycle() }
        @Suppress("DEPRECATION")
        private fun recycleWindow(window: AccessibilityWindowInfo?) { window?.recycle() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean {
        if (connected === this) connected = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }
}
