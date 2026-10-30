package cn.examassist.app

import org.junit.Assert.*
import org.junit.Test

class AiAnswerTest {
    @Test fun acceptsCompactJsonAndHidesLegacyExplanationAndReferences() {
        assertEquals("ABCD", Api.parseAnswer("""{"answer":"ABCD"}""").getString("answer"))
        val json = """{"answer":"B. 水","explanation":"水的化学式是H2O。","warnings":[],"references":[{"source":"虚构来源"}]}"""
        val answer = Api.parseAnswer("```json\n$json\n```", "水的化学式是？\nA. CO2\nB. 水")
        assertEquals("B", answer.getString("answer"))
        assertEquals("", answer.getString("explanation"))
        assertEquals(0, answer.getJSONArray("warnings").length())
        assertFalse(answer.has("references"))
    }
    @Test fun preservesAnswerSequenceAndFillBlanksWithoutQuestionNumbers() {
        val json = """{"answer":"1. 答案：ＡＣ\n2. 对\n3. 北京；上海\n4. 3.14"}"""
        assertEquals("AC\n对\n北京；上海\n3.14", Api.parseAnswer(json, "选择题\nA. 苹果\nB. 梨\nC. 香蕉").getString("answer"))
        assertEquals("错", Api.parseAnswer("""{"answer":"错"}""").getString("answer"))
        assertEquals("H2O", Api.parseAnswer("""{"answer":"H2O"}""").getString("answer"))
        assertEquals("C. elegans；3.14；E = mc²", Api.parseAnswer("""{"answer":"C. elegans；3.14；E = mc²"}""").getString("answer"))
        for (fill in listOf("A+B", "A和B", "P,Q", "1. NaOH", "1.23", "选择排序", "选项卡", "True", "False", "正确", "错误")) {
            assertEquals(fill, Api.parseAnswer(org.json.JSONObject().put("answer", fill).toString()).getString("answer"))
        }
        val mixed = "B\nA+B\nC. elegans\nP,Q"
        assertEquals(mixed, Api.parseAnswer(org.json.JSONObject().put("answer", mixed).toString(),
            "选择题\nA. 苹果\nB. 梨\n填空题").getString("answer"))
    }
    @Test fun checksLegacyOptionContradictionsBeforeRemovingOptionText() {
        val question = "水的化学式是？\nA. CO2\nB. H2O"
        rejects("""{"answer":"A. H2O"}""", question)
        rejects("""{"answer":"A","explanation":"正确答案是B。"}""", question)
        assertEquals("B", Api.parseAnswer("""{"answer":"B. H2O"}""", question).getString("answer"))
    }
    @Test fun rejectsIncompleteOrMistypedAiOutput() {
        listOf(
            "不是JSON", "A", "{}", """{"answer":""}""", """{"answer":"无法确定"}""",
            """{"answer":"A","explanation":false}""",
            """{"answer":"A","explanation":"依据","warnings":[42]}""",
            """{"answer":false,"explanation":"依据","warnings":[]}""",
            """{"answer":"A\n解析：推理内容"}""", """{"answer":"<think>推理</think>A"}""",
            "```json\n{\"answer\":\"A\"}\n```\n附加说明"
        ).forEach { rejects(it) }
    }
    private fun rejects(output: String, question: String = "") {
        try { Api.parseAnswer(output, question); fail("Must reject invalid answer") }
        catch (_: ApiException) { }
    }
}
