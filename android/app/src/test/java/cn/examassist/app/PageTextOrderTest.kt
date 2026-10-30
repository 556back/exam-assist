package cn.examassist.app

import org.junit.Assert.*
import org.junit.Test

class PageTextOrderTest {
    private fun piece(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        PageTextOrder.Piece(text, PageBounds(left, top, right, bottom))

    @Test fun verticallyOffsetLabelPrecedesItsWrappedContent() {
        val pieces = listOf(piece("选择内容", 20, 20, 500, 80),
            piece("A", 20, 115, 60, 145), piece("第一项", 80, 100, 500, 160),
            piece("第二项\n换行内容", 80, 200, 500, 290), piece("B", 20, 230, 60, 260),
            piece("C", 20, 315, 60, 345), piece("第三项", 80, 300, 500, 360))
        val ordered = PageTextOrder.order(pieces)
        assertEquals(listOf("选择内容", "A", "第一项", "B", "第二项\n换行内容", "C", "第三项"), ordered.map { it.text })
        assertEquals("第二项\n换行内容", ChoiceQuestion.parse(ordered.joinToString("\n") { it.text })!!.options["B"])
        assertEquals(ordered, PageTextOrder.order(ordered))
    }

    @Test fun unknownPositionsAndTextSpanningSeveralLabelsAreNotGuessed() {
        val missing = listOf(PageTextOrder.Piece("内容"), PageTextOrder.Piece("A"))
        assertEquals(missing, PageTextOrder.order(missing))
        val overlapping = listOf(piece("多项共用容器", 80, 100, 500, 300),
            piece("A", 20, 120, 60, 150), piece("B", 20, 220, 60, 250))
        assertEquals(overlapping, PageTextOrder.order(overlapping))
    }

    @Test fun contentStartingWithASingleLetterRemainsWithItsRow() {
        val pieces = listOf(piece("选择内容", 20, 20, 500, 80),
            piece("A 类火灾", 80, 100, 500, 160), piece("A", 20, 115, 60, 145),
            piece("a list", 80, 200, 500, 260), piece("B", 20, 215, 60, 245))
        val raw = PageTextOrder.order(pieces).joinToString("\n") { it.text }
        assertEquals(mapOf("A" to "A 类火灾", "B" to "a list"), ChoiceQuestion.parse(raw)!!.options)
    }

    @Test fun letterStatisticsAreContentEvenWhenTheirTopIsAboveTheActualLabel() {
        val pieces = listOf(piece("选择统计量", 20, 20, 500, 80),
            piece("F", 80, 100, 500, 160), piece("A", 20, 115, 60, 145),
            piece("t", 80, 200, 500, 260), piece("B", 20, 215, 60, 245),
            piece("R2", 80, 300, 500, 360), piece("C", 20, 315, 60, 345),
            piece("Adjusted R2", 80, 400, 500, 460), piece("D", 20, 415, 60, 445))
        val raw = PageTextOrder.order(pieces).joinToString("\n") { it.text }
        assertEquals(listOf("F", "t", "R2", "Adjusted R2"), ChoiceQuestion.parse(raw)!!.options.values.toList())
    }

    @Test fun mergedRowsWithOffsetSingleLetterContentDoNotRepeatParentDescriptions() {
        fun row(index: Int, text: String): PageNode {
            val path = listOf(index)
            val top = index * 100
            return PageNode(PageTarget(path, true, PageBounds(20, top, 500, top + 60), true),
                listOf("${'A' + index - 1}. $text"), selected = false, children = listOf(
                    PageNode(PageTarget(path + 0, true, PageBounds(80, top, 500, top + 60), false), listOf(text)),
                    PageNode(PageTarget(path + 1, true, PageBounds(20, top + 15, 60, top + 45), false), listOf(('A' + index - 1).toString()))))
        }
        val page = PageAnalysis.inspect(PageNode(PageTarget(emptyList(), true), children = listOf(
            PageNode(PageTarget(listOf(0), true), listOf("选择统计量")), row(1, "F"), row(2, "t"))), "quiz", 1)
        assertEquals(mapOf("A" to "F", "B" to "t"), page.question!!.options)
        assertEquals(listOf(listOf(1), listOf(2)), page.options.map { it.target.path })
        assertTrue(page.options.all { it.checkable })
    }

    @Test fun automaticParsingRepairsSiblingOrderAndKeepsClickTargets() {
        fun node(path: List<Int>, text: String, bounds: PageBounds, click: Boolean = false) =
            PageNode(PageTarget(path, true, bounds, click), listOf(text))
        val children = listOf(node(listOf(0), "单选题\n选择内容", PageBounds(20, 20, 500, 80)),
            node(listOf(1), "第一项", PageBounds(80, 100, 500, 160)),
            node(listOf(2), "A", PageBounds(20, 115, 60, 145), true),
            node(listOf(3), "第二项", PageBounds(80, 200, 500, 260)),
            node(listOf(4), "B", PageBounds(20, 215, 60, 245), true),
            node(listOf(5), "纠错", PageBounds(20, 300, 500, 340)),
            node(listOf(6), "下一题", PageBounds(20, 400, 500, 440), true))
        val page = PageAnalysis.inspect(PageNode(PageTarget(emptyList(), true), children = children), "quiz", 1)
        assertEquals(mapOf("A" to "第一项", "B" to "第二项"), page.question!!.options)
        assertEquals(listOf(listOf(2), listOf(4)), page.options.map { it.target.path })
        assertEquals(listOf(6), page.next!!.path)
        assertTrue(page.question!!.stem.contains("单选题"))
    }
}
