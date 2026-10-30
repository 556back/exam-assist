package cn.examassist.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Regression cases for complete workbooks and nonstandard table layouts. */
class TableImportRegressionTest {
    @Test fun oneOptionPerRowSheetsKeepContinuationOptionsCorrectMarkersAndFillAnswers() {
        val input = workbook(listOf(
            "导入规则" to listOf(listOf("字段", "填写范围", "说明"), listOf("题干", "文本", "按题目填写")),
            "单选" to listOf(listOf("题干", "选项", "是否正确答案", "题目编号"),
                listOf("第一题", "2008 年", "", "单选第1题"), listOf("", "2009 年", "", ""),
                listOf("", "2019 年", "Y", ""), listOf("", "2021 年", "", ""),
                listOf("题干独占一行", "", "", "单选第2题"), listOf("", "甲", "", ""), listOf("", "乙", "Y", "")),
            "多选" to listOf(listOf("题干", "选项", "是否正确项"),
                listOf("第三题", "甲", "Y"), listOf("", "乙", ""), listOf("", "丙", "Y")),
            "判断" to listOf(listOf("题干", "选项", "is_correct_answer"),
                listOf("第四题", "正确", "Y"), listOf("", "错误", "")),
            "填空" to listOf(listOf("题干", "填空答案"), listOf("第五题 #1#", "原文填空答案")),
            "主观" to listOf(listOf("题干", "参考答案", "关键字", "关键字"))
        ))
        val preview = BankImporter.preview(input, "消防题库.xlsx")
        assertEquals(5, preview.getInt("total"))
        val questions = preview.getJSONArray("questions")
        assertEquals("C", questions.getJSONObject(0).getString("reference_answer"))
        assertEquals(4, questions.getJSONObject(0).getJSONArray("options").length())
        assertEquals("单选第1题", questions.getJSONObject(0).getString("original_id"))
        assertEquals("单选题", questions.getJSONObject(0).getString("question_type"))
        assertEquals("单选", questions.getJSONObject(0).getString("source_sheet"))
        assertEquals(2, questions.getJSONObject(0).getInt("source_row"))
        assertTrue(questions.getJSONObject(0).getString("answer_evidence").contains("第 4 行 是否正确答案: Y"))
        assertTrue(questions.getJSONObject(0).getString("source_text").contains("2009 年"))
        assertEquals("B", questions.getJSONObject(1).getString("reference_answer"))
        assertEquals(2, questions.getJSONObject(1).getJSONArray("options").length())
        assertEquals("AC", questions.getJSONObject(2).getString("reference_answer"))
        assertEquals("多选题", questions.getJSONObject(2).getString("question_type"))
        assertEquals("A", questions.getJSONObject(3).getString("reference_answer"))
        assertEquals("判断题", questions.getJSONObject(3).getString("question_type"))
        assertEquals("原文填空答案", questions.getJSONObject(4).getString("reference_answer"))
        assertEquals("填空题", questions.getJSONObject(4).getString("question_type"))
        val records = BankImporter.extractText(input, "消防题库.xlsx").split(Regex("(?m)^记录 \\d+\\n")).filter { it.isNotBlank() }
        val first = records.single { it.contains("题干: 第一题") }
        assertTrue(first.contains("2008 年")); assertTrue(first.contains("2009 年"))
        assertTrue(first.contains("2019 年")); assertTrue(first.contains("2021 年"))
        assertTrue(first.contains("参考答案: C")); assertFalse(first.contains("题干独占一行"))
    }

