package cn.examassist.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BankImportTest {
    @Test fun csvSupportsQuotedMultilineCellsAndEscapedQuotes() {
        val rows = BankImporter.csvRows("题目,答案,来源\r\n\"第一行\n第二行,含逗号\",\"A. \"\"文本\"\"\",本地\r\n")
        assertEquals(1, rows.size)
        assertEquals("第一行\n第二行,含逗号", rows[0]["题目"])
        assertEquals("A. \"文本\"", rows[0]["答案"])
    }

    @Test fun csvAcceptsBomUtf8AndLegacyChineseEncoding() {
        assertEquals("题目,答案", BankImporter.decodeCsv("\uFEFF题目,答案".toByteArray()))
        assertEquals("题目,答案", BankImporter.decodeCsv("题目,答案".toByteArray(Charset.forName("GB18030"))))
    }

    @Test(expected = IllegalArgumentException::class) fun malformedCsvQuotesAreRejected() {
        BankImporter.csvRows("题目,答案\n\"未闭合,A")
    }

    @Test(expected = IllegalArgumentException::class) fun excessiveCsvRowsAreRejected() {
        BankImporter.csvRows("题目,答案\n" + "题干,A\n".repeat(5001))
    }

    @Test fun referencesMatchChineseTextButDiscountChangedNumbersAndNegation() {
        val current = BankMatching.prepare("以下计算 12 + 30 的结果是？\nA. 42\nB. 52")
        val exact = BankMatching.prepare("以下计算 12 + 30 的结果是？")
        val changed = BankMatching.prepare("以下计算 12 + 40 的结果是？")
        assertEquals(1.0, BankMatching.score(current, exact), 0.0001)
        assertTrue(BankMatching.score(current, changed) < 0.5)
        val positive = BankMatching.prepare("以下描述正确的是")
        val negative = BankMatching.prepare("以下描述不正确的是")
        assertTrue(BankMatching.score(positive, negative) < 0.5)
        assertEquals(0.0, BankMatching.score(current, BankMatching.prepare("苹果和橘子属于水果")), 0.0001)
    }

    @Test fun jsonPreservesOriginalAnswersLabelsSourcesAndCanonicalSchema() {
        val input = """
            {"questions":[
              {"题目":"选择正确项","选项":{"A":"第一项","C":"第三项"},"参考答案":"A与C","解析":"原始解析","出处":"课程讲义"},
              {"stem":"另一道题","options":["甲","乙"],"answer":["A","B"]}
            ]}
        """.trimIndent()
        val preview = BankImporter.preview(input.toByteArray(), "中文题库.json")
        assertEquals("中文题库", preview.getString("name"))
        assertEquals(2, preview.getInt("total"))
        val first = preview.getJSONArray("questions").getJSONObject(0)
        assertEquals("选择正确项", first.getString("stem"))
        assertEquals("A与C", first.getString("reference_answer"))
        assertEquals("原始解析", first.getString("explanation"))
        assertEquals("课程讲义", first.getString("source"))
        val options = first.getJSONArray("options")
        assertEquals(mapOf("A" to "第一项", "C" to "第三项"),
            (0 until options.length()).associate { options.getJSONObject(it).let { option -> option.getString("label") to option.getString("text") } })
        val second = preview.getJSONArray("questions").getJSONObject(1)
        assertEquals("[\"A\",\"B\"]", second.getString("reference_answer"))
        assertEquals("B", second.getJSONArray("options").getJSONObject(1).getString("label"))
        assertEquals("中文题库.json", second.getString("source"))
        assertTrue(preview.getJSONArray("warnings").toString().contains("结构化或数值答案"))
    }

    @Test fun aliasesAndTabSeparatedFilesProduceCanonicalQuestions() {
        val preview = BankImporter.preview("question_text\toption_A\toption_B\tcorrect_answer\tanalysis\n选择正确描述\t第一项\t第二项\tB\t原文解析\n".toByteArray(), "通用.tsv")
        val question = preview.getJSONArray("questions").getJSONObject(0)
        assertEquals("选择正确描述", question.getString("stem"))
        assertEquals("B", question.getString("reference_answer"))
        assertEquals("原文解析", question.getString("explanation"))
        assertEquals(2, question.getJSONArray("options").length())
    }

    @Test fun aggregateOptionsFieldCannotBecomeAnExtraOptionS() {
        val preview = BankImporter.preview("题干,options,option_A,option_S,答案\n选择正确描述,[],甲,乙,S\n".toByteArray(), "稀疏选项.csv")
        val options = preview.getJSONArray("questions").getJSONObject(0).getJSONArray("options")
        assertEquals(2, options.length())
        assertEquals("A", options.getJSONObject(0).getString("label"))
        assertEquals("S", options.getJSONObject(1).getString("label"))
        assertEquals("乙", options.getJSONObject(1).getString("text"))
    }

    @Test fun chineseOptionColumnAliasesPreserveEveryChoiceForLocalAndAiImport() {
        val csv = "题干,Ａ选项,选项B内容,答案\n选择正确描述,甲,乙,B\n".toByteArray()
        val question = BankImporter.preview(csv, "选项别名.csv").getJSONArray("questions").getJSONObject(0)
        assertEquals(2, question.getJSONArray("options").length())
        assertEquals("乙", question.getJSONArray("options").getJSONObject(1).getString("text"))
        val source = BankImporter.extractText(csv, "选项别名.csv")
        assertTrue(source.contains("选项A: 甲"))
        assertTrue(source.contains("选项B: 乙"))
    }

    @Test fun plainTextKeepsQuestionBoundariesInlineOptionsAndMissingAnswers() {
        val input = """
            课程复习
            1. 以下描述正确的是？
            A. 甲 B. 乙 C. 丙
            【答案】 A与C
            【解析】 保留原始解析
            2、请写出理由
            第二行题干
        """.trimIndent()
        val preview = BankImporter.preview(input.toByteArray(), "原始题库.txt")
        val questions = preview.getJSONArray("questions")
        assertEquals(2, questions.length())
        assertEquals("以下描述正确的是？", questions.getJSONObject(0).getString("stem"))
        assertEquals(3, questions.getJSONObject(0).getJSONArray("options").length())
        assertEquals("A与C", questions.getJSONObject(0).getString("reference_answer"))
        assertEquals("保留原始解析", questions.getJSONObject(0).getString("explanation"))
        assertEquals("请写出理由\n第二行题干", questions.getJSONObject(1).getString("stem"))
        assertEquals("", questions.getJSONObject(1).getString("reference_answer"))
        assertTrue(preview.getJSONArray("warnings").toString().contains("没有参考答案"))
        assertEquals("原始题库.txt", preview.getString("source_file"))
    }

    @Test fun docxTextIsReadLocallyAndJsonIsRenderedWithoutEscapedAnswerText() {
        val xml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:r><w:t>1. 甲 &amp; 乙是什么？</w:t></w:r></w:p>
            <w:p><w:r><w:t>答案：朋友</w:t></w:r></w:p>
            </w:body></w:document>"""
        val preview = BankImporter.preview(zip(mapOf("word/document.xml" to xml)), "讲义.docx")
        assertEquals("甲 & 乙是什么？", preview.getJSONArray("questions").getJSONObject(0).getString("stem"))
        assertEquals("朋友", preview.getJSONArray("questions").getJSONObject(0).getString("reference_answer"))
        val text = BankImporter.extractText("""{"items":[{"题目内容":"第一行\n第二行","标准答案":"甲\n乙"}]}""".toByteArray(), "别名.json")
        assertTrue(text.contains("标准答案: 甲\n乙"))
        assertEquals("甲\n乙", BankImporter.preview("""{"items":[{"题目内容":"题干","标准答案":"甲\n乙"}]}""".toByteArray(), "别名.json").getJSONArray("questions").getJSONObject(0).getString("reference_answer"))
    }

    @Test(expected = IllegalArgumentException::class) fun ambiguousRepeatedAnswersAreRejected() {
        BankImporter.previewText("第一道题\n答案:A\n第二道题\n答案:B", "没有题号.txt")
    }

    @Test(expected = IllegalArgumentException::class) fun duplicateOptionLabelsAreRejected() {
        BankImporter.preview("""[{"stem":"题目","options":[{"label":"A","text":"甲"},{"label":"a","text":"乙"}],"answer":"A"}]""".toByteArray(), "重复.json")
    }

    @Test(expected = IllegalArgumentException::class) fun duplicateNormalizedHeadersAreRejected() {
        BankImporter.preview("题目,答案,答 案\n试题,A,B".toByteArray(), "重复.csv")
    }

    @Test fun incompleteChoiceOptionsAndInvalidOriginalAnswerLabelsAreWarned() {
        val preview = BankImporter.preview("""[{"stem":"题目","options":{"A":"甲"},"answer":"C"}]""".toByteArray(), "需核对.json")
        assertEquals("C", preview.getJSONArray("questions").getJSONObject(0).getString("reference_answer"))
        val warnings = preview.getJSONArray("warnings").toString()
        assertTrue(warnings.contains("只有一个选项"))
        assertTrue(warnings.contains("不存在的选项"))
    }

    @Test(expected = IllegalArgumentException::class) fun conflictingAnswerAliasesAreRejected() {
        BankImporter.preview("""[{"stem":"题目","answer":"A","参考答案":"B"}]""".toByteArray(), "冲突.json")
    }

    @Test fun xlsxReadsAllSheetsAndPreservesVisibleTextAndUncalculatedFormula() {
        val workbook = """
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
              xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <bookViews><workbookView activeTab="1"/></bookViews>
              <sheets><sheet name="旧表" sheetId="1" r:id="old"/><sheet name="题库" sheetId="2" r:id="active"/></sheets>
            </workbook>
        """.trimIndent()
        val relationships = """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="old" Target="worksheets/sheet1.xml"/>
              <Relationship Id="active" Target="/xl/worksheets/sheet2.xml"/>
            </Relationships>
        """.trimIndent()
        val strings = """
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <si><r><t>比较 A &amp; B 与 </t></r><r><t><![CDATA[C < D]]></t></r>
                <rPh sb="0" eb="2"><t>注音不应出现</t></rPh><r><t>，选择正确项</t></r></si>
              <si><t>课程 &amp; 讲义</t></si>
            </sst>
        """.trimIndent()
        val activeSheet = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
              <row r="1">
                <c r="A1" t="inlineStr"><is><t>题目</t></is></c>
                <c r="B1" t="inlineStr"><is><t>选项A</t></is></c>
                <c r="C1" t="inlineStr"><is><t>选项B</t></is></c>
                <c r="D1" t="inlineStr"><is><t>答案</t></is></c>
                <c r="E1" t="inlineStr"><is><t>解析</t></is></c>
                <c r="F1" t="inlineStr"><is><t>来源</t></is></c>
              </row>
              <row r="2">
                <c r="A2" t="s"><v>0</v></c>
                <c r="B2" t="inlineStr"><is><t>第一项 &amp; 第二项</t><rPh sb="0" eb="2"><t>inline注音</t></rPh></is></c>
                <c r="C2" t="inlineStr"><is><t><![CDATA[第三项 < 第四项]]></t></is></c>
                <c r="D2"><f>"A"&amp;"B"</f><v>AB</v></c>
                <c r="E2" t="inlineStr"><is><t>保留公式，请核对</t></is></c>
                <c r="F2" t="s"><v>1</v></c>
              </row>
            </sheetData></worksheet>
        """.trimIndent()
        val oldSheet = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
              <row r="1"><c r="A1" t="inlineStr"><is><t>题目</t></is></c></row>
              <row r="2"><c r="A2" t="inlineStr"><is><t>其他表的题目也应导入</t></is></c></row>
            </sheetData></worksheet>
        """.trimIndent()
        val preview = BankImporter.preview(zip(mapOf(
            "xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to relationships,
            "xl/sharedStrings.xml" to strings,
            "xl/worksheets/sheet1.xml" to oldSheet,
            "xl/worksheets/sheet2.xml" to activeSheet
        )), "活动题库.xlsx")
        assertEquals(2, preview.getInt("total"))
        assertEquals("其他表的题目也应导入", preview.getJSONArray("questions").getJSONObject(0).getString("stem"))
        val question = preview.getJSONArray("questions").getJSONObject(1)
        assertEquals("比较 A & B 与 C < D，选择正确项", question.getString("stem"))
        assertEquals("第一项 & 第二项", question.getJSONArray("options").getJSONObject(0).getString("text"))
        assertEquals("第三项 < 第四项", question.getJSONArray("options").getJSONObject(1).getString("text"))
        assertEquals("=\"A\"&\"B\"", question.getString("reference_answer"))
        assertEquals("课程 & 讲义", question.getString("source"))
        assertTrue(preview.getJSONArray("warnings").toString().contains("未计算"))
    }

    @Test fun xlsxRejectsExternalDocumentDeclarations() {
        val content = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE workbook SYSTEM "file:///must-not-read-external-dtd">
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"/>
        """.trimIndent()
        val failure = runCatching { BankImporter.preview(zip(mapOf("xl/workbook.xml" to content)), "外部声明.xlsx") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("XLSX XML 不接受外部声明。", failure?.message)
    }

    private fun zip(files: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output -> files.forEach { (name, text) ->
            output.putNextEntry(ZipEntry(name))
            output.write(text.toByteArray(Charsets.UTF_8))
            output.closeEntry()
        } }
        return bytes.toByteArray()
    }
}
