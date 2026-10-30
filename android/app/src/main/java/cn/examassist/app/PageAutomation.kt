package cn.examassist.app

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/** Node handles never escape the read/action; each click re-resolves the current window. */
internal object PageAutomation {
    fun inspect(root: AccessibilityNodeInfo, ownPackage: String, checkActive: () -> Unit): AnswerPage {
        var count = 0
        var characters = 0
        fun visit(node: AccessibilityNodeInfo, path: List<Int>, parentEnabled: Boolean): PageNode? {
            checkActive()
            if (++count > 2000 || path.size > 64) throw ApiException("页面内容过多，已暂停自动操作。")
            if (!node.isVisibleToUser || node.isPassword || node.isEditable || node.packageName?.toString() == ownPackage ||
                (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) ||
                (Build.VERSION.SDK_INT >= 29 && node.isTextEntryKey)) return null
            val values = listOfNotNull(node.text?.toString()?.trim(), node.contentDescription?.toString()?.let(PageAnalysis::descriptionText))
                .filter(String::isNotBlank).distinct()
            characters += values.sumOf { it.length }
            if (characters > 40000 || node.childCount > 2000 - count) throw ApiException("页面内容过多，已暂停自动操作。")
            val described = PageAnalysis.describedState(if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString() else null)
                ?: PageAnalysis.describedState(node.contentDescription?.toString()?.substringAfterLast('，')?.substringAfterLast(','))
            val state = when {
                node.isCheckable -> node.isChecked
                described != null -> described
                node.isSelected -> true
                // ACTION_SELECT is also advertised by ordinary TextViews; it is not evidence
                // that this is a choice control with an observable unchecked state.
                else -> null
            }
            val bounds = Rect().also(node::getBoundsInScreen)
            val target = PageTarget(path, parentEnabled && node.isEnabled,
                PageBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            val children = mutableListOf<PageNode>()
            repeat(node.childCount) { index ->
                val child = node.getChild(index) ?: return@repeat
                try { visit(child, path + index, target.enabled)?.let(children::add) } finally { recycle(child) }
            }
            return PageNode(target, values, state, node.className?.toString().orEmpty().contains("CheckBox", true), children)
        }
        val snapshot = visit(root, emptyList(), true) ?: throw ApiException("当前页面无法读取，请返回题目页面。")
        return PageAnalysis.inspect(snapshot, root.packageName.toString(), root.windowId)
    }

    /** false means no click was accepted and a freshly located gesture may be attempted. */
    fun click(root: AccessibilityNodeInfo, target: PageTarget, checkActive: () -> Unit): Boolean {
        if (!target.clickAction) return false
        val acquired = mutableListOf<AccessibilityNodeInfo>()
        try {
            var node = root
            for (index in target.path) {
                checkActive()
                node = node.getChild(index)?.also(acquired::add) ?: throw ApiException("页面布局发生变化，已暂停。")
            }
            checkActive()
            if (!node.isVisibleToUser || !node.isEnabled) throw ApiException("目标当前不可点击，已暂停。")
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally { acquired.asReversed().forEach(::recycle) }
    }
    @Suppress("DEPRECATION") private fun recycle(node: AccessibilityNodeInfo) = node.recycle()
}