    @Test fun templateMetadataSurvivesJsonAndCsvReimport() {
        val json = """[{"stem":"模板题目","options":["甲","乙"],"reference_answer":"B","question_type":"单选题","answer_origin":"model","answer_model":"test-model","answer_provider":"test-provider","answer_evidence":"模型补充","source_file":"原文.xlsx","source_sheet":"单选","source_row":12,"original_id":"第2题","source_text":"完整原文","question_source_text":"题目原文"}]"""
        val question = BankImporter.preview(json.toByteArray(), "模板.json").getJSONArray("questions").getJSONObject(0)
        assertEquals("单选题", question.getString("question_type"))
        assertEquals("model", question.getString("answer_origin"))
        assertEquals("test-model", question.getString("answer_model"))
        assertEquals("test-provider", question.getString("answer_provider"))
        assertEquals("模型补充", question.getString("answer_evidence"))
        assertEquals("原文.xlsx", question.getString("source_file"))
        assertEquals("单选", question.getString("source_sheet"))
        assertEquals(12, question.getInt("source_row"))
        assertEquals("第2题", question.getString("original_id"))
        assertEquals("完整原文", question.getString("source_text"))
        assertEquals("题目原文", question.getString("question_source_text"))
        val csv = "题型,题干,A,B,答案,答案来源,答案依据,工作表,行号\n单选题,模板题目,甲,乙,B,model,模型补充,原始单选,12\n"
        val reimported = BankImporter.preview(csv.toByteArray(), "模板.csv").getJSONArray("questions").getJSONObject(0)
        assertEquals("model", reimported.getString("answer_origin"))
        assertEquals("单选题", reimported.getString("question_type"))
        assertEquals("原始单选", reimported.getString("source_sheet"))
        assertEquals(12, reimported.getInt("source_row"))
    }

    @Test fun optionRowAnswersPreserveExplicitSourcesAndWarnWhenMarkersCannotIdentifyAnswers() {
        val csv = "题干,选项,是否正确项,参考答案\n" +
            "明确原始答案,甲,,B\n,乙,,\n" +
            "冲突原始答案,甲,Y,B\n,乙,,\n" +
            "错误标记行,,Y,\n,甲,,\n,乙,,\n" +
            "后续行答案,甲,,\n,乙,,B\n"
        val preview = BankImporter.preview(csv.toByteArray(), "选项行.csv")
        val questions = preview.getJSONArray("questions")
        assertEquals(3, questions.length())
        assertEquals("B", questions.getJSONObject(0).getString("reference_answer"))
        assertEquals("错误标记行", questions.getJSONObject(1).getString("stem"))
        assertEquals("", questions.getJSONObject(1).getString("reference_answer"))
        assertEquals("A", questions.getJSONObject(1).getJSONArray("options").getJSONObject(0).getString("label"))
        assertEquals("B", questions.getJSONObject(2).getString("reference_answer"))
        val warnings = preview.getJSONArray("warnings").toString()
        assertTrue(warnings.contains("互相矛盾"))
        assertTrue(warnings.contains("标为正确项但选项为空"))
    }

    @Test fun workbookIncludesEveryQuestionSheetAndSkipsEmptyAndInstructionSheets() {
        val input = workbook(listOf(
            "说明" to listOf(listOf("本文件包含多个题库，请逐个工作表阅读。")),
            "空白" to emptyList(),
            "单选题" to listOf(listOf("题干", "答案"), listOf("水的沸点是多少？", "100℃")),
            "隐藏题库" to listOf(listOf("题目", "答案"), listOf("冰水混合物温度是多少？", "0℃"))
        ))
        val preview = BankImporter.preview(input, "完整题库.xlsx")
        assertEquals(2, preview.getInt("total"))
        assertFalse(preview.getBoolean("requires_ai_normalization"))
        val questions = preview.getJSONArray("questions")
        assertEquals("水的沸点是多少？", questions.getJSONObject(0).getString("stem"))
        assertEquals("冰水混合物温度是多少？", questions.getJSONObject(1).getString("stem"))
        val text = BankImporter.extractText(input, "完整题库.xlsx")
        assertTrue(text.contains("本文件包含多个题库"))
        assertTrue(text.contains("单选题"))
        assertTrue(text.contains("隐藏题库"))
        assertTrue(text.contains("100℃"))
        assertTrue(text.contains("0℃"))
    }

