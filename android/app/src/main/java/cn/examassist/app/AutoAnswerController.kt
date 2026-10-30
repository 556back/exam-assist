package cn.examassist.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal data class PageBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val usable: Boolean get() = right > left && bottom > top && left >= 0 && top >= 0
}
internal data class PageTarget(val path: List<Int>, val enabled: Boolean, val bounds: PageBounds? = null, val clickAction: Boolean = true)
internal data class PageOption(val label: String, val target: PageTarget, val selected: Boolean, val checkable: Boolean, val multiple: Boolean)
internal data class AnswerPage(
    val packageName: String, val windowId: Int, val rawText: String, val question: ChoiceQuestion?,
    val options: List<PageOption>, val next: PageTarget?
) {
    val identity: String get() = "$packageName/$windowId/${question?.text ?: rawText}"
    val diagnostic: String get() = "选项定位 ${options.size}/${question?.options?.size ?: 0}；可读选中状态 ${options.count { it.checkable }}；下一题${if (next == null) "未定位" else if (next.enabled) "已定位" else "不可点击"}"
}

internal interface AutoAnswerPort {
    suspend fun read(): AnswerPage
    suspend fun solve(page: AnswerPage): JSONObject
    fun show(answer: JSONObject)
    fun progress(message: String)
    suspend fun select(page: AnswerPage, label: String, selected: Boolean)
    suspend fun next(page: AnswerPage)
}

/** Only the current floating session remembers accepted state-less clicks across pause/resume. */
internal class AutoAnswerMemory {
    private var identity: String? = null
    val clicked = mutableSetOf<String>()
    var result: JSONObject? = null
    var solveFailure: ApiException? = null
    var shown = false
    var attempts = 0
    var navigationSent = false
    fun enter(page: AnswerPage) {
        if (identity != page.identity) {
            identity = page.identity; clicked.clear()
            result = null; solveFailure = null; shown = false; attempts = 0; navigationSent = false
        }
    }
}

internal data class AutoAnswerSummary(val answered: Int, val skipped: Int) {
    val message: String get() = "自动答题已结束 · 已答 $answered 题，跳过 $skipped 题；当前页没有下一题。"
}

