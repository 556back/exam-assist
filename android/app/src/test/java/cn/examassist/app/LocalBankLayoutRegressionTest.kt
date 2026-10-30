package cn.examassist.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalBankLayoutRegressionTest {
    @Test fun attachedOptionMarkersNeedACompleteOrderedSequence() {
        val question = JSONObject().put("stem", "哪些项正确？").put("reference_answer", "AC")
            .put("options", JSONArray(listOf("甲", "乙", "丙").mapIndexed { i, value -> JSONObject().put("label", ('A' + i).toString()).put("text", value) }))
        for (page in listOf("哪些项正确？A.甲\nB.乙\nC.丙", "哪些项正确？A.甲B.乙C.丙", "哪些项正确？(A)甲(B)乙(C)丙")) {
            assertEquals("AC", LocalBankMatching.answer(page, listOf(question), threshold = 100).getString("answer"))
        }
        assertTrue(LocalBankMatching.lookup("哪些项正确？A.甲\nC.丙", listOf(question), threshold = 100) !is LocalBankMatching.Lookup.Hit)
        assertEquals(emptyList<QuestionTextLayout.Marker>(), QuestionTextLayout.markers("评估方案A.的可行性"))
        assertEquals(emptyList<QuestionTextLayout.Marker>(), QuestionTextLayout.markers("Choose vitaminA. correctly"))
    }
    private val stem = "消防工作贯彻（ ）的方针。"
    private val row = JSONObject().put("stem", stem).put("reference_answer", "A")
        .put("options", JSONArray(listOf("预防为主，防消结合", "安全第一，预防为主", "以防为主，以消为辅").mapIndexed { i, value ->
            JSONObject().put("label", ('A' + i).toString()).put("text", value)
        }))
    private val choices = "A. 安全第一,预防为主\nB. 预防为主,防消结合\nC. 以防为主,以消为辅"

    @Test fun platformTypeAndProgressNoiseCanBeMergedOrOnSeparateLines() {
        for (header in listOf("学习平台单选题x|xxx", "学习平台单选题12|100", "星河企业大学单选题12/100",
            "学习平台\n单选题\n12 | 100", "星河企业大学\n【单选题】\n12/100", "学习平台\n单选题x|xxx")) {
            for (separator in listOf("", "\n")) {
                val raw = "$header$separator$stem\n$choices\n下一题"
                val result = LocalBankMatching.lookup(raw, listOf(row))
                assertTrue("$header / $separator: $result", result is LocalBankMatching.Lookup.Hit)
                assertEquals("B", (result as LocalBankMatching.Lookup.Hit).result.getString("answer"))
            }
        }
    }

    @Test fun fuzzyLongStemToleratesMoreThanThreeStrayCharactersAfterHeaderCleanup() {
        val long = "进行消防安全知识培训时，需要结合日常工作实践掌握基本的消防知识，并且熟悉办公区域的环境与相关设备，在讨论消防工作时应当贯彻（ ）的方针。"
        val saved = JSONObject(row.toString()).put("stem", long)
        val page = "学习平台单选题12|100\n" + long.replace("知识培训", "知识xyzq培训") + "\n$choices"
        val result = LocalBankMatching.answer(page, listOf(saved))
        assertEquals("B", result.getString("answer"))
        assertEquals("local_bank_scored", result.getString("answer_source"))
        assertTrue(LocalBankMatching.lookup(page.replace("应当", "不应当"), listOf(saved)) is LocalBankMatching.Lookup.Miss)
        assertTrue(LocalBankMatching.lookup(page, listOf(saved, JSONObject(saved.toString()).put("reference_answer", "B"))) is LocalBankMatching.Lookup.Blocked)
    }

    @Test fun pageTypeScoreProgressAndDecoratedNavigationDoNotPolluteQuestion() {
        for (header in listOf("单选题\n2分", "[单选题]（2分）", "1. 单选题（2分）", "1、【单项选择题】", "第1题 单选题：")) {
            val raw = "消防安全在线考试\n$header\n$stem\n$choices\n本题分值：2分\n收藏\n下一题（2/50）"
            val result = LocalBankMatching.lookup(raw, listOf(row))
            assertTrue("$header: $result", result is LocalBankMatching.Lookup.Hit)
            assertEquals("B", (result as LocalBankMatching.Lookup.Hit).result.getString("answer"))
        }
    }

    @Test fun inlineOptionsAndInvisibleLayoutCharactersMatchOffline() {
        val raw = "【单选题】$stem\u200B A. 安全第一,预防为主 B. 预防为主,防消结合 C. 以防为主,以消为辅"
        assertEquals("B", LocalBankMatching.answer(raw, listOf(row)).getString("answer"))
        assertEquals(listOf("A", "B", "C"), ChoiceQuestion.parse(raw)!!.options.keys.toList())
    }

    @Test fun compactChineseLabelsAreRecognizedOnlyAsAnOrderedQuestion() {
        val page = "$stem\n" + choices.replace("A. ", "A").replace("B. ", "B").replace("C. ", "C")
        assertEquals("B", LocalBankMatching.answer(page, listOf(row)).getString("answer"))
        assertEquals(listOf("A", "B", "C"), ChoiceQuestion.parse(page)!!.options.keys.toList())
        assertNull(ChoiceQuestion.optionLabel("B组"))
        assertTrue(LocalBankMatching.lookup(page.replace("B预防", "D预防"), listOf(row)) is LocalBankMatching.Lookup.Miss)
    }

    @Test fun letterAtStartOfOptionContentIsNotAnotherStandaloneLabel() {
        val saved = JSONObject().put("stem", "选择火灾类别").put("reference_answer", "B").put("options", JSONArray()
            .put(JSONObject().put("label", "A").put("text", "A 类火灾"))
            .put(JSONObject().put("label", "B").put("text", "B 类火灾"))
            .put(JSONObject().put("label", "C").put("text", "C 类火灾")))
        val raw = "选择火灾类别\nA\nA 类火灾\nB\nB 类火灾\nC\nC 类火灾"
        assertEquals("B", LocalBankMatching.answer(raw, listOf(saved)).getString("answer"))
        assertEquals("B", LocalBankMatching.answer(raw.replace("\nA\n", "\nA.\n").replace("\nB\n", "\nB.\n").replace("\nC\n", "\nC.\n"), listOf(saved)).getString("answer"))
        assertEquals("B 类火灾", ChoiceQuestion.parse(raw)!!.options["B"])
    }

    @Test fun optionPunctuationDifferencesStillDetectConflictingAnswers() {
        val alternate = JSONObject(row.toString()).put("reference_answer", "B")
        alternate.getJSONArray("options").objects().forEach { it.put("text", it.getString("text").replace("，", ",")) }
        assertTrue(LocalBankMatching.lookup("$stem\n$choices", listOf(row, alternate)) is LocalBankMatching.Lookup.Blocked)
    }

    @Test fun numberedTypeHeadersStillPreserveMultipleQuestionBoundaries() {
        val raw = "消防安全在线考试\n1. 【单选题】\n$stem\n$choices\n2. 单选题（2分）\n$stem\n$choices"
        assertEquals("B\nB", LocalBankMatching.answer(raw, listOf(row)).getString("answer"))
    }

    @Test fun normalizationDoesNotDiscardChangedNumbersNegationsOrExtraConditions() {
        val numeric = JSONObject().put("stem", "以下数值是哪一个？").put("reference_answer", "A")
            .put("options", JSONArray().put(JSONObject().put("label", "A").put("text", "1,000"))
                .put(JSONObject().put("label", "B").put("text", "2000")))
        assertTrue(LocalBankMatching.lookup("以下数值是哪一个？\nA. 1000\nB. 2000", listOf(numeric), threshold = 100) is LocalBankMatching.Lookup.Miss)
        for (page in listOf("$stem\n$choices\n附加条件：不得使用水", "$stem\n$choices".replace("防消结合", "不防消结合"),
            "在特殊环境下\n【单选题】\n$stem\n$choices")) {
            assertTrue(page, LocalBankMatching.lookup(page, listOf(row), threshold = 100) is LocalBankMatching.Lookup.Miss)
        }
    }

    @Test fun reportsSameStemOptionMismatchInsteadOfGenericAiAdvice() {
        val result = LocalBankMatching.lookup("$stem\n$choices".replace("以消为辅", "以消为主"), listOf(row), threshold = 100)
        assertTrue(result is LocalBankMatching.Lookup.Miss)
        val reason = (result as LocalBankMatching.Lookup.Miss).reason
        assertTrue(reason, reason.contains("题干已命中"))
        assertTrue(reason, reason.contains("选项"))
        assertFalse(reason.contains("启用大模型"))
    }

    @Test fun allImportedQuestionsMatchSeveralAccessiblePageLayoutsWithoutAi() {
        val rows = SyntheticBank.preview()
            .getJSONArray("questions").objects()
        for ((index, question) in rows.withIndex()) {
            val options = question.getJSONArray("options").objects()
            val body = question.getString("stem") + options.joinToString("") { "\n${it.getString("label")}\n${it.getString("text")}" }
            val cleanResult = LocalBankMatching.lookup(body, rows)
            assertTrue("Clean question ${index + 1}: $cleanResult", cleanResult is LocalBankMatching.Lookup.Hit)
            val clean = (cleanResult as LocalBankMatching.Lookup.Hit).result.getString("answer")
            for (type in listOf("【${question.getString("question_type")}】", "${question.getString("question_type")}（2分）",
                "学习平台${question.getString("question_type")}x|xxx")) {
                val page = "消防安全在线考试\n第 ${index + 1} 题 共 ${rows.size} 题\n$type\n2分\n$body\n本题分值：2分\n下一题（2/${rows.size}）"
                val result = LocalBankMatching.lookup(page, rows)
                assertTrue("Question ${index + 1}: $result", result is LocalBankMatching.Lookup.Hit)
                assertEquals(clean, QuestionSolver.bankAnswer(AiConfig(aiEnabled = false), result)!!.getString("answer"))
            }
        }
    }
}