    @Test fun verticalFieldsRetainStemsOptionsAndAnswersInTheirOwnRecords() {
        val csv = "题目,水的沸点是多少？\n选项A,100℃\n选项B,90℃\n答案,A\n" +
            "题目,冰水混合物温度是多少？\n选项A,0℃\n选项B,10℃\n答案,A\n"
        val text = BankImporter.extractText(csv.toByteArray(), "竖向题库.csv")
        val records = AiBankImporter.chunks(text).flatMap { chunk ->
            (0 until chunk.length()).map { chunk.getJSONObject(it).getString("source_text") }
        }
        assertEquals(2, records.size)
        assertTrue(records[0].contains("水的沸点是多少？"))
        assertTrue(records[0].contains("100℃"))
        assertTrue(records[0].contains("90℃"))
        assertTrue(records[0].contains("答案: A"))
        assertFalse(records[0].contains("冰水混合物"))
        assertTrue(records[1].contains("冰水混合物温度是多少？"))
        assertTrue(records[1].contains("0℃"))
        assertTrue(records[1].contains("10℃"))
        assertTrue(records[1].contains("答案: A"))
    }

    @Test fun numberedSingleColumnSheetKeepsOptionsAndAnswerWithEachQuestion() {
        val input = workbook(listOf("原始题目" to listOf(
            listOf("1. 水的沸点是多少？"), listOf("A. 100℃"), listOf("B. 90℃"), listOf("答案：A"),
            listOf("2. 冰水混合物温度是多少？"), listOf("A. 0℃"), listOf("B. 10℃"), listOf("答案：A")
        )))
        val records = AiBankImporter.chunks(BankImporter.extractText(input, "单列题库.xlsx")).flatMap { chunk ->
            (0 until chunk.length()).map { chunk.getJSONObject(it).getString("source_text") }
        }
        assertEquals(2, records.size)
        assertTrue(records[0].contains("水的沸点是多少？"))
        assertTrue(records[0].contains("B. 90℃"))
        assertTrue(records[0].contains("答案：A"))
        assertFalse(records[0].contains("冰水混合物"))
        assertTrue(records[1].contains("B. 10℃"))
        assertTrue(records[1].contains("答案：A"))
    }

    @Test fun csvAllowsInstructionsRepeatedHeadersAndQuotedMultilineCells() {
        val csv = "课程题库\r\n题干（必填）;选项A;选项B;答案（必填）\r\n" +
            "\"第一行,保留逗号\n第二行题干\";甲;乙;B\r\n" +
            "题干（必填）;选项A;选项B;答案（必填）\r\n另一道题;丙;丁;A\r\n"
        val preview = BankImporter.preview(csv.toByteArray(), "分号题库.csv")
        assertEquals(2, preview.getInt("total"))
        val questions = preview.getJSONArray("questions")
        assertEquals("第一行,保留逗号\n第二行题干", questions.getJSONObject(0).getString("stem"))
        assertEquals("B", questions.getJSONObject(0).getString("reference_answer"))
        assertEquals("另一道题", questions.getJSONObject(1).getString("stem"))
        val source = BankImporter.extractText(csv.toByteArray(), "分号题库.csv")
        assertTrue(source.contains("课程题库"))
        assertTrue(source.contains("答案: B"))
    }

    @Test fun csvAcceptsUtf16BomAndChineseLegacyEncodingWithSemicolonDelimiter() {
        val csv = "题干;答案\n中文题目;甲\n"
        for (bytes in listOf(
            "\uFEFF$csv".toByteArray(Charsets.UTF_16LE),
            "\uFEFF$csv".toByteArray(Charsets.UTF_16BE),
            csv.toByteArray(Charset.forName("GB18030"))
        )) {
            val question = BankImporter.preview(bytes, "编码.csv").getJSONArray("questions").getJSONObject(0)
            assertEquals("中文题目", question.getString("stem"))
            assertEquals("甲", question.getString("reference_answer"))
        }
    }

