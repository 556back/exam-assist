package cn.examassist.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerCheckTest {
    private val binaryQuestion = "二进制110110000转换成十进制是多少？\nA. 433\nB. 431\nC. 434\nD. 432"
    private val temperatureQuestion = "标准大气压下水的沸点？\nA. 100℃\nB. 90℃\nC. 80℃\nD. 70℃"

    @Test fun rejectsProvenOptionAndConclusionContradictions() {
        assertTrue(AnswerCheck.conflicts(binaryQuestion, "Ｃ. 432", "", emptyList()))
        assertTrue(AnswerCheck.conflicts(binaryQuestion, "C", "当前题干选项C为432。", emptyList()))
        assertTrue(AnswerCheck.conflicts(temperatureQuestion, "A. 100℃", "正确答案是B。", emptyList()))
        assertTrue(AnswerCheck.conflicts(temperatureQuestion, "A", "核对当前题。", listOf("最终答案应为B. 90℃。")))
        assertTrue(AnswerCheck.conflicts(temperatureQuestion, "A", "答案不是A。", emptyList()))
    }

    @Test fun permitsReferenceDiscussionsHypothesesAndRejectionOfOtherOptions() {
        val explanations = listOf("参考答案B错误。当前答案A正确。", "答案B是错误，答案A正确。",
            "当前选项A正确（参考中选项顺序不同，其答案B不能沿用）。",
            "参考中答案是B，但其顺序不同，按当前题干应为A。", "如果答案是B，需要另行核对。",
            "初次答案B。更正：答案应为A. 100℃。", "The reference answer is B; the current answer is A.")
        explanations.forEach { assertFalse(it, AnswerCheck.conflicts(temperatureQuestion, "A. 100℃", it,
            listOf("参考答案是B，与当前选项顺序不同。"))) }
        assertFalse(AnswerCheck.conflicts(binaryQuestion, "D", "参考题的当前选项C为432。当前答案D。", emptyList()))
        assertFalse(AnswerCheck.conflicts(temperatureQuestion, "无法确定", "可能答案B。", emptyList()))
    }
}
