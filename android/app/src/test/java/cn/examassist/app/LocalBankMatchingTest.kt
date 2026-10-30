package cn.examassist.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBankMatchingTest {
    private val longStem = "在标准大气压环境中使用温度计测量纯净水的沸点通常是多少？"
    private fun fuzzyQuestion(stem: String = longStem, answer: String = "A") = question(answer).put("stem", stem)
    private fun fuzzyPage(stem: String) = "$stem\nA. 90℃\nB. 100℃\nC. 80℃"

    @Test fun scoredMatchHandlesOneTypoWithReorderedOptions() {
        val page = fuzzyPage(longStem.replace("温度计", "温度记"))
        val result = LocalBankMatching.answer(page, listOf(fuzzyQuestion()))
        assertEquals("B", result.getString("answer"))
        assertEquals("local_bank_scored", result.getString("answer_source"))
        assertEquals("scored", result.getJSONArray("references").getJSONObject(0).getString("match_type"))
        assertEquals("100°C", LocalBankMatching.answer(page, listOf(fuzzyQuestion()), AnswerFormat.CONTENT).getString("answer"))
        assertEquals("B", LocalBankMatching.answer(page.replace("C. 80℃", ""), listOf(fuzzyQuestion())).getString("answer"))
        rejected(page.replace("B. 100℃", "B. 101℃"), listOf(fuzzyQuestion()))
        assertTrue(LocalBankMatching.lookup(page, listOf(fuzzyQuestion(), fuzzyQuestion(answer = "B"))) is LocalBankMatching.Lookup.Blocked)
    }

    @Test fun closeRunnerDoesNotVetoWinnerAndChangedFactsLowerScore() {
        val page = fuzzyPage(longStem.replace("温度计", "温度记"))
        assertEquals("B", LocalBankMatching.answer(page, listOf(fuzzyQuestion(), fuzzyQuestion(longStem.replace("纯净水", "洁净水"), "B"))).getString("answer"))
        for ((from, to) in listOf("通常是" to "通常不是", "标准大气压" to "2标准大气压", "沸点" to "最低沸点")) {
            rejected(fuzzyPage(longStem.replace(from, to)), listOf(fuzzyQuestion()))
        }
        val quantity = longStem.replace("标准大气压", "100Pa气压")
        rejected(fuzzyPage(quantity.replace("100Pa", "100kPa")), listOf(fuzzyQuestion(quantity)))
        rejected(fuzzyPage(quantity.replace("100Pa", "101Pa")), listOf(fuzzyQuestion(quantity)))
        val truth = JSONObject().put("stem", longStem).put("options", JSONArray()).put("reference_answer", "正确")
        assertEquals("对", LocalBankMatching.answer(longStem.replace("温度计", "温度记"), listOf(truth)).getString("answer"))
    }

    @Test fun removesRecognizedPageMetadataAndToleratesPunctuationButKeepsConditions() {
        val page = "消防安全在线考试\n返回\n倒计时：00:12\n单选题（2分）\n12 / 50\n" + current + "\n下一题"
        assertEquals("B", LocalBankMatching.answer(page, listOf(question())).getString("answer"))
        assertEquals("B", LocalBankMatching.answer(current.replace("下水", "下，水"), listOf(question())).getString("answer"))
        rejected("$current\n条件：水样不纯", listOf(question()), threshold = 100)
        assertTrue(LocalBankMatching.lookup(current, listOf(question(), question("B").put("stem", stem.replace("下水", "下，水")))) is LocalBankMatching.Lookup.Blocked)
    }
    @Test fun supportsStandaloneParenthesizedAndWhitespaceOptionLabels() {
        for (page in listOf("$stem\nA\n90℃\nB\n100℃\nC\n80℃",
            "$stem\n（A）90℃\n（B）100℃\n（C）80℃", "$stem\nA 90℃\nB 100℃\nC 80℃",
            "$stem\n[A]90℃\n[B]100℃\n[C]80℃")) {
            assertEquals("B", LocalBankMatching.answer(page, listOf(question())).getString("answer"))
        }
    }

    @Test fun judgmentAnswersMapToCurrentLabelsOrWords() {
        val row = JSONObject().put("stem", "地球绕太阳公转。").put("options", JSONArray())
            .put("question_type", "判断题").put("reference_answer", "正确")
        val page = "地球绕太阳公转。\nA. 错\nB. 对"
        assertEquals("B", LocalBankMatching.answer(page, listOf(row)).getString("answer"))
        assertEquals("对", LocalBankMatching.answer(page, listOf(row), AnswerFormat.CONTENT).getString("answer"))
        row.put("options", JSONArray().put(JSONObject().put("label", "A").put("text", "正确"))
            .put(JSONObject().put("label", "B").put("text", "错误"))).put("reference_answer", "A")
        assertEquals("B", LocalBankMatching.answer(page, listOf(row)).getString("answer"))
        assertEquals("对", LocalBankMatching.answer("地球绕太阳公转。", listOf(row)).getString("answer"))
        rejected(page.replace("公转", "不公转"), listOf(row))
        rejected(page.replace("A. 错", "A. 不一定"), listOf(row))
    }

    @Test fun contentArraysAndDelimitedMultipleAnswersRemap() {
        assertEquals("AB", LocalBankMatching.answer(current, listOf(question("100℃；90℃"))).getString("answer"))
        assertEquals("AB", LocalBankMatching.answer(current, listOf(question("[\"100℃\",\"90℃\"]"))).getString("answer"))
        rejected(current, listOf(question("100℃；100℃")))
        rejected(current, listOf(question("100℃；101℃")))
    }

    @Test fun reorderedOptionsCannotReusePositionDependentAnswers() {
        val row = question("C", listOf("甲", "乙", "A和B"))
        assertEquals("C", LocalBankMatching.answer("$stem\nA. 甲\nB. 乙\nC. A和B", listOf(row)).getString("answer"))
        rejected("$stem\nA. A和B\nB. 甲\nC. 乙", listOf(row))
    }

    @Test fun lookupDistinguishesMissesFromKnownConflictsIncludingPartialPages() {
        assertTrue(LocalBankMatching.lookup(current.replace("100℃", "101℃"), listOf(question())) is LocalBankMatching.Lookup.Blocked)
        assertTrue(LocalBankMatching.lookup(current, listOf(question(), question("B"))) is LocalBankMatching.Lookup.Blocked)
        assertTrue(LocalBankMatching.lookup("1. 未知题目\n2. $current", listOf(question(), question("B"))) is LocalBankMatching.Lookup.Blocked)
        assertTrue(LocalBankMatching.lookup(current, listOf(question(""))) is LocalBankMatching.Lookup.Blocked)
    }

    @Test fun auditCountsMissingInvalidModelAndConflictingAnswersUsingContent() {
        val audit = LocalBankMatching.audit(listOf(question(), question("B"), question(""), question("Z"),
            question("B", listOf("90℃", "100℃", "80℃")).put("answer_origin", "model")))
        assertEquals(1, audit.getInt("missing_answers"))
        assertEquals(1, audit.getInt("unusable_answers"))
        assertEquals(1, audit.getInt("model_answers"))
        assertEquals(1, audit.getInt("conflict_groups"))
        val agreeing = LocalBankMatching.audit(listOf(question(), question("B", listOf("90℃", "100℃", "80℃"))))
        assertEquals(0, agreeing.getInt("conflict_groups"))
    }
    @Test fun layoutNormalizationRetainsEnglishWordAndNumberBoundaries() {
        val stored = JSONObject().put("stem", "It is now here").put("options", JSONArray()).put("reference_answer", "对")
        assertEquals("对", LocalBankMatching.answer("It is now\n here", listOf(stored)).getString("answer"))
        rejected("It is nowhere", listOf(stored), threshold = 100)
        assertTrue(compactBankText("1 2") != compactBankText("12"))
    }
    private val stem = "标准大气压下水的沸点？"
    private val current = "$stem\nA. 90℃\nB. 100℃\nC. 80℃"
    private fun question(answer: String = "A", options: List<String> = listOf("100℃", "90℃", "80℃")) = JSONObject()
        .put("stem", stem).put("reference_answer", answer).put("source", "讲义")
        .put("options", JSONArray(options.mapIndexed { index, text -> JSONObject().put("label", ('A' + index).toString()).put("text", text) }))
    private fun rejected(input: String, rows: List<JSONObject>, threshold: Int = 85): String {
        val failure = runCatching { LocalBankMatching.answer(input, rows, threshold = threshold) }.exceptionOrNull()
        assertTrue(failure?.toString(), failure is ApiException)
        return failure?.message.orEmpty()
    }

    @Test fun remapsAnswersByOptionContentIncludingFullWidthAndMultipleSelections() {
        assertEquals("B", LocalBankMatching.answer(current, listOf(question())).getString("answer"))
        assertEquals("AB", LocalBankMatching.answer(current, listOf(question("A与B"))).getString("answer"))
        assertEquals("AB", LocalBankMatching.answer(current, listOf(question("[\"A\",\"B\"]"))).getString("answer"))
        assertEquals("B", LocalBankMatching.answer(current.replace("B.", "Ｂ．"), listOf(question("答案：A. 100℃"))).getString("answer"))
        assertEquals("B", LocalBankMatching.answer(current.replace("℃", "°C"), listOf(question())).getString("answer"))
        assertEquals("B", LocalBankMatching.answer(current, listOf(question("100℃"))).getString("answer"))
        val result = LocalBankMatching.answer(current, listOf(question()))
        assertEquals("local_bank", result.getString("answer_source"))
        assertEquals("讲义", result.getJSONArray("references").getJSONObject(0).getString("source"))
    }

    @Test fun rejectsFuzzyMatchesChangedConditionsIncompleteOrDuplicateOptionsAndUnknownAnswers() {
        rejected(current.replace("标准大气压", "高海拔"), listOf(question()))
        rejected(current.replace("100℃", "101℃"), listOf(question()))
        rejected(current.replace("C. 80℃", ""), listOf(question()), threshold = 100)
        rejected(current.replace("C. 80℃", "C. 100℃"), listOf(question()))
        rejected(current.replace("C. 80℃", "B. 80℃"), listOf(question()))
        rejected(current, listOf(question("")))
        rejected(current, listOf(question("D")))
        rejected(current, listOf(question("A. 90℃")))
        rejected(current, listOf(question("=A")))
        rejected(stem, listOf(question()))
        rejected(current, listOf(question().put("options", JSONArray())))
        rejected(current, emptyList())
    }

    @Test fun acceptsAgreeingDuplicatesButRejectsConflictingBankAnswers() {
        assertEquals("B", LocalBankMatching.answer(current, listOf(question(), question())).getString("answer"))
        assertTrue(rejected(current, listOf(question(), question("B"))).contains("不一致"))
    }

    @Test fun multipleQuestionsRequireClearBoundariesAndEveryAnswer() {
        val second = JSONObject().put("stem", "地球绕太阳公转。").put("options", JSONArray()).put("reference_answer", "正确")
        assertEquals("B\n对", LocalBankMatching.answer("1. $current\n2. 地球绕太阳公转。", listOf(question(), second)).getString("answer"))
        assertTrue(rejected("1. $current\n2. 未知题目", listOf(question())).contains("第 2 题"))
        rejected("$current\n$current", listOf(question()))
        rejected("1. $current\n2. $stem\nA. 100℃", listOf(question()))
        rejected("页面标题\n1. $current", listOf(question()))
    }

    @Test fun supportsEmbeddedAndWrappedOptionsButPreservesNumericAndNegationDifferences() {
        val embedded = question().put("stem", "$stem\nA. 100℃\nB. 90℃\nC. 80℃").put("options", JSONArray())
        assertEquals("B", LocalBankMatching.answer("  12. $current", listOf(embedded)).getString("answer"))
        val wrapped = question().put("stem", "计算 12 + 30 的结果是？").put("options", JSONArray()
            .put(JSONObject().put("label", "A").put("text", "第一行\n第二行"))
            .put(JSONObject().put("label", "B").put("text", "42")))
        val input = "计算 12 + 30 的结果是？\nA. 第一行\n第二行\nB. 42"
        assertEquals("A", LocalBankMatching.answer(input, listOf(wrapped)).getString("answer"))
        rejected(input.replace("30", "40"), listOf(wrapped))
        rejected(input.replace("结果是", "结果不是"), listOf(wrapped))
        val exponent = JSONObject().put("stem", "计算 x² 的值？").put("options", JSONArray()).put("reference_answer", "4")
        assertEquals("4", LocalBankMatching.answer("计算 x² 的值？", listOf(exponent)).getString("answer"))
        rejected("计算 x2 的值？", listOf(exponent))
    }

    @Test fun permitsEnglishFillAnswersAndJudgmentsWithoutTreatingWordsAsOptionLetters() {
        val fill = JSONObject().put("stem", "The liquid is ___").put("options", JSONArray()).put("reference_answer", "water")
        assertEquals("water", LocalBankMatching.answer("The liquid is ___", listOf(fill)).getString("answer"))
        val judgment = JSONObject().put("stem", "地球绕太阳公转。").put("options", JSONArray()).put("reference_answer", "true")
        assertEquals("对", LocalBankMatching.answer("地球绕太阳公转。", listOf(judgment)).getString("answer"))
        rejected("The liquid is ___", listOf(fill.put("reference_answer", "A")))
        rejected("The liquid is ___", listOf(fill.put("reference_answer", "a")))
        val choice = question("water", listOf("water", "ice", "steam"))
        assertEquals("A", LocalBankMatching.answer("$stem\nA. water\nB. ice\nC. steam", listOf(choice)).getString("answer"))
    }

    @Test fun trimsOnlyExplicitWholeNavigationLinesAtQuestionBoundaries() {
        val page = "返回\n单选题\n第 12 题 共 50 题\n12. $current\n上一题\n下一题\n提交\n答题卡"
        assertEquals("B", LocalBankMatching.answer(page, listOf(question())).getString("answer"))
        assertEquals("B", LocalBankMatching.answer("单选题\n$current\n提交", listOf(question())).getString("answer"))
        rejected("考试标题\n1. $current\n下一题", listOf(question()))
        rejected("$current\n条件：水样不纯", listOf(question()), threshold = 100)
        val verb = question("A", listOf("提交", "返回", "下一题"))
        assertEquals("A", LocalBankMatching.answer("$stem\nA. 提交\nB. 返回\nC. 下一题", listOf(verb)).getString("answer"))
        val sourceWithWrappedOption = question().put("stem", "$stem\nA. 100℃\nB. 90℃\nC. 80℃\n提交").put("options", JSONArray())
        rejected(current, listOf(sourceWithWrappedOption), threshold = 100)
    }
}