/** Retry page reads/actions, skip unsolvable questions, and never redispatch accepted navigation. */
internal class AutoAnswerController(private val port: AutoAnswerPort, private val format: AnswerFormat, private val pollMillis: Long = 300,
                                    private val memory: AutoAnswerMemory = AutoAnswerMemory()) {
    suspend fun run(): AutoAnswerSummary {
        val visited = mutableSetOf<String>()
        var owner: Pair<String, Int>? = null
        var answered = 0
        var skipped = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                var page = stablePage(owner = owner)
                if (owner == null) owner = page.packageName to page.windowId
                memory.enter(page)
                if (memory.navigationSent || page.identity in visited) {
                    port.progress("等待页面进入下一题 · 再点红钮停止")
                    delay(pollMillis * 4)
                    continue
                }
                var chosen: Set<String>? = null
                try {
                    chosen = answerCurrent(page)
                } catch (error: ApiException) {
                    // Revalidate before skipping; never use a stale answer or click another app.
                    page = samePage(page)
                    if (++memory.attempts < 3) {
                        port.progress("正在重新读取本题（${memory.attempts}/3） · 再点红钮停止")
                        delay(pollMillis * 2)
                        continue
                    }
                    port.progress("本题暂无法自动作答，正在跳过 · 再点红钮停止")
                }
                page = samePage(page)
                val nextPage = awaitNextEnabled(page, chosen)
                if (chosen == null) skipped++ else answered++
                if (nextPage == null) return AutoAnswerSummary(answered, skipped)
                // Record before dispatch: cancellation or an uncertain gesture callback must
                // not cause a second next click on resume and accidentally skip another page.
                memory.navigationSent = true
                visited += page.identity
                port.next(nextPage)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                port.progress("等待题目页面恢复，自动继续 · 再点红钮停止")
                delay(pollMillis * 4)
            }
        }
    }

    private suspend fun answerCurrent(initial: AnswerPage): Set<String> {
        var page = initial
        memory.solveFailure?.let { throw it }
        val result = memory.result ?: try { port.solve(page).also { memory.result = it } }
            catch (error: ApiException) { memory.solveFailure = error; throw error }
        currentCoroutineContext().ensureActive()
        page = samePage(page)
        val question = page.question
        if (question == null) {
            if (!memory.shown) { port.show(result); memory.shown = true }
            throw ApiException("当前题目没有可识别的编号选项，答案已保留；请手工填写或选择后继续。")
        }
        val chosen = question.labels(result.getString("answer"))
        if (!memory.shown) {
            port.show(JSONObject(result.toString()).put("answer", question.render(result.getString("answer"), format)))
            memory.shown = true
        }
        if (memory.clicked.any { it !in chosen })
            throw ApiException("本题的新答案与已执行的点击不一致，请手工核对后重新开启浮窗。")
        requireChosenTargets(page, chosen)
        val explicitlyMultiple = page.options.any { it.multiple } || Regex("多选|不定项").containsMatchIn(question.stem)
        if (chosen.size > 1 && !explicitlyMultiple && Regex("单选|单项选择").containsMatchIn(question.stem))
            throw ApiException("当前题目标为单选，但答案包含多个选项，已暂停，请核对答案。")
        val multiple = explicitlyMultiple || chosen.size > 1
        port.progress("正在选择答案 · 再点红钮停止")
        // For checkboxes, remove old wrong selections before adding the requested selections.
        val changes = page.options.filter { it.selected && it.label !in chosen && multiple }.map { it.label to false } +
            page.options.filter { it.label in chosen && (if (it.checkable) !it.selected else it.label !in memory.clicked) }.map { it.label to true }
        for ((label, selected) in changes) {
            page = samePage(page)
            requireChosenTargets(page, chosen)
            val option = page.options.singleOrNull { it.label == label }
                ?: throw ApiException("选项状态发生变化，已暂停，请重新开始。")
            if (!option.checkable || option.selected != selected) {
                if (!option.checkable) memory.clicked += label
                port.select(page, label, selected)
                if (option.checkable) page = awaitSelection(page, label, selected)
                else {
                    // Custom web rows may expose no state at all. Dispatch once, allow their
                    // event handler to finish, then only validate states the page actually exposes.
                    delay(pollMillis * 2)
                    page = samePage(page)
                }
            }
        }
        page = samePage(page)
        requireChosenTargets(page, chosen)
        if (page.options.any { it.checkable && it.selected != (it.label in chosen) })
            throw ApiException("无法确认选项已全部选中，已暂停，不会自动翻题。")
        return chosen
    }

    private fun requireChosenTargets(page: AnswerPage, chosen: Set<String>) {
        val missing = chosen.filter { label ->
            val option = page.options.singleOrNull { it.label == label }
            option == null || !option.target.enabled ||
                (!option.target.clickAction && option.target.bounds?.usable != true) ||
                option.target.path == page.next?.path || page.options.any { other ->
                other.label != label && (other.target.path == option.target.path ||
                    (!other.target.clickAction && !option.target.clickAction && other.target.bounds != null &&
                        other.target.bounds == option.target.bounds))
            }
        }
        if (missing.isNotEmpty()) throw ApiException("答案选项 ${missing.joinToString("、")} 无法唯一定位或不可点击，已暂停。${page.diagnostic}。请将这些选项完整显示后重试。")
    }

    private fun checkOwner(page: AnswerPage, owner: Pair<String, Int>) {
        if (page.packageName != owner.first || page.windowId != owner.second)
            throw ApiException("题目窗口已切换，已暂停，请回到题目后重新开始。")
    }
    private suspend fun samePage(expected: AnswerPage): AnswerPage {
        currentCoroutineContext().ensureActive()
        val current = port.read()
        if (current.identity != expected.identity) throw ApiException("题目在处理期间发生变化，已暂停，请重新开始。")
        return current
    }
    private suspend fun awaitSelection(expected: AnswerPage, label: String, selected: Boolean): AnswerPage {
        repeat(10) {
            delay(pollMillis)
            val page = samePage(expected)
            if (page.options.singleOrNull { it.label == label }?.selected == selected) return page
        }
        throw ApiException("点击后未确认选中状态，已暂停，请手工核对。")
    }
    private suspend fun awaitNextEnabled(expected: AnswerPage, chosen: Set<String>?): AnswerPage? {
        var found = false
        repeat(10) {
            val page = samePage(expected)
            if (chosen != null) requireChosenTargets(page, chosen)
            if (chosen != null && page.options.any { it.checkable && it.selected != (it.label in chosen) })
                throw ApiException("选中状态发生变化，已暂停自动翻题。")
            if (page.next != null) found = true
            if (page.next?.enabled == true) return page
            delay(pollMillis)
        }
        if (!found) return null
        throw ApiException("“下一题”尚不可点击，已暂停，请检查页面提示。")
    }
    private suspend fun stablePage(owner: Pair<String, Int>? = null): AnswerPage {
        var last: String? = null
        repeat(20) {
            currentCoroutineContext().ensureActive()
            val page = port.read()
            if (owner != null) checkOwner(page, owner)
            val loading = Regex("(?:正在)?加载中[.。…]*|请稍候[.。…]*|loading[.]*", RegexOption.IGNORE_CASE)
            if (page.rawText.isBlank() || (page.question == null && page.rawText.lines().any { loading.matches(it.trim()) })) {
                last = null
                delay(pollMillis)
                return@repeat
            }
            if (page.identity == last) return page
            last = page.identity
            delay(pollMillis)
        }
        throw ApiException("页面没有稳定切换到新题目，或仍未提供题目文字，已暂停，不会重复点击下一题。")
    }
}
