package cn.examassist.app

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBankImportTest {
    private val original = "1. 水的沸点是多少？\nA. 100℃\nB. 90℃\n答案：A\n解析：标准气压下沸腾。"
    private fun records(source: String = original) = JSONArray().put(JSONObject().put("source_id", 1).put("source_text", source))
    private fun item(source: String = original) = JSONObject().put("source_id", 1).put("stem", "水的沸点是多少？")
        .put("options", JSONArray().put(JSONObject().put("label", "A").put("text", "100℃"))
            .put(JSONObject().put("label", "B").put("text", "90℃")))
        .put("reference_answer", "A").put("explanation", "标准气压下沸腾。")
        .put("source_text", source).put("answer_evidence", "答案：A")
    private fun response(item: JSONObject, finish: String = "stop") = response(JSONArray().put(item), finish)
    private fun response(items: JSONArray, finish: String = "stop", skipped: JSONArray = JSONArray()) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", finish).put("message", JSONObject().put("content", JSONObject().put("questions", items).put("skipped_sources", skipped).toString()))))
    private fun skipped(id: Int, reason: String = "说明：仅包含导入说明") = JSONObject().put("source_id", id).put("reason", reason)
    private fun requestedRecords(body: ByteArray) = JSONObject(JSONObject(String(body, Charsets.UTF_8))
        .getJSONArray("messages").getJSONObject(1).getString("content")).getJSONArray("records")
    private fun simpleItem(record: JSONObject): JSONObject {
        val source = record.getString("source_text")
        val stem = Regex("(?m)^题目: (.*)$").find(source)!!.groupValues[1]
        val answer = Regex("(?m)^答案: (.*)$").find(source)?.groupValues?.get(1).orEmpty()
        return JSONObject().put("source_id", record.getInt("source_id")).put("stem", stem).put("options", JSONArray())
            .put("reference_answer", answer).put("explanation", "").put("source_text", source)
            .put("answer_evidence", if (answer.isBlank()) "" else "答案: $answer")
    }

    @Test fun validExtractionRetainsSourceAndOriginalAnswer() {
        val parsed = AiBankImporter.parseResponse(response(item()), records(), "讲义.txt")
        assertEquals("A", parsed.getJSONObject(0).getString("reference_answer"))
        assertEquals(original, parsed.getJSONObject(0).getString("source_text"))
        assertEquals("讲义.txt", parsed.getJSONObject(0).getString("source_file"))
        assertEquals("ai_normalized", parsed.getJSONObject(0).getString("import_method"))
    }

    @Test fun annotatedAnswersArePreservedWithoutBecomingExtraOptions() {
        val source = original.replace("答案：A", "答案：A. 100℃")
        val annotated = item(source).put("reference_answer", "A. 100℃").put("answer_evidence", "答案：A. 100℃")
        assertEquals("A. 100℃", AiBankImporter.parseResponse(response(annotated), records(source), "讲义.txt")
            .getJSONObject(0).getString("reference_answer"))
    }

    @Test fun fillAnswerColumnIsPreservedAsAnOriginalAnswerRatherThanSolved() {
        val source = "记录 1\n工作表: 填空题\n题目: 钠的元素符号？\n填空答案: Na\n题目解析: 元素符号由大小写字母组成。"
        val question = JSONObject().put("source_id", 1).put("stem", "钠的元素符号？").put("question_type", "填空题")
            .put("options", JSONArray()).put("reference_answer", "Na").put("explanation", "元素符号由大小写字母组成。")
            .put("source_text", source).put("answer_evidence", "填空答案: Na")
        val parsed = AiBankImporter.parseResponse(response(question), records(source), "消防.xlsx").getJSONObject(0)
        assertEquals("Na", parsed.getString("reference_answer"))
        assertEquals("original", parsed.getString("answer_origin"))
        assertEquals("填空答案: Na", parsed.getString("answer_evidence"))
        val omitted = JSONObject(question.toString()).put("reference_answer", "").put("answer_evidence", "")
        assertTrue(runCatching { AiBankImporter.parseResponse(response(omitted), records(source), "消防.xlsx") }.isFailure)
    }

    @Test fun changingInventingAndOmittingAnAnswerAreRejected() {
        val changed = item().put("reference_answer", "B")
        assertTrue(runCatching { AiBankImporter.parseResponse(response(changed), records(), "原文.txt") }.isFailure)
        val invented = item().put("reference_answer", "B").put("answer_evidence", "答案：B")
        assertTrue(runCatching { AiBankImporter.parseResponse(response(invented), records(), "原文.txt") }.isFailure)
        val omitted = item().put("reference_answer", "").put("answer_evidence", "")
        assertTrue(runCatching { AiBankImporter.parseResponse(response(omitted), records(), "原文.txt") }.isFailure)
        val separateLine = original.replace("答案：A", "答案：\nA")
        omitted.put("source_text", separateLine)
        assertTrue(runCatching { AiBankImporter.parseResponse(response(omitted), records(separateLine), "原文.txt") }.isFailure)
    }

    @Test fun missingSourceAnswerRemainsBlankAndMultilineAnswersArePreserved() {
        val source = "1. 请填写\n题干第二行"
        val blank = JSONObject().put("source_id", 1).put("stem", "请填写\n题干第二行").put("options", JSONArray())
            .put("reference_answer", "").put("explanation", "").put("source_text", source).put("answer_evidence", "")
        assertEquals("", AiBankImporter.parseResponse(response(blank), records(source), "题目.txt").getJSONObject(0).getString("reference_answer"))
        val multiSource = "1. 请填写\n答案: 甲\n乙"
        val multi = blank.put("stem", "请填写").put("source_text", multiSource).put("reference_answer", "甲\n乙").put("answer_evidence", "答案: 甲\n乙")
        assertEquals("甲\n乙", AiBankImporter.parseResponse(response(multi), records(multiSource), "题目.txt").getJSONObject(0).getString("reference_answer"))
    }

    @Test fun swappedOptionTextsAndChangedLabelsAreRejected() {
        val swapped = item().put("options", JSONArray().put(JSONObject().put("label", "A").put("text", "90℃"))
            .put(JSONObject().put("label", "B").put("text", "100℃")))
        assertTrue(runCatching { AiBankImporter.parseResponse(response(swapped), records(), "原文.txt") }.isFailure)
        val changedLabel = item()
        changedLabel.getJSONArray("options").getJSONObject(0).put("label", "C")
        assertTrue(runCatching { AiBankImporter.parseResponse(response(changedLabel), records(), "原文.txt") }.isFailure)
        val omitted = item().put("options", JSONArray())
        assertTrue(runCatching { AiBankImporter.parseResponse(response(omitted), records(), "原文.txt") }.isFailure)
    }

    @Test fun scientificCaseAndOriginalStemCannotBeChanged() {
        val wordSource = "1. It is now here\n答案: 对"
        val merged = JSONObject().put("source_id", 1).put("stem", "It is nowhere").put("options", JSONArray())
            .put("reference_answer", "对").put("explanation", "").put("source_text", wordSource).put("answer_evidence", "答案: 对")
        assertTrue(runCatching { AiBankImporter.parseResponse(response(merged), records(wordSource), "英语.txt") }.isFailure)
        val source = "1. Na 是哪种元素？\n答案: 钠"
        val changed = JSONObject().put("source_id", 1).put("stem", "NA 是哪种元素？").put("options", JSONArray())
            .put("reference_answer", "钠").put("explanation", "").put("source_text", source).put("answer_evidence", "答案: 钠")
        assertTrue(runCatching { AiBankImporter.parseResponse(response(changed), records(source), "化学.txt") }.isFailure)
        val formulaSource = "1. 求 x² 的导数\n答案: 2x"
        changed.put("stem", "求 x2 的导数").put("reference_answer", "2x").put("source_text", formulaSource).put("answer_evidence", "答案: 2x")
        assertTrue(runCatching { AiBankImporter.parseResponse(response(changed), records(formulaSource), "数学.txt") }.isFailure)
    }

    @Test fun structuredSparseOptionLabelsAreRenderedAndPreserved() {
        val input = """[{"stem":"选项题","options":[{"label":"C","text":"丙"},{"label":"E","text":"戊"}],"answer":"E"}]"""
        val source = BankImporter.extractText(input.toByteArray(), "稀疏.json")
        assertTrue(source.contains("C. 丙\nE. 戊"))
        val item = JSONObject().put("source_id", 1).put("stem", "选项题").put("options", JSONArray()
            .put(JSONObject().put("label", "C").put("text", "丙")).put(JSONObject().put("label", "E").put("text", "戊")))
            .put("reference_answer", "E").put("explanation", "").put("source_text", source).put("answer_evidence", "answer: E")
        assertEquals("E", AiBankImporter.parseResponse(response(item), records(source), "稀疏.json").getJSONObject(0).getString("reference_answer"))
    }

    @Test fun truncationEmptyOutputAndOmittedRecordsAreRejected() {
        assertTrue(runCatching { AiBankImporter.parseResponse(response(item(), "length"), records(), "原文.txt") }.isFailure)
        assertTrue(runCatching { AiBankImporter.parseResponse(response(JSONArray()), records(), "原文.txt") }.isFailure)
        val input = records().put(JSONObject().put("source_id", 2).put("source_text", "2. 第二题\n答案:B"))
        assertTrue(runCatching { AiBankImporter.parseResponse(response(item()), input, "原文.txt") }.isFailure)
    }

    @Test fun explicitNonQuestionSkipsAreAuditedAndMayFillAnEntireBatch() {
        val instruction = "记录 1\n工作表: A. 使用说明\n行号: 1\n表头: 列A=题目; 列B=答案\n备注: 按工作表顺序导入。"
        val skippedRecords = JSONArray()
        val parsed = AiBankImporter.parseResponse(response(JSONArray(), skipped = JSONArray().put(skipped(1))), records(instruction), "多表.xlsx", skippedRecords)
        assertEquals(0, parsed.length())
        assertEquals(instruction, skippedRecords.getJSONObject(0).getString("source_text"))
        assertTrue(skippedRecords.getJSONObject(0).getString("reason").contains("说明"))

        val input = records(instruction).put(JSONObject().put("source_id", 2).put("source_text", original))
        val question = item().put("source_id", 2)
        assertEquals(1, AiBankImporter.parseResponse(response(JSONArray().put(question), skipped = JSONArray().put(skipped(1))), input, "多表.xlsx").length())
    }

    @Test fun skippedRecordsCannotHideQuestionsAnswersOrUncertainty() {
        for (source in listOf("题目: 没有答案也须保留。", "题目:\n多行题干须保留。", "question_text: Preserve this question.",
            "1. 没有参考答案的填空题", "列A: 1. 没有参考答案的填空题", "第 2 题 没有参考答案的题目", "A. 甲", "答案: A", "答案:\n甲")) {
            assertTrue(source, runCatching { AiBankImporter.parseResponse(response(JSONArray(), skipped = JSONArray().put(skipped(1))), records(source), "题库.xlsx") }.isFailure)
        }
        val instruction = records("说明: 按表格顺序导入。")
        for (skip in listOf(skipped(1, ""), skipped(1, "无法确定是否为题目"), skipped(2), skipped(1).put("source_id", 1.5))) {
            assertTrue(runCatching { AiBankImporter.parseResponse(response(JSONArray(), skipped = JSONArray().put(skip)), instruction, "说明.xlsx") }.isFailure)
        }
        assertTrue(runCatching { AiBankImporter.parseResponse(response(JSONArray(), skipped = JSONArray().put(skipped(1)).put(skipped(1))), instruction, "说明.xlsx") }.isFailure)
        assertTrue(runCatching { AiBankImporter.parseResponse(response(JSONArray().put(item()), skipped = JSONArray().put(skipped(1))), records(), "题库.xlsx") }.isFailure)
        val blankStem = records("题目:\n备注: 空题干的说明行。\nAnswering tips: Use original source materials.")
        assertEquals(0, AiBankImporter.parseResponse(response(JSONArray(), skipped = JSONArray().put(skipped(1))), blankStem, "说明.xlsx").length())
    }

    @Test fun sheetAndHeaderMetadataDoNotBecomeQuestionOptions() {
        val source = "记录 1\n工作表: A. 第一章 B. 单选题\n行号: 2\n表头: 列A=题目; 列B=选项A; 列C=答案（必填）\n$original"
        assertEquals(2, AiBankImporter.parseResponse(response(item(source)), records(source), "题库.xlsx").getJSONObject(0).getJSONArray("options").length())
        for (label in listOf("答案（必填）", "correct answer", "reference_answer", "CorrectAnswer")) {
            val annotated = original.replace("答案：A", "$label: A")
            assertEquals("A", AiBankImporter.parseResponse(response(item(annotated).put("answer_evidence", "$label: A")), records(annotated), "题库.xlsx")
                .getJSONObject(0).getString("reference_answer"))
        }
    }

    @Test fun numberedOperatingInstructionsRequireBothAnInstructionsSheetAndUnambiguousActions() {
        val instructions = "记录 1\n工作表: 使用说明\n行号: 1\n列A: 1. 请勿更改表头\n列A: 2. 导入前请核对答案"
        val output = response(JSONArray(), skipped = JSONArray().put(skipped(1)))
        assertEquals(0, AiBankImporter.parseResponse(output, records(instructions), "导入.xlsx").length())
        for (source in listOf(instructions.replace("使用说明", "正式题库"),
            instructions.replace("1. 请勿更改表头", "1. 没有参考答案的填空题"),
            instructions.replace("1. 请勿更改表头", "1. 请选择正确的 CSV 文件格式"),
            instructions.replace("1. 请勿更改表头", "1. 请导入哪个文件？"),
            instructions + "\n题目: 真实题干")) {
            assertTrue(source, runCatching { AiBankImporter.parseResponse(output, records(source), "导入.xlsx") }.isFailure)
        }
    }

    @Test fun distinctOptionLabelsMayFaithfullyHaveIdenticalText() {
        val source = "题目: 请选择\nA. 相同文字\nB. 相同文字\n答案: B"
        val question = JSONObject().put("source_id", 1).put("stem", "请选择").put("options", JSONArray()
            .put(JSONObject().put("label", "A").put("text", "相同文字")).put(JSONObject().put("label", "B").put("text", "相同文字")))
            .put("reference_answer", "B").put("explanation", "").put("source_text", source).put("answer_evidence", "答案: B")
        assertEquals(2, AiBankImporter.parseResponse(response(question), records(source), "原文.txt").getJSONObject(0).getJSONArray("options").length())
    }

    @Test fun sourceSlicesCannotHideTheContinuationOfAMultilineAnswer() {
        val source = "题目: 请填写\n答案: 甲\n乙"
        val partial = JSONObject().put("source_id", 1).put("stem", "请填写").put("options", JSONArray())
            .put("reference_answer", "甲").put("explanation", "").put("source_text", source.substringBeforeLast('\n'))
            .put("answer_evidence", "答案: 甲")
        assertTrue(runCatching { AiBankImporter.parseResponse(response(partial), records(source), "多行答案.csv") }.isFailure)
        val complete = partial.put("source_text", source).put("reference_answer", "甲\n乙").put("answer_evidence", "答案: 甲\n乙")
        assertEquals("甲\n乙", AiBankImporter.parseResponse(response(complete), records(source), "多行答案.csv").getJSONObject(0).getString("reference_answer"))
        val blankSource = "题目: 请填写\n答案:\n备注: 留空答案"
        val blank = complete.put("source_text", blankSource).put("reference_answer", "").put("answer_evidence", "")
        assertEquals("", AiBankImporter.parseResponse(response(blank), records(blankSource), "空答案.csv").getJSONObject(0).getString("reference_answer"))
    }

    @Test fun questionsInOneRecordMustUseExclusiveOrderedSourceSlices() {
        val firstSource = "题一是什么？\n答案:A"
        val secondSource = "题二是什么？\n答案:B"
        val source = "$firstSource\n\n$secondSource"
        fun question(stem: String, slice: String, answer: String) = JSONObject().put("source_id", 1).put("stem", stem)
            .put("options", JSONArray()).put("reference_answer", answer).put("explanation", "")
            .put("source_text", slice).put("answer_evidence", "答案:$answer")
        val valid = JSONArray().put(question("题一是什么？", firstSource, "A")).put(question("题二是什么？", secondSource, "B"))
        assertEquals(2, AiBankImporter.parseResponse(response(valid), records(source), "多题.txt").length())
        val swapped = JSONArray().put(question("题一是什么？", source, "B")).put(question("题二是什么？", source, "A"))
        assertTrue(runCatching { AiBankImporter.parseResponse(response(swapped), records(source), "多题.txt") }.isFailure)
        val reversed = JSONArray().put(question("题二是什么？", secondSource, "B")).put(question("题一是什么？", firstSource, "A"))
        assertTrue(runCatching { AiBankImporter.parseResponse(response(reversed), records(source), "多题.txt") }.isFailure)
    }

    @Test fun chunksNeverSeparateSourceRecordFromItsAnswerOrNestedNumberedLines() {
        val first = "记录 1\n题目: " + "甲".repeat(2800) + "\n1. 条件一\n2. 条件二\n答案: A\n\n"
        val second = "记录 2\n题目: " + "乙".repeat(2800) + "\n答案: B"
        val chunks = AiBankImporter.chunks(first + second)
        assertEquals(2, chunks.size)
        assertEquals(first.trim(), chunks[0].getJSONObject(0).getString("source_text"))
        assertEquals(second.trim(), chunks[1].getJSONObject(0).getString("source_text"))
        assertTrue(runCatching { AiBankImporter.chunks("无题号" + "甲".repeat(20000)) }.isFailure)
        val long = "记录 1\n题目: " + "甲".repeat(8000) + "\n答案: A"
        val longChunks = AiBankImporter.chunks(long + "\n\n记录 2\n题目: 第二题\n答案: B")
        assertEquals(2, longChunks.size)
        assertEquals(1, longChunks[0].length())
        assertEquals(long, longChunks[0].getJSONObject(0).getString("source_text"))
    }

    @Test fun chunksCoverFiveThousandQuestionsAndAdditionalNonQuestionRows() {
        val source = (1..20000).joinToString("\n\n") { "记录 $it\n" + if (it <= 15000) "说明: 第 $it 行说明" else "题目: 第 $it 道题" }
        val chunks = AiBankImporter.chunks(source)
        assertTrue(source.length > 125000)
        assertEquals(20000, chunks.sumOf { it.length() })
        assertTrue(chunks.all { it.length() in 1..16 })
        val ids = chunks.flatMap { chunk -> (0 until chunk.length()).map { chunk.getJSONObject(it).getInt("source_id") } }
        assertEquals((1..20000).toList(), ids)
        assertTrue(runCatching { AiBankImporter.chunks(source + "\n\n记录 20001\n备注: 超限") }.isFailure)
        assertTrue(runCatching { AiBankImporter.chunks("甲".repeat(2 * 1024 * 1024 + 1)) }.isFailure)
    }

    @Test fun previewProcessesLaterSheetsAfterAnEntireInstructionsBatch() = runBlocking {
        val source = (1..18).joinToString("\n\n") { "记录 $it\n工作表: 使用说明\n行号: $it\n备注: 第 $it 条说明" } +
            "\n\n记录 19\n工作表: 题库\n行号: 2\n题目: 后续工作表第一题\n答案: 原答案" +
            "\n\n记录 20\n工作表: 题库\n行号: 3\n题目: 无答案题目"
        var calls = 0
        val progress = mutableListOf<Pair<Int, Int>>()
        val preview = AiBankImporter.preview(AiConfig(apiKey = "test-only"), source, "全工作簿.xlsx", request = { _, body ->
            calls++
            val material = JSONObject(JSONObject(String(body, Charsets.UTF_8)).getJSONArray("messages").getJSONObject(1).getString("content"))
            if (material.has("questions")) {
                val answers = JSONArray().put(JSONObject().put("question_id", 2).put("answer", "模型补全答案"))
                return@preview JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                    .put("message", JSONObject().put("content", JSONObject().put("answers", answers).toString()))))
            }
            val input = requestedRecords(body)
            val questions = JSONArray()
            val ignored = JSONArray()
            for (index in 0 until input.length()) {
                val record = input.getJSONObject(index)
                if (record.getInt("source_id") <= 18) ignored.put(skipped(record.getInt("source_id"))) else questions.put(simpleItem(record))
            }
            response(questions, skipped = ignored)
        }, progress = { current, total -> progress.add(current to total) })
        assertEquals(3, calls)
        assertEquals(2, preview.getInt("total"))
        assertEquals(20, preview.getInt("source_record_total"))
        assertEquals(18, preview.getJSONArray("skipped_sources").length())
        assertTrue(preview.getJSONArray("skipped_sources").getJSONObject(0).getString("source_text").contains("工作表: 使用说明"))
        assertEquals("后续工作表第一题", preview.getJSONArray("questions").getJSONObject(0).getString("stem"))
        assertEquals("模型补全答案", preview.getJSONArray("questions").getJSONObject(1).getString("reference_answer"))
        assertEquals("model", preview.getJSONArray("questions").getJSONObject(1).getString("answer_origin"))
        assertTrue(preview.getJSONArray("warnings").toString().contains("已跳过 18"))
        assertEquals(listOf(1 to 2, 2 to 2, 0 to 1, 1 to 1), progress)
    }

    @Test fun previewSplitsTruncatedBatchesWithoutDroppingOrReorderingRecords() = runBlocking {
        val source = (1..4).joinToString("\n\n") { "记录 $it\n题目: 第 $it 题\n答案: 答案 $it" }
        val sizes = mutableListOf<Int>()
        val preview = AiBankImporter.preview(AiConfig(apiKey = "test-only"), source, "题库.csv", request = { _, body ->
            val input = requestedRecords(body)
            sizes.add(input.length())
            if (input.length() > 1) response(JSONArray(), "length") else response(simpleItem(input.getJSONObject(0)))
        })
        assertEquals(listOf(4, 2, 1, 1, 2, 1, 1), sizes)
        assertEquals(4, preview.getInt("total"))
        assertEquals((1..4).map { "第 $it 题" }, (0..3).map { preview.getJSONArray("questions").getJSONObject(it).getString("stem") })
    }

    @Test fun previewRetriesOmissionsButFailsOnUnresolvedQuestionsAndNetworkErrors() = runBlocking {
        val source = "记录 1\n题目: 题目一\n答案: 甲\n\n记录 2\n题目: 题目二\n答案: 乙"
        var calls = 0
        val repaired = AiBankImporter.preview(AiConfig(apiKey = "test-only"), source, "题库.csv", request = { _, body ->
            calls++
            response(simpleItem(requestedRecords(body).getJSONObject(0)))
        })
        assertEquals(3, calls)
        assertEquals(2, repaired.getInt("total"))

        calls = 0
        val unresolved = runCatching { AiBankImporter.preview(AiConfig(apiKey = "test-only"), source, "题库.csv", request = { _, _ ->
            calls++
            response(JSONArray())
        }) }.exceptionOrNull()
        assertTrue(unresolved?.message.orEmpty().contains("遗漏"))
        assertEquals(2, calls)

        calls = 0
        val network = runCatching { AiBankImporter.preview(AiConfig(apiKey = "test-only"), source, "题库.csv", request = { _, _ ->
            calls++
            throw ApiException("AI Key 无效或没有权限。")
        }) }.exceptionOrNull()
        assertEquals("AI Key 无效或没有权限。", network?.message)
        assertEquals(1, calls)
    }

    @Test fun previewSupportsTheWholeFiveThousandQuestionBank() = runBlocking {
        val source = (1..5000).joinToString("\n\n") { "记录 $it\n题目: 第 $it 题\n答案: 原答案 $it" }
        var recordsSeen = 0
        val preview = AiBankImporter.preview(AiConfig(apiKey = "test-only"), source, "完整题库.csv", request = { _, body ->
            val input = requestedRecords(body)
            val questions = JSONArray()
            for (index in 0 until input.length()) questions.put(simpleItem(input.getJSONObject(index)))
            recordsSeen += input.length()
            response(questions)
        })
        assertEquals(5000, recordsSeen)
        assertEquals(5000, preview.getInt("total"))
        assertEquals("第 5000 题", preview.getJSONArray("questions").getJSONObject(4999).getString("stem"))
    }

    @Test fun normalizationPromptUsesExistingProviderConfigurationWithoutSolving() {
        val body = AiBankImporter.buildRequest(AiConfig(provider = "deepseek", model = "deepseek-flash"), records())
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertEquals(8192, body.getInt("max_tokens"))
        val messages = body.getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).getString("content").contains("不求解"))
        assertTrue(messages.getJSONObject(1).getString("content").contains("source_id"))
    }
}
