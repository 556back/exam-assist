package cn.examassist.app

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AutoAnswerControllerTest {
    private fun page(stem: String = "选择水果", multiple: Boolean = false, next: Boolean = true): AnswerPage {
        val question = ChoiceQuestion(stem, linkedMapOf("A" to "苹果", "B" to "白菜", "C" to "香蕉"))
        return AnswerPage("test.quiz", 1, question.text, question, question.options.keys.mapIndexed { i, label ->
            PageOption(label, PageTarget(listOf(i), true), false, true, multiple)
        }, if (next) PageTarget(listOf(3), true) else null)
    }
    private class Fake(var page: AnswerPage, var answer: String = "A") : AutoAnswerPort {
        val actions = mutableListOf<String>()
        val shown = mutableListOf<String>()
        val messages = mutableListOf<String>()
        var solves = 0
        var readFailures = 0
        var loadingReads = 0
        var nextPage: AnswerPage? = null
        var beforeRead: (() -> Unit)? = null
        var afterSolve: (() -> Unit)? = null
        var afterSelect: (() -> Unit)? = null
        var afterNext: (() -> Unit)? = null
        var solveError: ApiException? = null
        var ignoreClicks = false
        var slowSolve = false
        override suspend fun read(): AnswerPage {
            beforeRead?.invoke()
            if (readFailures-- > 0) throw ApiException("页面暂时不可读")
            if (loadingReads-- > 0) return page.copy(rawText = "学习平台\n加载中…", question = null, options = emptyList(), next = null)
            return page
        }
        override suspend fun solve(page: AnswerPage): JSONObject {
            solves++
            if (slowSolve) delay(10000)
            solveError?.let { throw it }
            afterSolve?.invoke()
            return JSONObject().put("answer", answer)
        }
        override fun show(answer: JSONObject) { shown += answer.getString("answer") }
        override fun progress(message: String) { messages += message }
        override suspend fun select(page: AnswerPage, label: String, selected: Boolean) {
            actions += "$label=$selected"
            if (!ignoreClicks) this.page = this.page.copy(options = this.page.options.map {
                it.copy(selected = if (it.label == label) selected else if (!it.multiple && selected) false else it.selected)
            })
            afterSelect?.invoke()
        }
        override suspend fun next(page: AnswerPage) {
            actions += "next"
            nextPage?.let { this.page = it }
            afterNext?.invoke()
        }
    }
    private suspend fun finish(fake: Fake, format: AnswerFormat = AnswerFormat.LABELS) =
        withTimeout(3000) { AutoAnswerController(fake, format, 1).run() }
    private suspend fun runUntil(fake: Fake, memory: AutoAnswerMemory = AutoAnswerMemory(), condition: () -> Boolean) = coroutineScope {
        val job = launch { AutoAnswerController(fake, AnswerFormat.LABELS, 1, memory).run() }
        try {
            withTimeout(3000) { while (!condition()) { check(job.isActive); delay(1) } }
            assertTrue("Session must remain active without confirmation", job.isActive)
        } finally { job.cancelAndJoin() }
    }
    @Test fun singleChoiceContinuesAndFinishesWithoutSubmitting() = runBlocking {
        val initial = page()
        val fake = Fake(initial.copy(options = initial.options.map { it.copy(selected = it.label == "B") }))
        fake.nextPage = page(stem = "第二题", next = false)
        assertEquals(AutoAnswerSummary(2, 0), finish(fake, AnswerFormat.CONTENT))
        assertEquals(listOf("A=true", "next", "A=true"), fake.actions)
        assertEquals(listOf("苹果", "苹果"), fake.shown)
        assertEquals(2, fake.solves)
    }
    @Test fun multipleChoiceKeepsCorrectSelectionAndRemovesWrongSelection() = runBlocking {
        val initial = page(multiple = true, next = false)
        val fake = Fake(initial.copy(options = initial.options.map { it.copy(selected = it.label in setOf("A", "B")) }), "AC")
        assertEquals(AutoAnswerSummary(1, 0), finish(fake))
        assertEquals(listOf("B=false", "C=true"), fake.actions)
    }
    @Test fun staleAnswerIsDiscardedAndChangedQuestionIsSolvedAutomatically() = runBlocking {
        val fake = Fake(page())
        fake.afterSolve = { fake.page = page(stem = "另一个题目", next = false); fake.afterSolve = null }
        assertEquals(AutoAnswerSummary(1, 0), finish(fake))
        assertEquals(2, fake.solves)
        assertEquals(listOf("A=true"), fake.actions)
        assertEquals(listOf("A"), fake.shown)
    }
    @Test fun anotherWindowIsNeverClickedAndReturningResumesAutomatically() = runBlocking {
        for (changed in listOf(page().copy(packageName = "another.app"), page().copy(windowId = 2))) {
            val original = page(next = false)
            val fake = Fake(original)
            fake.afterSolve = { fake.page = changed; fake.afterSolve = null }
            fake.beforeRead = {
                if (fake.messages.count { "等待题目页面恢复" in it } >= 2) {
                    assertTrue(fake.actions.isEmpty()); fake.page = original; fake.beforeRead = null
                }
            }
            assertEquals(AutoAnswerSummary(1, 0), finish(fake))
            assertEquals(listOf("A=true"), fake.actions)
            assertEquals(1, fake.solves)
        }
    }
    @Test fun unknownStateAndResumeNeverToggleOrNavigateTwice() = runBlocking {
        val initial = page(multiple = true)
        val fake = Fake(initial.copy(options = initial.options.map { it.copy(checkable = false) }), "AC")
        val memory = AutoAnswerMemory()
        runUntil(fake, memory) { fake.messages.count { "等待页面进入下一题" in it } >= 2 }
        runUntil(fake, memory) { fake.messages.count { "等待页面进入下一题" in it } >= 4 }
        assertEquals(listOf("A=true", "C=true", "next"), fake.actions)
        assertEquals(1, fake.solves)
    }
    @Test fun failedSelectionRetriesThenSkipsWithoutBlockingNextQuestion() = runBlocking {
        val fake = Fake(page()).apply { ignoreClicks = true; nextPage = page(stem = "第二题", next = false) }
        fake.afterNext = { fake.ignoreClicks = false }
        assertEquals(AutoAnswerSummary(1, 1), finish(fake))
        assertEquals(listOf("A=true", "A=true", "A=true", "next", "A=true"), fake.actions)
        assertEquals(2, fake.solves)
    }
    @Test fun missingChosenTargetsSkipBeforeAnyOptionClick() = runBlocking {
        for (answer in listOf("C", "AC")) {
            val fake = Fake(page(multiple = true).let { it.copy(options = it.options.dropLast(1)) }, answer)
            fake.nextPage = page(stem = "第二题", multiple = true, next = false)
            assertEquals(AutoAnswerSummary(1, 1), finish(fake))
            assertEquals("next", fake.actions.first())
        }
    }
    @Test fun transientMissingTargetRecoversWithoutSolvingAgain() = runBlocking {
        val initial = page(next = false)
        val fake = Fake(initial.copy(options = initial.options.dropLast(1)), "C")
        fake.beforeRead = {
            if (fake.messages.any { "重新读取本题" in it }) { fake.page = initial; fake.beforeRead = null }
        }
        assertEquals(AutoAnswerSummary(1, 0), finish(fake))
        assertEquals(listOf("C=true"), fake.actions)
        assertEquals(1, fake.solves)
    }
    @Test fun missingUnchosenTargetsAndUnstatedMultipleTypeDoNotBlock() = runBlocking {
        val initial = page(next = false)
        for (answer in listOf("B", "AC")) {
            val chosen = answer.map { it.toString() }
            val fake = Fake(initial.copy(options = initial.options.filter { it.label in chosen }.map { it.copy(checkable = false) }), answer)
            assertEquals(AutoAnswerSummary(1, 0), finish(fake))
            assertEquals(chosen.map { "$it=true" }, fake.actions)
        }
    }
    @Test fun singleChoiceWithMultipleAnswersAndAmbiguousTargetsAreSkipped() = runBlocking {
        val initial = page(next = false)
        val b = initial.options[1]
        val invalid = listOf(initial.options + b,
            initial.options.map { if (it.label == "B") it.copy(target = it.target.copy(enabled = false)) else it },
            initial.options.map { if (it.label == "A") it.copy(target = b.target) else it },
            initial.options.map { it.copy(target = it.target.copy(bounds = PageBounds(10, 10, 40, 40), clickAction = false)) })
        for (fake in invalid.map { Fake(initial.copy(options = it), "B") } + Fake(page("单选题：选择水果", next = false), "AC")) {
            assertEquals(AutoAnswerSummary(0, 1), finish(fake))
            assertTrue(fake.actions.isEmpty())
            assertEquals(1, fake.solves)
        }
    }
    @Test fun lostTargetAfterUnknownClickDoesNotCauseASecondToggle() = runBlocking {
        val initial = page(next = false)
        val fake = Fake(initial.copy(options = initial.options.map { it.copy(checkable = false) }), "B")
        fake.afterSelect = { fake.page = fake.page.copy(options = fake.page.options.filter { it.label != "B" }) }
        assertEquals(AutoAnswerSummary(0, 1), finish(fake))
        assertEquals(listOf("B=true"), fake.actions)
    }
    @Test fun slowNavigationAndLoadingContinueWithoutDoubleNext() = runBlocking {
        val fake = Fake(page())
        fake.afterNext = { fake.loadingReads = 25 }
        fake.beforeRead = {
            if (fake.actions.contains("next") && fake.messages.any { "等待页面进入下一题" in it }) {
                fake.page = page(stem = "第二题", next = false); fake.beforeRead = null
            }
        }
        assertEquals(AutoAnswerSummary(2, 0), finish(fake))
        assertEquals(listOf("A=true", "next", "A=true"), fake.actions)
        assertEquals(2, fake.solves)
    }
    @Test fun fillShowsAnswerAndSkipsWithoutConfirmation() = runBlocking {
        val fake = Fake(page().copy(question = null, rawText = "填空：圆周率", options = emptyList()), "3.14")
        fake.nextPage = page(stem = "下一题", next = false)
        fake.afterNext = { fake.answer = "B" }
        assertEquals(AutoAnswerSummary(1, 1), finish(fake))
        assertEquals(listOf("3.14", "B"), fake.shown)
        assertEquals(listOf("next", "B=true"), fake.actions)
    }
    @Test fun bankMissAndConflictSkipThenAnswerFollowingQuestion() = runBlocking {
        for (reason in listOf("未命中题库", "题库答案冲突")) {
            val fake = Fake(page()).apply { solveError = ApiException(reason); nextPage = page(stem = "第二题", next = false) }
            fake.afterNext = { fake.solveError = null }
            assertEquals(AutoAnswerSummary(1, 1), finish(fake))
            assertEquals(listOf("next", "A=true"), fake.actions)
            assertEquals(2, fake.solves)
        }
    }
    @Test fun cancellationDuringSolvePreventsLateClicksAndResults() = runBlocking {
        val fake = Fake(page()).apply { slowSolve = true }
        runUntil(fake) { fake.solves == 1 }
        assertTrue(fake.actions.isEmpty())
        assertTrue(fake.shown.isEmpty())
    }
    @Test fun disabledNextAutomaticallyContinuesWhenEnabled() = runBlocking {
        val initial = page()
        val fake = Fake(initial.copy(next = initial.next!!.copy(enabled = false)))
        fake.nextPage = page(stem = "第二题", next = false)
        fake.beforeRead = {
            if (fake.messages.any { "等待题目页面恢复" in it } && "next" !in fake.actions)
                fake.page = fake.page.copy(next = initial.next)
        }
        assertEquals(AutoAnswerSummary(2, 0), finish(fake))
        assertEquals(listOf("A=true", "next", "A=true"), fake.actions)
        assertEquals(2, fake.solves)
    }
    @Test fun transientReadFailuresAutomaticallyRecover() = runBlocking {
        val fake = Fake(page(next = false)).apply { readFailures = 3 }
        assertEquals(AutoAnswerSummary(1, 0), finish(fake))
        assertEquals(listOf("A=true"), fake.actions)
        assertEquals(1, fake.solves)
    }
}
