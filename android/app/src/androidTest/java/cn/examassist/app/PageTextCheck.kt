package cn.examassist.app

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/** Synthetic native nodes and text-only layout checks; no device window is read. */
@Suppress("DEPRECATION")
internal object PageTextCheck {
    private const val OWN = "cn.examassist.app"

    fun run(context: Context): List<String> {
        val passed = mutableListOf<String>()
        Tree(context).use { tree ->
            val root = tree.node()
            val stem = tree.node("相同文字", top = 100)
            val optionA = tree.node("A. 相同文字", top = 200)
            val optionB = tree.node("B. 乙", top = 200, left = 300)
            val description = tree.node(description = "C. 丙", top = 300)
            val repeated = tree.node("相同文字", top = 400)
            tree.attach(root, repeated, optionB, description, optionA, stem,
                tree.node("A. 相同文字", top = 200))
            check(tree.read(root) == "相同文字\nA. 相同文字\nB. 乙\nC. 丙\n相同文字") {
                "Screen order, descriptions, exact duplicate suppression or repeated text were lost."
            }
        }
        passed += "page text preserves screen order, descriptions, complete options and repeated text at different positions"

        Tree(context).use { tree ->
            val root = tree.node()
            val merged = tree.node(description = "A. 东，单选按钮，未选中", top = 200)
            tree.attach(merged, tree.node("A", top = 200), tree.node("东", top = 200, left = 100))
            tree.attach(root, tree.node("太阳从哪个方向升起？", top = 100), merged,
                tree.node(description = "B. 西，未选中，单选按钮", top = 300))
            check(tree.read(root) == "太阳从哪个方向升起？\nA\n东\nB. 西") { "Merged descriptions or role/state suffixes polluted local matching." }
        }
        passed += "page text removes merged parent duplication and role/state suffixes in either order"

        Tree(context).use { tree ->
            val root = tree.node()
            tree.attach(root, tree.node("选择内容", top = 100),
                tree.node("甲", top = 200, left = 300), tree.node("A", top = 215),
                tree.node("乙", top = 300, left = 300), tree.node("B", top = 315),
                tree.node("纠错", top = 400), tree.node("下一题", top = 500))
            val raw = tree.read(root)
            check(raw == "选择内容\nA\n甲\nB\n乙\n纠错\n下一题") { "Offset label bounds changed option ownership: $raw" }
            check(ChoiceQuestion.parse(raw)?.options == mapOf("A" to "甲", "B" to "乙")) {
                "Shared choice parser included feedback controls in the last option."
            }
        }
        passed += "page text associates vertically offset option letters with their row and excludes terminal feedback controls"

        Tree(context).use { tree ->
            val root = tree.node()
            tree.attach(root, tree.node("正常题干"), tree.node("密码").apply { isPassword = true },
                tree.node("不可见").apply { isVisibleToUser = false },
                tree.node("已输入答案").apply { isEditable = true },
                tree.node("助手旧答案").apply { packageName = OWN })
            if (Build.VERSION.SDK_INT >= 34) tree.attach(root, tree.node("敏感数据").apply { isAccessibilityDataSensitive = true })
            check(tree.read(root) == "正常题干") { "Password, invisible, editable or own-app content leaked." }
            rejects { tree.read(tree.node("自身窗口").apply { packageName = OWN }) }
        }
        passed += "page text excludes passwords, invisible nodes, entered text, API 34+ sensitive data and the assistant's own window"

        Tree(context).use { tree ->
            rejects { tree.read(tree.node()) }
            rejects { tree.read(tree.node("题".repeat(20001))) }
            val root = tree.node()
            tree.attach(root, tree.node("甲".repeat(10000)), tree.node("乙".repeat(10000), top = 100))
            rejects { tree.read(root) } // The separator also counts toward the complete request limit.
            val deep = tree.node()
            var parent = deep
            repeat(65) { tree.node().also { tree.attach(parent, it); parent = it } }
            rejects { tree.read(deep) }
            val wide = tree.node()
            repeat(2000) { tree.attach(wide, tree.node()) }
            rejects { tree.read(wide) }
        }
        passed += "empty, excessive-character, excessive-node and excessive-depth page reads fail without truncating question content"

        Tree(context).use { tree ->
            val root = tree.node("正常题干")
            var cancelled = false
            try { PageTextService.extract(root, OWN, checkActive = { throw CancellationException("cancelled") }) }
            catch (_: CancellationException) { cancelled = true }
            check(cancelled) { "Page read swallowed cancellation." }
        }
        passed += "page text extraction propagates cancellation"
        val service = CaptureService()
        try {
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, context)
            fun field(name: String) = CaptureService::class.java.getDeclaredField(name).apply { isAccessible = true }
            field("windowManager").set(service, context.getSystemService(WindowManager::class.java))
            field("pageTextMode").setBoolean(service, true)
            val resize = CaptureService::class.java.getDeclaredMethod("resizeReader", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            resize.invoke(service, 1080, 2400)
            resize.invoke(service, 2400, 1080)
            check(field("reader").get(service) == null && field("projection").get(service) == null) {
                "Text-only rotation allocated screenshot resources."
            }
            passed += "text-only portrait/landscape layout changes never create an ImageReader or MediaProjection"
        } finally {
            (CaptureService::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(service) as CoroutineScope).cancel()
        }
        return passed
    }

    private fun rejects(read: () -> String) {
        var rejected = false
        try { read() } catch (_: ApiException) { rejected = true }
        check(rejected) { "Invalid or incomplete page text was accepted." }
    }

    private class Tree(private val context: Context) : AutoCloseable {
        private val nodes = mutableListOf<AccessibilityNodeInfo>()
        private val children = mutableMapOf<String, MutableList<AccessibilityNodeInfo>>()
        fun node(text: String? = null, top: Int = 0, left: Int = 0, description: String? = null): AccessibilityNodeInfo =
            AccessibilityNodeInfo.obtain().apply {
                packageName = "cn.examassist.synthetic"
                viewIdResourceName = "cn.examassist.synthetic:id/node_${nodes.size}"
                isVisibleToUser = true
                this.text = text
                contentDescription = description
                setBoundsInScreen(Rect(left, top, left + 250, top + 50))
                nodes += this
            }
        fun attach(parent: AccessibilityNodeInfo, vararg added: AccessibilityNodeInfo) {
            added.forEach {
                parent.addChild(View(context))
                children.getOrPut(parent.viewIdResourceName) { mutableListOf() } += it
            }
        }
        fun read(root: AccessibilityNodeInfo): String = PageTextService.extract(root, OWN,
            childAt = { parent, index -> children[parent.viewIdResourceName]?.getOrNull(index)?.let { AccessibilityNodeInfo.obtain(it) } })
        override fun close() { nodes.forEach { it.recycle() } }
    }
}
