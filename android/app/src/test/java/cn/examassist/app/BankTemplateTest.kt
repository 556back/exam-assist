package cn.examassist.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BankTemplateTest {
    @Test fun exportedTemplateCanBeImportedAndMissingAnswersStayDistinct() {
        assertEquals(1, BankTemplate.csv.trim().lines().size)
        assertTrue(!BankTemplate.csv.contains("示例"))
        val content = BankTemplate.csv + "单选题,1加1等于多少？,1,2,3,4,,,B,,测试,原题库\n" +
            "多选题,以下哪些数字是偶数？,1,2,3,4,,,BD,,测试,原题库\n" +
            "判断题,2大于1。,,,,,,,对,,测试,原题库\n"
        val preview = BankTemplate.normalize(BankImporter.preview(content.toByteArray(), "题库模板.csv"))
        val questions = preview.getJSONArray("questions")
        assertEquals(3, questions.length())
        assertEquals(BankTemplate.VERSION, preview.getInt("schema_version"))
        assertEquals("B", questions.getJSONObject(0).getString("reference_answer"))
        assertEquals("BD", questions.getJSONObject(1).getString("reference_answer"))
        assertEquals("多选题", questions.getJSONObject(1).getString("question_type"))
        assertEquals("对", questions.getJSONObject(2).getString("reference_answer"))
        questions.getJSONObject(0).put("reference_answer", "").put("answer_origin", "original")
        questions.getJSONObject(1).put("answer_origin", "model").put("answer_model", "saved-model")
        BankTemplate.normalize(preview)
        assertEquals("missing", questions.getJSONObject(0).getString("answer_origin"))
        assertEquals("model", questions.getJSONObject(1).getString("answer_origin"))
        assertEquals("saved-model", questions.getJSONObject(1).getString("answer_model"))
    }

    @Test fun regressionBankUsesTemplateAndEachChoiceAnswerCanBeMatchedOffline() {
        val preview = SyntheticBank.preview()
        val questions = preview.getJSONArray("questions").objects()
        assertTrue(questions.isNotEmpty())
        assertEquals(questions.size, preview.getInt("total"))
        for (question in questions) {
            assertTrue(question.getString("reference_answer").isNotBlank())
            assertEquals("original", question.getString("answer_origin"))
            assertTrue(question.getString("source_text").isNotBlank())
            val options = question.getJSONArray("options").objects()
            if (options.isNotEmpty()) {
                val raw = question.getString("stem") + "\n" + options.joinToString("\n") { "${it.getString("label")}. ${it.getString("text")}" }
                assertEquals(question.getString("reference_answer"), LocalBankMatching.answer(raw, listOf(question)).getString("answer"))
            }
        }
    }
}
