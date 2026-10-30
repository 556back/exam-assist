package cn.examassist.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PageMatchingRegressionTest {
    private fun row(stem: String, options: List<String>, answer: String) = JSONObject()
        .put("stem", stem).put("reference_answer", answer)
        .put("options", JSONArray(options.mapIndexed { index, text ->
            JSONObject().put("label", ('A' + index).toString()).put("text", text)
        }))

    @Test fun reportedFooterFailuresMatchAtFullThresholdAndKeepRawDiagnostics() {
        val rows = listOf(
            row("-5%3的运算结果是2。", listOf("正确", "错误"), "B"),
            row("计算机网络的功能（ ）。", listOf("数据通信", "资源共享", "进行数据信息的集中和综合处理", "均衡负载"), "ABCD"),
            row("“词嵌入”（Embedding）在大模型中的作用是（ ）。", listOf("对用户数据进行加密", "提高显示器的分辨率", "压缩图像文件的大小", "将文本转换为数值向量，便于模型处理"), "D")
        )
        rows.forEachIndexed { index, saved ->
            val type = listOf("判断题", "多选题", "单选题")[index]
            val raw = "学习平台\n$type\n58 |65\n" + saved.getString("stem") +
                saved.getJSONArray("options").objects().joinToString("") { "\n${it.getString("label")}\n${it.getString("text")}" } +
                "\n纠错\n上一题\n下一题"
            var diagnostic: JSONObject? = null
            val result = LocalBankMatching.lookup(raw, rows, threshold = 100) { diagnostic = it }
            assertTrue(result.toString(), result is LocalBankMatching.Lookup.Hit)
            assertEquals(saved.getString("reference_answer"), (result as LocalBankMatching.Lookup.Hit).result.getString("answer"))
            assertEquals(raw, diagnostic!!.getString("raw_text"))
            assertFalse(ChoiceQuestion.parse(raw)!!.options.values.any { "纠错" in it })
        }
    }

    @Test fun reportedDelayedLabelUsesAllOptionContentsAndSurvivesReordering() {
        val stem = "某单位信息内网的一台计算机上一份重要文件泄密，但从该计算机上无法获得泄密细节和线索，可能的原因是（ ）。"
        val options = listOf("该计算机未开启审计功能", "该计算机审计日志未安排专人进行维护", "该计算机感染了木马", "该计算机存在系统漏洞")
        val raw = "学习平台\n多选题\n43 |65\n$stem\nA\n${options[0]}\n${options[1]}\nB\nC\n${options[2]}\nD\n${options[3]}\n纠错\n上一题\n下一题"
        for (saved in listOf(row(stem, options, "AB"), row(stem, options.reversed(), "CD"))) {
            assertEquals("AB", LocalBankMatching.answer(raw, listOf(saved), threshold = 100).getString("answer"))
            assertEquals(options.take(2).joinToString("；"), LocalBankMatching.answer(raw, listOf(saved), AnswerFormat.CONTENT, 100).getString("answer"))
        }
    }

    @Test fun delayedLabelsSupportWrappedEnglishAndMathematicalOptions() {
        val saved = row("Select the expression", listOf("now here", "x² + 12", "not allowed", "other"), "BC")
        for (body in listOf("A\nnow here\nx² + 12\nB\nC\nnot allowed\nD\nother",
            "A\nnow\nhere\nx² + 12\nB\nnot allowed\nC\nother\nD")) {
            assertEquals("BC", LocalBankMatching.answer("Select the expression\n$body", listOf(saved), threshold = 100).getString("answer"))
        }
        val letterContent = row("选择内容", listOf("A 类火灾", "a list", "other"), "B")
        assertEquals("B", LocalBankMatching.answer("选择内容\nA\nA 类火灾\na list\nB\nC\nother", listOf(letterContent), threshold = 100).getString("answer"))
    }

    @Test fun recoveryCannotFillMissingContentDiscardConditionsOrResolveConflicts() {
        val saved = row("选择内容", listOf("甲", "乙", "丙"), "B")
        val raw = "选择内容\nA\n甲\n乙\nB\nC\n丙"
        assertEquals("B", LocalBankMatching.answer(raw, listOf(saved), threshold = 100).getString("answer"))
        for (bad in listOf(raw.replace("乙\n", ""), raw.replace("乙", "丁"), raw + "\n附加条件：不得选择乙",
            raw.replace("\nB\n", "\nB\nB\n"), raw.replace("\nB\n", "\nD\n"))) {
            assertFalse(bad, LocalBankMatching.lookup(bad, listOf(saved), threshold = 0) is LocalBankMatching.Lookup.Hit)
        }
        assertTrue(LocalBankMatching.lookup(raw, listOf(saved, row("选择内容", listOf("甲", "乙", "丙"), "A")), threshold = 100) is LocalBankMatching.Lookup.Blocked)
    }

    @Test fun footerWordsInsideOrAsOptionsRemainQuestionContent() {
        val saved = row("选择操作", listOf("返回", "纠错"), "B")
        for (body in listOf("A. 返回\nB. 纠错", "A\n返回\nB\n纠错", "A\n返回\nB\n纠错\n下一题")) {
            assertEquals("B", LocalBankMatching.answer("选择操作\n$body", listOf(saved), threshold = 100).getString("answer"))
            assertEquals("纠错", ChoiceQuestion.parse("选择操作\n$body")!!.options["B"])
        }
        val prose = row("选择操作", listOf("返回", "请点击纠错按钮报告错误"), "B")
        assertEquals("B", LocalBankMatching.answer("选择操作\nA. 返回\nB. 请点击纠错按钮报告错误", listOf(prose), threshold = 100).getString("answer"))
    }

    @Test fun delayedFinalLabelWithFooterIsRecoveredOnlyWithFullContentEvidence() {
        val saved = row("选择内容", listOf("甲", "乙"), "B")
        for (footer in listOf("纠错\n上一题\n下一题", "Report an error\n下一题", "题目反馈\n下一题")) {
            assertEquals("B", LocalBankMatching.answer("选择内容\nA\n甲\n乙\nB\n$footer", listOf(saved), threshold = 100).getString("answer"))
            assertFalse(LocalBankMatching.lookup("选择内容\nA\n甲\nB\n$footer", listOf(saved), threshold = 0) is LocalBankMatching.Lookup.Hit)
        }
    }

    @Test fun ambiguousContentPartitionsDoNotSelectOneBankInterpretation() {
        val raw = "选择内容\nA\n甲\n乙\n丙\nB\nC\n丁"
        val rows = listOf(row("选择内容", listOf("甲乙", "丙", "丁"), "A"), row("选择内容", listOf("甲", "乙丙", "丁"), "A"))
        // Both interpretations give A; the content boundary itself still has to be unique.
        assertTrue(LocalBankMatching.lookup(raw, rows, threshold = 0) is LocalBankMatching.Lookup.Miss)
    }

    @Test fun recoveredAndRegularQuestionsCanShareANumberedPage() {
        val saved = row("选择内容", listOf("甲", "乙", "丙"), "B")
        val page = "1. 选择内容\nA\n甲\n乙\nB\nC\n丙\n2. 选择内容\nA. 丙\nB. 甲\nC. 乙\n下一题"
        assertEquals("B\nC", LocalBankMatching.answer(page, listOf(saved), threshold = 100).getString("answer"))
    }

    @Test fun numberedCodeLinesAndConditionsInsideOneStemArePreserved() {
        for (stem in listOf("有以下程序片段，选择插入内容。\n1.\n2.public class Example{\n3.// code\n4. }",
            "根据以下条件选择：\n1. 不得超过12米\n2. 必须先关闭开关")) {
            val saved = row(stem, listOf("甲", "乙", "丙"), "B")
            for (number in listOf("", "12. ")) {
                for (body in listOf("A\n甲\nB\n乙\nC\n丙", "A\n甲\n乙\nB\nC\n丙")) {
                    assertEquals("B", LocalBankMatching.answer("$number$stem\n$body\n纠错\n下一题", listOf(saved), threshold = 100).getString("answer"))
                }
            }
        }
    }

    @Test fun singleLetterOptionContentsAreNotAdditionalLabels() {
        for (options in listOf(listOf("F", "t", "R2", "Adjusted R2"), listOf("B", "C", "D", "E"))) {
            val saved = row("选择统计量", options, "AB")
            for (delayed in listOf(false, true)) {
                val body = options.mapIndexed { index, text ->
                    if (delayed && index == 1) "\n$text\n${'A' + index}" else "\n${'A' + index}\n$text"
                }.joinToString("")
                assertEquals("AB", LocalBankMatching.answer("选择统计量$body\n纠错\n下一题", listOf(saved), threshold = 100).getString("answer"))
                if (!delayed && options.first() == "F")
                    assertEquals(options, ChoiceQuestion.parse("选择统计量$body\n纠错\n下一题")!!.options.values.toList())
            }
        }
    }

    @Test fun lowThresholdCannotCollapseAnUnknownBareQuestionAndAKnownChoiceQuestion() {
        val saved = row("选择内容", listOf("甲", "乙"), "B")
        val raw = "1. 未知填空题\n2. 选择内容\nA. 甲\nB. 乙"
        assertFalse(LocalBankMatching.lookup(raw, listOf(saved), threshold = 0) is LocalBankMatching.Lookup.Hit)
    }
}
