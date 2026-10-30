package cn.examassist.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BankMatchThresholdTest {
    private val stem = "请问在标准大气压环境中使用温度计测量纯净水的沸点通常是多少？"
    private val page = stem.replace("温度计", "温度记") + "\nA. 90℃\nB. 100℃\nC. 80℃"
    private fun row(text: String = stem, answer: String = "A") = JSONObject().put("stem", text)
        .put("reference_answer", answer).put("options", JSONArray(listOf("100℃", "90℃", "80℃").mapIndexed { i, value ->
            JSONObject().put("label", ('A' + i).toString()).put("text", value)
        }))
    private fun bare(text: String, answer: String = "内容") = JSONObject().put("stem", text).put("reference_answer", answer)

    @Test fun savedDefaultAndRangeValidationWorkWithoutAiCredentials() {
        assertEquals(85, AiConfig().bankMatchThreshold)
        assertEquals(85, BankMatchSettings.read(JSONObject()))
        for (value in listOf(-1, 101, 85.5, "90", JSONObject.NULL))
            assertEquals(85, BankMatchSettings.read(JSONObject().put(BankMatchSettings.JSON_KEY, value)))
        for (value in listOf(0, 65, 85, 100)) {
            assertEquals(value, BankMatchSettings.read(JSONObject().put(BankMatchSettings.JSON_KEY, value)))
            assertNull(Api.validateAnswerMode(AiConfig(aiEnabled = false, bankMatchThreshold = value)))
        }
        assertNotNull(Api.validateAnswerMode(AiConfig(aiEnabled = false, bankMatchThreshold = 101)))
        assertNotNull(Api.validateAnswerMode(AiConfig(aiEnabled = false, bankMatchThreshold = -1)))
        assertEquals(92, AiConfig(bankMatchThreshold = 92).copy(provider = "qwen", model = "qwen-plus").bankMatchThreshold)
    }

    @Test fun thresholdAloneDecidesAcceptanceIncludingEqualityAndEndpoints() {
        val rows = listOf(bare("中国山河风雨"))
        val result = LocalBankMatching.answer("中国山河风雪", rows, threshold = 80)
        assertEquals(0.8, result.getDouble("match_score"), 0.000001)
        assertEquals(80, result.getInt("match_threshold"))
        val miss = LocalBankMatching.lookup("中国山河风雪", rows, threshold = 81) as LocalBankMatching.Lookup.Miss
        assertTrue(miss.reason, miss.reason.contains("80.0%") && miss.reason.contains("81%"))
        assertEquals("内容", LocalBankMatching.answer("中国山河风雨", rows, threshold = 100).getString("answer"))
        assertTrue(LocalBankMatching.lookup("完全无关文本", rows, threshold = 0) is LocalBankMatching.Lookup.Hit)
        assertTrue(LocalBankMatching.lookup("完全无关文本", rows, threshold = 1) is LocalBankMatching.Lookup.Miss)
    }

    @Test fun highestCandidateWinsEvenWhenSeveralQualifyAndGapIsSmall() {
        val winner = row().put("id", "winner")
        val runner = row(stem.replace("纯净水", "洁净水"), "B").put("id", "runner")
        val bestScore = LocalBankMatching.answer(page, listOf(winner)).getDouble("match_score")
        val runnerScore = LocalBankMatching.answer(page, listOf(runner)).getDouble("match_score")
        assertTrue("winner=$bestScore runner=$runnerScore", bestScore > runnerScore && bestScore - runnerScore < 0.06)
        for (rows in listOf(listOf(runner, winner), listOf(winner, runner))) {
            val result = LocalBankMatching.answer(page, rows)
            assertEquals("B", result.getString("answer"))
            assertEquals(bestScore, result.getDouble("match_score"), 0.000001)
            val refs = result.getJSONArray("references")
            assertEquals(1, refs.length())
            assertEquals("winner", refs.getJSONObject(0).getString("id"))
        }
        assertEquals("100°C", LocalBankMatching.answer(page, listOf(runner, winner), AnswerFormat.CONTENT).getString("answer"))
        runner.put("reference_answer", "")
        assertEquals("B", LocalBankMatching.answer(page, listOf(runner, winner)).getString("answer"))
    }

    @Test fun sameStemDifferentOptionsAreRankedInsteadOfMergedAsConflictingAnswers() {
        val runner = row(answer = "B")
        runner.getJSONArray("options").getJSONObject(2).put("text", "70℃")
        val exactPage = page.replace("温度记", "温度计")
        val result = LocalBankMatching.answer(exactPage, listOf(runner, row()))
        assertEquals("B", result.getString("answer"))
        assertEquals(1.0, result.getDouble("match_score"), 0.0)
    }

    @Test fun thresholdCoversShortBareAndLongNoisyQuestionsWithoutHiddenCutoffs() {
        assertEquals("内容", LocalBankMatching.answer("甲乙丙戊", listOf(bare("甲乙丙丁")), threshold = 60).getString("answer"))
        assertTrue(LocalBankMatching.lookup("甲乙丙戊", listOf(bare("甲乙丙丁")), threshold = 70) is LocalBankMatching.Lookup.Miss)
        val long = "中国山河风雨".repeat(120)
        val noisy = long.take(300) + "甲乙丙丁戊己庚辛壬癸" + long.drop(300)
        assertEquals("内容", LocalBankMatching.answer(noisy, listOf(bare(long)), threshold = 95).getString("answer"))
        val unknownHeader = "紫藤练习入口\n$stem\nA. 90℃\nB. 100℃\nC. 80℃"
        assertEquals("B", LocalBankMatching.answer(unknownHeader, listOf(row())).getString("answer"))
        assertTrue(LocalBankMatching.lookup(unknownHeader, listOf(row()), threshold = 100) is LocalBankMatching.Lookup.Miss)
    }

    @Test fun changedFactsReduceScoreInsteadOfBypassingUserThreshold() {
        val changed = page.replace("通常是", "通常不是")
        assertTrue(LocalBankMatching.lookup(changed, listOf(row())) is LocalBankMatching.Lookup.Miss)
        assertEquals("B", LocalBankMatching.answer(changed, listOf(row()), threshold = 60).getString("answer"))
    }

    @Test fun highestTieCanAgreeButCannotInventAMissingOrContradictoryAnswer() {
        assertEquals("B", LocalBankMatching.answer(page, listOf(row(), row())).getString("answer"))
        assertTrue(LocalBankMatching.lookup(page, listOf(row(), row(answer = "B"))) is LocalBankMatching.Lookup.Blocked)
        assertTrue(LocalBankMatching.lookup(page, listOf(row(answer = ""))) is LocalBankMatching.Lookup.Blocked)
        assertTrue(LocalBankMatching.lookup(page.replace("100℃", "101℃"), listOf(row()), threshold = 0) is LocalBankMatching.Lookup.Blocked)
    }

    @Test fun bareBankAnswersRequireActualOptionMappingOnAChoicePage() {
        val choices = "水果分类\nA. 苹果\nB. 白菜"
        assertEquals("A", LocalBankMatching.answer(choices, listOf(bare("水果分类", "苹果")), threshold = 80).getString("answer"))
        assertEquals("苹果", LocalBankMatching.answer(choices, listOf(bare("水果分类", "苹果")), AnswerFormat.CONTENT, 80).getString("answer"))
        for (answer in listOf("香蕉", "A", "")) {
            assertTrue(LocalBankMatching.lookup(choices, listOf(bare("水果分类", answer)), AnswerFormat.CONTENT, 0) is LocalBankMatching.Lookup.Blocked)
        }
    }

    @Test fun multipleQuestionsApplyThresholdIndividuallyAndReportLowestScore() {
        val rows = listOf(bare("中国山河风雨"), bare("甲乙丙丁", "另一个答案"))
        val result = LocalBankMatching.answer("1. 中国山河风雪\n2. 甲乙丙丁", rows, threshold = 80)
        assertEquals("内容\n另一个答案", result.getString("answer"))
        assertEquals(0.8, result.getDouble("match_score"), 0.000001)
        assertTrue(LocalBankMatching.lookup("1. 中国山河风雪\n2. 甲乙丙丁", rows, threshold = 81) is LocalBankMatching.Lookup.Miss)
    }
}