    @Test fun emptyCanonicalFieldsDoNotHideFilledAliasesOrIndividualOptions() {
        val csv = "stem,题目,options,A,B,answer,答案\n,实际题干,,甲,乙,,B\n"
        val question = BankImporter.preview(csv.toByteArray(), "备用列.csv").getJSONArray("questions").getJSONObject(0)
        assertEquals("实际题干", question.getString("stem"))
        assertEquals("B", question.getString("reference_answer"))
        assertEquals(2, question.getJSONArray("options").length())
        assertEquals("甲", question.getJSONArray("options").getJSONObject(0).getString("text"))
        assertEquals("乙", question.getJSONArray("options").getJSONObject(1).getString("text"))
    }

    @Test fun punctuationInsideQuestionsDoesNotOverrideTheCsvHeaderDelimiter() {
        for (csv in listOf(
            "题干,答案\n列举甲;乙;丙;丁;戊,A\n列举壹;贰;叁;肆;伍,B\n",
            "题干;答案\n列举甲,乙,丙,丁,戊;A\n列举壹,贰,叁,肆,伍;B\n"
        )) {
            val preview = BankImporter.preview(csv.toByteArray(), "题干标点.csv")
            assertEquals(2, preview.getInt("total"))
            assertEquals("A", preview.getJSONArray("questions").getJSONObject(0).getString("reference_answer"))
            assertEquals("B", preview.getJSONArray("questions").getJSONObject(1).getString("reference_answer"))
        }
    }

    @Test fun invalidQuestionDoesNotHideValidQuestionsInTheSameOrLaterSheets() {
        val input = workbook(listOf(
            "第一表" to listOf(listOf("题干", "选项", "答案"),
                listOf("第一道有效题", "A. 甲 B. 乙", "A"),
                listOf("选项重复的坏题", "A. 甲 A. 乙", "A")),
            "第二表" to listOf(listOf("题干", "答案"), listOf("后续工作表有效题", "保留答案"))
        ))
        val preview = BankImporter.preview(input, "含坏题.xlsx")
        assertEquals(2, preview.getInt("total"))
        assertTrue(preview.getBoolean("requires_ai_normalization"))
        val questions = preview.getJSONArray("questions")
        assertEquals("第一道有效题", questions.getJSONObject(0).getString("stem"))
        assertEquals("后续工作表有效题", questions.getJSONObject(1).getString("stem"))
        assertEquals("保留答案", questions.getJSONObject(1).getString("reference_answer"))
        assertTrue(preview.getJSONArray("warnings").toString().contains("重复"))
    }

    @Test fun mixedStandardAndUnparsedQuestionSheetsRequestAiNormalization() {
        val input = workbook(listOf(
            "标准题目" to listOf(listOf("题干", "答案"), listOf("第一题", "原始答案")),
            "竖向题目" to listOf(listOf("题目", "第二题"), listOf("选项A", "甲"), listOf("选项B", "乙"), listOf("答案", "B")),
            "原始文字" to listOf(listOf("1. 第三题"), listOf("A. 丙"), listOf("B. 丁"), listOf("答案：A")),
            "导入规则" to listOf(listOf("题干", "文本", "必填"), listOf("答案", "按原文填写")),
            "空白" to emptyList(), "主观" to listOf(listOf("题干", "参考答案", "关键字", "关键字"))
        ))
        val preview = BankImporter.preview(input, "混合格式.xlsx")
        assertEquals(1, preview.getInt("total"))
        assertTrue(preview.getBoolean("requires_ai_normalization"))
        val source = BankImporter.extractText(input, "混合格式.xlsx")
        assertTrue(source.contains("题目: 第二题"))
        assertTrue(source.contains("1. 第三题"))
        assertTrue(source.contains("说明内容: 列A: 题干"))
    }

    @Test fun headerlessAnswerFirstRowsDoNotBecomeOneVerticalChoiceQuestion() {
        val csv = "A,第一道题的完整题干\nB,第二道题的完整题干\n"
        val records = AiBankImporter.chunks(BankImporter.extractText(csv.toByteArray(), "无表头.csv")).flatMap { chunk ->
            (0 until chunk.length()).map { chunk.getJSONObject(it).getString("source_text") }
        }
        assertEquals(2, records.size)
        assertTrue(records[0].contains("第一道题的完整题干"))
        assertFalse(records[0].contains("第二道题"))
        assertTrue(records[0].contains("列A: A"))
        assertTrue(records[1].contains("第二道题的完整题干"))
        assertTrue(records[1].contains("列A: B"))
    }

