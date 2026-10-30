package cn.examassist.app

import org.junit.Assert.*
import org.junit.Test

class PageAnalysisTest {
    private fun node(path: List<Int>, text: String = "", click: Boolean = false, state: Boolean? = null,
                     enabled: Boolean = true, children: List<PageNode> = emptyList()) = PageNode(
        PageTarget(path, enabled, PageBounds(10, 100 + path.sum() * 60, 600, 150 + path.sum() * 60), click),
        listOf(text).filter(String::isNotBlank), state, children = children)
    private fun inspect(rows: List<PageNode>, next: PageNode = node(listOf(5), "下一题 (2/10)", true)) =
        PageAnalysis.inspect(node(emptyList(), children = listOf(node(listOf(0), "太阳从哪个方向升起？")) + rows + next), "test.quiz", 3)

    @Test fun customTextRowsWorkWithoutCheckableFlags() {
        val page = inspect(listOf(node(listOf(1), "A. 东", true), node(listOf(2), "B. 西", true)))
        assertEquals(listOf("A", "B"), page.options.map { it.label })
        assertTrue(page.options.none { it.checkable })
        assertEquals(listOf(5), page.next!!.path)
        assertEquals("西", page.question!!.options["B"])
    }
    @Test fun nonClickableVisibleTextHasGestureTarget() {
        val page = inspect(listOf(node(listOf(1), "A. 东"), node(listOf(2), "B. 西")), node(listOf(5), "下一题"))
        assertEquals(2, page.options.size)
        assertTrue(page.options.none { it.target.clickAction })
        assertTrue(page.options.all { it.target.bounds?.usable == true })
        assertFalse(page.next!!.clickAction)
    }
    @Test fun splitSiblingLabelAndUnlabelledRadioUseRadioActionAndState() {
        val rows = listOf("东", "西").mapIndexed { index, text ->
            val p = listOf(index + 1)
            node(p, children = listOf(node(p + 0, ('A' + index).toString()), node(p + 1, text), node(p + 2, click = true, state = index == 1)))
        }
        val page = inspect(rows)
        assertEquals(listOf(listOf(1, 2), listOf(2, 2)), page.options.map { it.target.path })
        assertTrue(page.options.all { it.checkable })
        assertEquals(listOf(false, true), page.options.map { it.selected })
    }
    @Test fun mergedParentTextAndDuplicateStateDoNotInvalidateAnOption() {
        val rows = listOf("东", "西").mapIndexed { index, text ->
            val p = listOf(index + 1)
            node(p, "${'A' + index}. $text", true, state = false, children = listOf(node(p + 0, "${'A' + index}. $text", true, state = false)))
        }
        val page = inspect(rows)
        assertEquals(2, page.options.size)
        assertTrue(page.options.all { it.checkable })
        assertEquals("东", page.question!!.options["A"])
    }
    @Test fun splitNextButtonUsesClickableAncestorAndKeepsDisabledState() {
        val next = node(listOf(5), click = true, enabled = false, children = listOf(node(listOf(5, 0), "下一题"), node(listOf(5, 1), "2/10")))
        val page = inspect(listOf(node(listOf(1), "A. 东", true), node(listOf(2), "B. 西", true)), next)
        assertEquals(listOf(5), page.next!!.path)
        assertFalse(page.next!!.enabled)
    }
    @Test fun mergedDescriptionWithPunctuationDoesNotDuplicateSplitLabelAndText() {
        val rows = listOf("东", "西").mapIndexed { index, text ->
            val p = listOf(index + 1)
            node(p, "${'A' + index}. $text", true, children = listOf(node(p + 0, ('A' + index).toString()), node(p + 1, text)))
        }
        val page = inspect(rows)
        assertEquals(2, page.options.size)
        assertEquals("西", page.question!!.options["B"])
    }
    @Test fun changingTimerAndDecoratedNextAreOutsideQuestionIdentity() {
        val first = ChoiceQuestion.parse("练习\n剩余时间：12:30\n太阳从哪个方向升起？\n(A) 东\n(B) 西\n下一题（2/10）")!!
        val second = ChoiceQuestion.parse("练习\n剩余时间：12:29\n太阳从哪个方向升起？\n(A) 东\n(B) 西\n下一题（2/10）")!!
        assertEquals(first, second)
        assertEquals("西", second.options["B"])
        for (label in listOf("提交并下一题", "交卷", "下一题并提交", "下一题答案", "下一题错误")) assertFalse(ChoiceQuestion.isNext(label))
        assertTrue(ChoiceQuestion.isNext("下一题 2/10 >"))
    }
    @Test fun stateAndRoleDescriptionsDoNotBecomeOptionContent() {
        assertEquals("A. 东", PageAnalysis.descriptionText("A. 东，未选中，单选按钮"))
        assertEquals("A. 东", PageAnalysis.descriptionText("A. 东，单选按钮，未选中"))
        assertEquals("A. 东", PageAnalysis.descriptionText("A. 东, radio button, not checked"))
        assertEquals("", PageAnalysis.descriptionText("未选中"))
        assertEquals(false, PageAnalysis.describedState("未选中"))
        assertEquals(null, PageAnalysis.describedState("普通文本"))
    }
    @Test fun fragmentedOptionContentUsesStandaloneLetterPosition() {
        val page = inspect(listOf(node(listOf(1), "A."), node(listOf(2), "正"), node(listOf(3), "东"),
            node(listOf(4), "B."), node(listOf(6), "正"), node(listOf(7), "西")))
        assertEquals(listOf("A", "B"), page.options.map { it.label })
        assertEquals(listOf(4), page.options.single { it.label == "B" }.target.path)
        assertFalse(page.options.single { it.label == "B" }.target.clickAction)
        assertEquals("正西", ChoiceQuestion.key(page.question!!.options.getValue("B")))
    }
    @Test fun incompleteTextMatchStillLocatesLastOptionByItsLetter() {
        val page = inspect(listOf(node(listOf(1), "A. 东"), node(listOf(2), "B. 西"),
            node(listOf(3), "C. 南"), node(listOf(4), "D. 北"), node(listOf(6), "本题分值：2分")))
        assertEquals(4, page.options.size)
        assertEquals(listOf(4), page.options.single { it.label == "D" }.target.path)
    }
    @Test fun letterPositionKeepsSelectionStateFromItsRow() {
        val page = inspect(listOf(node(listOf(1), state = true, children = listOf(
            node(listOf(1, 0), "A"), node(listOf(1, 1), "东"))), node(listOf(2), "B. 西")))
        val a = page.options.single { it.label == "A" }
        assertEquals(listOf(1, 0), a.target.path)
        assertTrue(a.checkable)
        assertTrue(a.selected)
    }
    @Test fun duplicateLetterNodesCannotBeReplacedByAWholeContainerTap() {
        // The two exposed B nodes are distinct controls despite sharing one parent.
        val b = node(listOf(2), children = listOf(node(listOf(2, 0), "B."), node(listOf(2, 1), "B."), node(listOf(2, 2), "西")))
        val page = inspect(listOf(node(listOf(1), "A. 东"), b))
        assertNull(page.question)
        assertTrue(page.options.none { it.label == "B" })
    }
    @Test fun lastLetterDoesNotBorrowNextOrFavouriteActionFromItsContainer() {
        for (button in listOf("下一题", "收藏")) {
            val page = inspect(listOf(node(listOf(1), "A. 东"), node(listOf(2), children = listOf(
                node(listOf(2, 0), "B. 西"), node(listOf(2, 1), button, click = true)))))
            assertEquals(listOf(2, 0), page.options.single { it.label == "B" }.target.path)
            assertFalse(page.options.single { it.label == "B" }.target.clickAction)
        }
    }
    @Test fun optionMarkersRequireAnExplicitBoundaryAndOneLetterPerCandidate() {
        for (text in listOf("B", "B. 西", "(B) 西", "（B）西", "[B] 西", "Ｂ．西", "b、 西"))
            assertEquals(text, "B", ChoiceQuestion.optionLabel(text))
        for (text in listOf("Banana", "答案是B", "A. 东\nB. 西", "AB", "提交B", "B组"))
            assertNull(text, ChoiceQuestion.optionLabel(text))
    }
}
