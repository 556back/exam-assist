package cn.examassist.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BankNormalizationTest {
    @Test fun answersAndLabelsNormalizeWithoutLosingOriginals() {
        for (answer in listOf("A与C", "[\"A\",\"C\"]", "【答案】（A）、（C）。", "选项A及选项C", "选项：A与C", "甲、丙")) {
            val preview = BankImporter.preview("""[{"stem":"哪些项正确？","options":[{"label":"（ａ）","text":"甲"},{"label":"B.","text":"乙"},{"label":"[C]","text":"丙"}],"answer":${JSONObject.quote(answer)}}]""".toByteArray(), "原格式.json")
            BankTemplate.normalize(preview)
            val row = preview.getJSONArray("questions").getJSONObject(0)
            assertEquals(answer, row.getString("original_reference_answer"))
            assertEquals("AC", row.getString("reference_answer"))
            assertEquals("多选题", row.getString("question_type"))
            assertEquals(1, preview.getJSONObject("quality").getInt("usable_answers"))
            assertEquals("BC", LocalBankMatching.answer("哪些项正确？\nA.乙\nB.丙\nC.甲", listOf(row), threshold = 100).getString("answer"))
            val before = preview.toString()
            assertEquals(before, BankTemplate.normalize(preview).toString())
        }
    }

    @Test fun embeddedOptionsBecomeSeparateFieldsOnlyIfUnambiguous() {
        val preview = BankImporter.preview("""[{"stem":"哪些项正确？A.甲B.乙C.丙","answer":"A与C"}]""".toByteArray(), "内嵌.json")
        val row = BankTemplate.normalize(preview).getJSONArray("questions").getJSONObject(0)
        assertEquals("哪些项正确?", row.getString("stem"))
        assertEquals("哪些项正确？A.甲B.乙C.丙", row.getString("original_stem"))
        assertEquals(3, row.getJSONArray("options").length())
        assertEquals("AC", row.getString("reference_answer"))
    }

    @Test fun unknownDuplicateAndExplanatoryAnswersStayUnusable() {
        for (answer in listOf("[Z]", "A、A", "正确选项可能是A或者C", "A.乙", "=AC")) {
            val preview = BankTemplate.normalize(BankImporter.preview("""[{"stem":"哪些项正确？","options":["甲","乙","丙"],"answer":${JSONObject.quote(answer)}}]""".toByteArray(), "待核对.json"))
            assertEquals(answer, preview.getJSONArray("questions").getJSONObject(0).getString("reference_answer"))
            assertEquals(0, preview.getJSONObject("quality").getInt("usable_answers"))
            assertEquals(1, preview.getJSONObject("quality").getInt("unusable_answers"))
        }
    }

    @Test fun contentAnswersWithInternalCommasStillRemap() {
        val preview = BankTemplate.normalize(BankImporter.preview("""[{"stem":"选择方针","options":["预防为主，防消结合","安全第一","其他"],"answer":"预防为主，防消结合；安全第一"}]""".toByteArray(), "标点.json"))
        assertEquals("AB", preview.getJSONArray("questions").getJSONObject(0).getString("reference_answer"))
    }

    @Test fun diagnosticRetainsExactReadTextParsedOptionsAndNearestRecord() {
        val preview = BankImporter.preview("""[{"stem":"哪些项正确？","options":["甲","乙","丙"],"answer":"AC"}]""".toByteArray(), "原格式.json")
        val raw = "【多选题】\n哪些项正确？\nA.甲\nB.乙\nC.丁\n下一题"
        var report: JSONObject? = null
        val result = LocalBankMatching.lookup(raw, preview.getJSONArray("questions").objects(), threshold = 50) { report = it }
        assertTrue(result is LocalBankMatching.Lookup.Blocked)
        val snapshot = report!!
        assertEquals(raw, snapshot.getString("raw_text"))
        assertEquals("答案不可用", snapshot.getString("status"))
        val question = snapshot.getJSONArray("questions").getJSONObject(0)
        assertEquals(3, question.getJSONObject("options").length())
        assertEquals("丁", question.getJSONObject("options").getString("C"))
        assertEquals("AC", question.getJSONObject("best").getString("reference_answer"))
        assertTrue(BankDiagnostic.text(snapshot).contains(raw))
        assertTrue(snapshot.getString("reason").contains("答案项未对应"))
    }
}