    @Test fun emptyEncodedOptionCollectionsFallBackToIndividualOptionColumns() {
        for (encoded in listOf("[]", "{}")) {
            val csv = "题干,options,A,B,答案\n实际题干,$encoded,甲,乙,B\n"
            val question = BankImporter.preview(csv.toByteArray(), "备用选项.csv").getJSONArray("questions").getJSONObject(0)
            val options = question.getJSONArray("options")
            assertEquals(2, options.length())
            assertEquals("甲", options.getJSONObject(0).getString("text"))
            assertEquals("乙", options.getJSONObject(1).getString("text"))
            assertEquals("B", question.getString("reference_answer"))
        }
    }

    @Test fun questionLimitCountsValidQuestionsAndNeverSilentlyTruncatesThem() {
        val header = "题干,选项,答案\n"
        val valid = (1..5000).joinToString("\n", postfix = "\n") { "有效题目$it,A. 甲 B. 乙,A" }
        val preview = BankImporter.preview((header + "坏题,A. 甲 A. 乙,A\n" + valid).toByteArray(), "5000有效题.csv")
        assertEquals(5000, preview.getInt("total"))
        assertEquals("有效题目5000", preview.getJSONArray("questions").getJSONObject(4999).getString("stem"))
        assertTrue(preview.getJSONArray("warnings").toString().contains("重复"))
        val failure = runCatching {
            BankImporter.preview((header + valid + "有效题目5001,A. 甲 B. 乙,A\n").toByteArray(), "5001有效题.csv")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("5000"))
    }

    @Test fun delimitersAreDetectedAfterLongInstructionsAndHonorExcelSeparatorHints() {
        val instructions = (1..30).joinToString("\n", postfix = "\n") { "第 $it 行使用说明" }
        for ((name, csv) in listOf(
            "长说明.tsv" to instructions + "题干\t答案\n后续题目\t原始答案\n",
            "自动识别.csv" to instructions + "题干\t答案\n后续题目\t原始答案\n",
            "指定分隔符.csv" to "sep=|\n" + instructions + "题干|答案\n后续题目|原始答案\n"
        )) {
            val preview = BankImporter.preview(csv.toByteArray(), name)
            assertEquals(1, preview.getInt("total"))
            assertEquals("后续题目", preview.getJSONArray("questions").getJSONObject(0).getString("stem"))
            assertEquals("原始答案", preview.getJSONArray("questions").getJSONObject(0).getString("reference_answer"))
        }
    }

    private fun workbook(sheets: List<Pair<String, List<List<String>>>>): ByteArray {
        val namespace = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val files = linkedMapOf(
            "xl/workbook.xml" to "<workbook xmlns=\"$namespace\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>" +
                sheets.mapIndexed { index, (name, _) ->
                    "<sheet name=\"$name\" sheetId=\"${index + 1}\" r:id=\"r${index + 1}\"${if (name == "隐藏题库") " state=\"hidden\"" else ""}/>"
                }.joinToString("") + "</sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                sheets.indices.joinToString("") { "<Relationship Id=\"r${it + 1}\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } + "</Relationships>"
        )
        sheets.forEachIndexed { sheetIndex, (_, rows) ->
            files["xl/worksheets/sheet${sheetIndex + 1}.xml"] = "<worksheet xmlns=\"$namespace\"><sheetData>" +
                rows.mapIndexed { rowIndex, cells ->
                    "<row r=\"${rowIndex + 1}\">" + cells.mapIndexed { column, value ->
                        val escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        "<c r=\"${'A' + column}${rowIndex + 1}\" t=\"inlineStr\"><is><t>$escaped</t></is></c>"
                    }.joinToString("") + "</row>"
                }.joinToString("") + "</sheetData></worksheet>"
        }
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> files.forEach { (name, value) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(value.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        } }
        return bytes.toByteArray()
    }
}
