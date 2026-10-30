package cn.examassist.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnswerFormatTest {
    private val question = "多选题：选择水果\nA. 苹果\nB. 白菜\nC. 香蕉"
    @Test fun contentPromptAndParsingPreserveWordsAndFillBlanks() {
        assertEquals(4096, Api.buildRequest(AiConfig(answerFormat = AnswerFormat.CONTENT), question).getInt("max_tokens"))
        val prompt = Api.buildRequest(AiConfig(answerFormat = AnswerFormat.CONTENT), "", byteArrayOf(1))
            .getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(prompt.contains("完整原文"))
        assertTrue(prompt.contains("没有选项"))
        assertEquals("苹果；香蕉", Api.parseAnswer("""{"answer":"AC"}""", question, AnswerFormat.CONTENT).getString("answer"))
        assertEquals("苹果；香蕉", Api.parseAnswer("""{"answer":"苹果；香蕉"}""", question, AnswerFormat.CONTENT).getString("answer"))
        for (fill in listOf("A+B", "C. elegans", "A", "3.14", "E = mc²", "北京；上海")) {
            for (format in AnswerFormat.entries) assertEquals(fill,
                Api.parseAnswer(JSONObject().put("answer", fill).toString(), "填空题：请填写答案", format).getString("answer"))
        }
        assertEquals("苹果\n对\n3.14", Api.parseAnswer("""{"answer":"1. 苹果\n2. 对\n3. 3.14"}""", "", AnswerFormat.CONTENT).getString("answer"))
    }
    @Test fun localContentUsesCurrentOrderAndRetainsNoOptionAnswers() {
        val stored = JSONObject().put("stem", "多选题：选择水果").put("reference_answer", "AB").put("options", JSONArray()
            .put(JSONObject().put("label", "A").put("text", "香蕉"))
            .put(JSONObject().put("label", "B").put("text", "苹果"))
            .put(JSONObject().put("label", "C").put("text", "白菜")))
        assertEquals("AC", LocalBankMatching.answer(question, listOf(stored)).getString("answer"))
        assertEquals("苹果；香蕉", LocalBankMatching.answer(question, listOf(stored), AnswerFormat.CONTENT).getString("answer"))
        val blank = JSONObject().put("stem", "圆周率约为多少").put("reference_answer", "3.14")
        for (format in AnswerFormat.entries) assertEquals("3.14", LocalBankMatching.answer("圆周率约为多少", listOf(blank), format).getString("answer"))
    }
    @Test fun mappingHandlesSplitLabelsAndRejectsAmbiguity() {
        val parsed = ChoiceQuestion.parse("多选题\nＡ\n苹果\nＢ． 白菜\nC. 香蕉\n下一题")!!
        assertEquals(setOf("A", "C"), parsed.labels("苹果；香蕉"))
        assertEquals("苹果；香蕉", parsed.render("AC", AnswerFormat.CONTENT))
        assertNull(ChoiceQuestion.parse("题\nA. 相同\nB. 相同"))
        assertNull(ChoiceQuestion.parse("题\nB. 缺A\nC. 其他"))
        assertNull(ChoiceQuestion.parse("题1\nA. 一\nB. 二\n题2\nA. 三\nB. 四"))
        for (answer in listOf("AA", "D", "苹果和香蕉", "A\n解析：说明")) {
            try { parsed.labels(answer); fail(answer) } catch (_: ApiException) { }
        }
        val letters = ChoiceQuestion.parse("选择字母\nA. B\nB. A")!!
        assertEquals("B", letters.render("A", AnswerFormat.CONTENT))
        assertEquals("A", letters.render("A", AnswerFormat.CONTENT, preferContent = true))
        assertFalse(ChoiceQuestion.isNext("提交并下一题"))
        assertFalse(ChoiceQuestion.isNext("交卷"))
    }
    @Test fun automationDefaultsToManualAndRestoresCapturePreference() {
        val config = AiConfig(ocrEnabled = false)
        assertFalse(config.autoAnswerEnabled)
        assertEquals(AnswerFormat.LABELS, config.answerFormat)
        assertTrue(config.copy(autoAnswerEnabled = true).usesPageText)
        assertFalse(config.copy(autoAnswerEnabled = true).copy(autoAnswerEnabled = false).usesPageText)
    }
}
