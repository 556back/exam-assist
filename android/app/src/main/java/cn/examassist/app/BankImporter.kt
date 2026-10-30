package cn.examassist.app

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipInputStream

/** Local format parsing; every importer produces the same validated bank schema. */
object BankImporter {
    const val MAX_FILE = 10 * 1024 * 1024
    const val MAX_QUESTIONS = 5000
    private const val MAX_TABLE_ROWS = 20000
    private data class TableRow(val number: Int, val cells: Map<Int, Any?>)
    private data class Table(val name: String, val rows: List<TableRow>)
    private val stemKeys = setOf("stem", "question", "question_text", "content", "title", "题干", "题目", "问题", "题目内容", "试题", "试题内容", "题干内容", "试题题干", "问题描述").map(::normalize).toSet()
    private val answerKeys = setOf("reference_answer", "answer", "correct_answer", "correctAnswer", "solution", "答案", "参考答案", "正确答案", "标准答案", "答案要点", "填空答案")
    private val correctOptionKeys = setOf("是否正确项", "是否正确答案", "是否正确", "正确项", "is_correct", "is_correct_answer", "correct").map(::normalize).toSet()

    fun preview(bytes: ByteArray, filename: String): JSONObject {
        require(bytes.size <= MAX_FILE) { "题库文件最多 10 MiB。" }
        val file = filename.substringAfterLast('/').substringAfterLast('\\')
        val tableWarnings = mutableListOf<String>()
        val unparsed = mutableListOf<String>()
        val rows = when (file.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "csv", "tsv" -> tableRows(csvTable(decodeCsv(bytes), file), tableWarnings, unparsed)
            "json" -> jsonRows(bytes)
            "xlsx" -> xlsxTables(bytes).flatMap { tableRows(it, tableWarnings, unparsed) }
            "txt", "md", "markdown", "docx", "html", "htm" -> return previewText(extractText(bytes, file), file)
            else -> throw IllegalArgumentException("支持 CSV、TSV、JSON、XLSX、TXT、Markdown、DOCX 和 HTML；图片/PDF 可先识别文字。")
        }
        return previewRows(rows, file).apply {
            tableWarnings.forEach { getJSONArray("warnings").put(it) }
            if (unparsed.isNotEmpty()) put("requires_ai_normalization", true)
        }
    }

    /** Unescaped source fields for model normalization; never calculate formulas or answers. */
    fun extractText(bytes: ByteArray, filename: String): String {
        require(bytes.size <= MAX_FILE) { "题库文件最多 10 MiB。" }
        return when (filename.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "json" -> sourceRows(jsonRows(bytes))
            "xlsx" -> tableSource(xlsxTables(bytes))
            "csv", "tsv" -> tableSource(listOf(csvTable(decodeCsv(bytes), filename)))
            "docx" -> docxText(bytes)
            "html", "htm" -> htmlText(decodeCsv(bytes))
            "txt", "md", "markdown" -> decodeCsv(bytes)
            else -> throw IllegalArgumentException("此文件需要先提取文字，再解析题库。")
        }
    }

    private fun sourceRows(rows: List<Map<String, Any?>>): String = rows.mapIndexed { index, row ->
        "记录 ${index + 1}\n" + sourceFields(row)
    }.joinToString("\n\n")

    private fun sourceFields(row: Map<String, Any?>): String = row.entries.joinToString("\n") { (key, value) ->
            val label = headerLabel(key)
            if (headerKey(key) in setOf("options", "选项")) "$label:\n${sourceOptions(value)}" else "$label: ${text(value)}"
    }

    private fun sourceOptions(value: Any?): String {
        val options = if (value is String) runCatching { JSONTokener(value).nextValue() }.getOrNull() ?: value else value
        return when (options) {
            is JSONObject -> options.keys().asSequence().joinToString("\n") { "$it. ${text(options.opt(it))}" }
            is JSONArray -> (0 until options.length()).joinToString("\n") { index ->
                when (val option = options.opt(index)) {
                    is JSONObject -> "${text(option.opt("label") ?: option.opt("key") ?: option.opt("id")).ifBlank { ('A' + index).toString() }}. ${text(option.opt("text") ?: option.opt("content") ?: option.opt("value"))}"
                    is String -> if (optionLabel.matches(option.trim())) option else "${'A' + index}. $option"
                    else -> text(option)
                }
            }
            else -> text(value)
        }
    }

    fun previewText(value: String, filename: String): JSONObject {
        require(value.length <= 2 * 1024 * 1024) { "题库文字最多 200 万字符，请拆分文件。" }
        return previewRows(textRows(value), filename).apply {
            getJSONArray("warnings").put("文本按题号、选项和答案标记解析，请核对题目边界与参考答案。")
        }
    }

    private fun previewRows(rows: List<Map<String, Any?>>, file: String): JSONObject {
        val questions = JSONArray()
        val warnings = JSONArray()
        var requiresAi = false
        rows.forEachIndexed { index, row ->
            try {
                val mapped = row.entries.associate { headerKey(it.key) to it.value }
                fun pick(vararg keys: String): Any? = keys.asSequence().map { mapped[headerKey(it)] }.firstOrNull { text(it).isNotBlank() }
                val stem = text(pick("stem", "question", "question_text", "content", "title", "题干", "题目", "问题", "题目内容", "试题", "试题内容", "题干内容", "试题题干", "问题描述"))
                if (stem.isBlank()) {
                    warnings.put("第 ${index + 1} 条缺少题干，未导入。")
                    if (mapped.any { (it.key in answerKeys.map(::normalize) || it.key in setOf("options", "选项")) && text(it.value).isNotBlank() }) requiresAi = true
                    return@forEachIndexed
                }
                var rawOptions = pick("options", "选项")
                if (rawOptions is String) {
                    val encodedOptions = rawOptions
                    rawOptions = runCatching { JSONTokener(encodedOptions).nextValue() }.getOrNull()
                    if (rawOptions !is JSONObject && rawOptions !is JSONArray) rawOptions = optionText(encodedOptions)
                }
                if (rawOptions is JSONObject && rawOptions.length() == 0 || rawOptions is JSONArray && rawOptions.length() == 0) rawOptions = null
                val options = when (val optionValue = rawOptions) {
                    is JSONObject -> JSONArray().apply { optionValue.keys().forEach { label -> put(JSONObject().put("label", label).put("text", text(optionValue.opt(label)))) } }
                    is JSONArray -> JSONArray().apply {
                        for (n in 0 until optionValue.length()) {
                            val option = optionValue.opt(n)
                            put(when (option) {
                                is String -> optionLabel.matchEntire(option.trim())?.let { JSONObject().put("label", it.groupValues[1].uppercase(Locale.ROOT)).put("text", it.groupValues[2]) }
                                    ?: JSONObject().put("label", ('A' + n).toString()).put("text", option)
                                is JSONObject -> JSONObject().put("label", text(option.opt("label") ?: option.opt("key") ?: option.opt("id")).ifBlank { ('A' + n).toString() })
                                    .put("text", text(option.opt("text") ?: option.opt("content") ?: option.opt("value")))
                                else -> throw IllegalArgumentException("第 ${index + 1} 条选项格式无效。")
                            })
                        }
                    }
                    null, JSONObject.NULL -> JSONArray().apply {
                        for (label in 'A'..'Z') {
                            val value = text(pick(label.toString(), "选项$label", "option_$label"))
                            if (value.isNotBlank()) put(JSONObject().put("label", label.toString()).put("text", value))
                        }
                    }
                    else -> throw IllegalArgumentException("第 ${index + 1} 条选项格式无效。")
                }
                val rawAnswer = pick(*answerKeys.toTypedArray())
                val answers = answerKeys.map(::normalize)
                require(mapped.filterKeys { it in answers }.values.map(::text).filter { it.isNotBlank() }.distinct().size <= 1) {
                    "第 ${index + 1} 条的多个答案字段互相矛盾，请先核对原文。"
                }
                val answer = text(rawAnswer)
                if (rawAnswer != null && rawAnswer !== JSONObject.NULL && rawAnswer !is String) warnings.put("第 ${index + 1} 条结构化或数值答案已保留，需要核对。")
                if (answer.startsWith("=")) warnings.put("第 ${index + 1} 条答案可能为公式，保留原文但未计算。")
                if (answer.isBlank()) warnings.put("第 ${index + 1} 条没有参考答案，将仅作为题目材料。")
                if (options.length() == 1) warnings.put("第 ${index + 1} 条只有一个选项，可能不完整，请核对原文。")
                val answerLabels = Normalizer.normalize(answer, Normalizer.Form.NFKC).uppercase(Locale.ROOT)
                if (options.length() > 0 && Regex("^[A-Z]+(?:[ ,，、/&+和与][A-Z]+)*$").matches(answerLabels)) {
                    val labels = (0 until options.length()).map { Normalizer.normalize(options.getJSONObject(it).optString("label"), Normalizer.Form.NFKC).uppercase(Locale.ROOT) }.toSet()
                    if (answerLabels.filter { it in 'A'..'Z' }.any { it.toString() !in labels }) warnings.put("第 ${index + 1} 条参考答案包含不存在的选项标签，原答案已保留，请核对。")
                }
                val question = JSONObject().put("stem", stem).put("options", options).put("reference_answer", answer)
                    .put("explanation", text(pick("explanation", "analysis", "解析", "答案解析", "试题解析", "题目解析")))
                    .put("source", text(pick("source", "出处", "来源")).ifBlank { file })
                val metadata = mapOf(
                    "question_type" to arrayOf("question_type", "题型", "类型"),
                    "answer_origin" to arrayOf("answer_origin", "答案来源"),
                    "answer_evidence" to arrayOf("answer_evidence", "答案依据"),
                    "source_text" to arrayOf("source_text", "原文"),
                    "question_source_text" to arrayOf("question_source_text"),
                    "original_reference_answer" to arrayOf("original_reference_answer"), "original_stem" to arrayOf("original_stem"),
                    "answer_model" to arrayOf("answer_model"), "answer_provider" to arrayOf("answer_provider"),
                    "source_file" to arrayOf("source_file"),
                    "source_sheet" to arrayOf("source_sheet", "工作表"), "original_id" to arrayOf("original_id", "题目编号", "编号", "序号", "id")
                )
                metadata.forEach { (key, aliases) -> pick(*aliases)?.let { question.put(key, text(it)) } }
                pick("source_row", "行号")?.let { value ->
                    question.put("source_row", text(value).toIntOrNull() ?: text(value))
                }
                pick("schema_version")?.let { value -> question.put("schema_version", text(value).toIntOrNull() ?: text(value)) }
                validateQuestion(question, index + 1)
                questions.put(question)
            } catch (error: IllegalArgumentException) {
                requiresAi = true
                warnings.put("第 ${index + 1} 条未导入：${error.message} 请核对源文件。")
            }
        }
        require(questions.length() <= MAX_QUESTIONS) { "单个题库最多 5000 条题目。" }
        require(questions.length() > 0) { "没有可导入的题目，请检查题干列；无标准表头的表格可开启大模型整理。${warnings.optString(0).take(400)}" }
        val name = file.substringBeforeLast('.', file).ifBlank { "未命名题库" }.take(100)
        return JSONObject().put("name", name).put("questions", questions).put("warnings", warnings).put("total", questions.length())
            .put("source_file", file).put("import_method", "local").put("requires_ai_normalization", requiresAi)
    }

    internal fun validateQuestion(question: JSONObject, row: Int) {
        fun string(key: String, max: Int, nonEmpty: Boolean = false) {
            val value = question.opt(key)
            require(value is String && value.length <= max && (!nonEmpty || value.isNotBlank())) { "第 $row 条字段格式或长度无效。" }
        }
        string("stem", 20000, true); string("reference_answer", 4000); string("explanation", 12000); string("source", 1000)
        if (question.has("source_text")) string("source_text", 40000)
        if (question.has("answer_evidence")) string("answer_evidence", 12000)
        if (question.has("original_reference_answer")) string("original_reference_answer", 4000)
        if (question.has("original_stem")) string("original_stem", 20000)
        val options = question.optJSONArray("options") ?: throw IllegalArgumentException("第 $row 条选项格式无效。")
        require(options.length() <= 26) { "第 $row 条最多 26 个选项。" }
        val labels = mutableSetOf<String>()
        for (index in 0 until options.length()) {
            val option = options.optJSONObject(index) ?: throw IllegalArgumentException("第 $row 条选项格式无效。")
            require(option.opt("label") is String && option.getString("label").isNotBlank() && option.getString("label").length <= 30 &&
                option.opt("text") is String && option.getString("text").isNotBlank() && option.getString("text").length <= 4000 &&
                labels.add(normalize(option.getString("label")))) { "第 $row 条选项为空、重复或格式/长度无效。" }
        }
    }

    private fun jsonRows(bytes: ByteArray): List<Map<String, Any?>> {
        val value = try {
            val parser = JSONTokener(decode(bytes, Charsets.UTF_8).removePrefix("\uFEFF"))
            parser.nextValue().also { require(parser.nextClean() == '\u0000') }
        }
        catch (_: Exception) { throw IllegalArgumentException("JSON 文件内容无效。") }
        val array = if (value is JSONObject) {
            sequenceOf("questions", "items", "data", "题目", "题库", "试题").mapNotNull { value.optJSONArray(it) }.firstOrNull()
                ?: value.optJSONObject("data")?.optJSONArray("questions")
                ?: if (value.keys().asSequence().any { normalize(it) in setOf("stem", "question", "题目", "题干") }) JSONArray().put(value) else null
        } else value as? JSONArray
        require(array != null) { "JSON 顶层应为题目数组或包含 questions 的对象。" }
        require(array.length() <= MAX_QUESTIONS) { "单个题库最多 5000 条题目。" }
        return (0 until array.length()).map { index ->
            val row = array.optJSONObject(index) ?: throw IllegalArgumentException("第 ${index + 1} 条不是题目对象。")
            row.keys().asSequence().associateWith { row.opt(it) }
        }
    }

    private fun decode(bytes: ByteArray, charset: Charset): String = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()

    internal fun decodeCsv(bytes: ByteArray): String = try {
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        decode(bytes, charset).removePrefix("\uFEFF")
    }
    catch (_: CharacterCodingException) {
        try { decode(bytes, Charset.forName("GB18030")) } catch (_: CharacterCodingException) { throw IllegalArgumentException("CSV 需要 UTF-8 或 GB18030 编码。") }
    }

    internal fun csvRows(csv: String): List<Map<String, Any?>> = tableRows(csvTable(csv, "CSV"), mutableListOf()).also {
        require(it.size <= MAX_QUESTIONS) { "单个题库最多 5000 条题目。" }
    }

    private fun csvTable(csv: String, name: String): Table {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.take(MAX_TABLE_ROWS).toList()
        val separators = listOf(',', '\t', ';', '|')
        val headerDelimiter = separators.mapNotNull { separator ->
            val line = lines.indexOfFirst { line ->
                separator in line && isHeader(line.split(separator).withIndex().associate { it.index to it.value.trim().removeSurrounding("\"") })
            }
            if (line >= 0) separator to line else null
        }.minByOrNull { it.second }?.first
        val declaredDelimiter = Regex("(?i)^sep=([,;\\t|])$").matchEntire(lines.firstOrNull().orEmpty().trim())?.groupValues?.get(1)?.single()
        val delimiter = declaredDelimiter ?: if (name.endsWith(".tsv", true)) '\t' else headerDelimiter ?: separators.maxBy { separator ->
            var inQuotes = false
            lines.take(20).sumOf { line -> line.count { char -> if (char == '"') inQuotes = !inQuotes; char == separator && !inQuotes } }
        }
        val rows = mutableListOf<TableRow>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var afterQuote = false
        var index = 0
        var rowNumber = 0
        fun cellDone() { row.add(cell.toString()); cell.setLength(0); afterQuote = false }
        fun rowDone() {
            cellDone()
            rowNumber++
            if (row.any { it.isNotBlank() }) {
                require(rows.size < MAX_TABLE_ROWS) { "表格非空行最多 20000 行，请拆分文件。" }
                rows.add(TableRow(rowNumber, row.withIndex().associate { it.index to it.value }))
            }
            row.clear()
        }
        while (index < csv.length) {
            val char = csv[index]
            when {
                quoted && char == '"' -> {
                    if (index + 1 < csv.length && csv[index + 1] == '"') { cell.append('"'); index++ }
                    else { quoted = false; afterQuote = true }
                }
                quoted -> cell.append(char)
                char == delimiter -> cellDone()
                char == '\r' || char == '\n' -> { rowDone(); if (char == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') index++ }
                char == '"' && cell.isEmpty() && !afterQuote -> quoted = true
                afterQuote && char.isWhitespace() -> Unit
                afterQuote || char == '"' -> throw IllegalArgumentException("CSV 引号格式无效。")
                else -> cell.append(char)
            }
            index++
        }
        require(!quoted) { "CSV 引号没有闭合。" }
        if (cell.isNotEmpty() || row.isNotEmpty() || afterQuote) rowDone()
        return Table(name, rows)
    }

    private fun headerLabel(value: String): String {
        val label = value.replace(Regex("[（(\\[]\\s*(?:必填|选填|可选|required|optional)\\s*[）)\\]]", RegexOption.IGNORE_CASE), "").trim()
        val option = Regex("^(?:([a-z])选项|选项([a-z]))(?:内容|文本)?$").matchEntire(normalize(label))
        return option?.let { "选项${it.groupValues.drop(1).first { letter -> letter.isNotBlank() }.uppercase(Locale.ROOT)}" } ?: label
    }
    private fun headerKey(value: String): String {
        val label = headerLabel(value)
        // Keep the aggregate options field distinct from the individual option_S column.
        val option = Regex("(?i)^option[_\\s-]+([a-z])$").matchEntire(label)
        return option?.let { "option_${it.groupValues[1].lowercase(Locale.ROOT)}" } ?: normalize(label)
    }

    private fun knownHeader(value: String): Boolean {
        val key = headerKey(value)
        return key in stemKeys || key in answerKeys.map(::normalize) || key in correctOptionKeys || key in setOf("options", "选项", "explanation", "analysis", "解析", "答案解析", "试题解析", "题目解析", "source", "出处", "来源", "编号", "序号", "id", "题型", "类型", "难度", "分值", "备注", "考点", "答案来源") ||
            Regex("(?:[a-z]|选项[a-z]|option_?[a-z])").matches(key)
    }

    private fun isHeader(cells: Map<Int, Any?>): Boolean {
        val values = cells.values.map(::text).filter { it.isNotBlank() }
        return values.any { headerKey(it) in stemKeys } &&
            (values.size == 1 || values.count { knownHeader(it) && headerKey(it).length > 1 } >= 2 ||
                values.count { Regex("^[a-z]$").matches(headerKey(it)) } >= 2) && values.all { it.length <= 80 }
    }

    private fun tableRows(table: Table, warnings: MutableList<String>, unparsed: MutableList<String> = mutableListOf()): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        var header: Map<Int, String>? = null
        var skipped = 0
        var pending: MutableMap<String, Any?>? = null
        var options = JSONArray()
        val correct = StringBuilder()
        val explicitAnswers = linkedSetOf<String>()
        val evidence = StringBuilder()
        val material = StringBuilder()
        fun skippedQuestion(row: TableRow) {
            if (isInstructionSheet(table.name)) return
            val fields = row.cells.entries.filter { text(it.value).isNotBlank() }
            val labels = header
            val explicit = fields.any { questionStart.matches(text(it.value)) || optionLabel.matches(text(it.value)) || answerStart.matches(text(it.value)) }
            val verticalStem = fields.size >= 2 && headerKey(text(fields.first().value)) in stemKeys && text(fields[1].value).isNotBlank()
            val labeledMaterial = labels != null && fields.any {
                val key = headerKey(labels[it.key].orEmpty())
                key in answerKeys.map(::normalize) || key in setOf("options", "选项") || Regex("(?:[a-z]|选项[a-z]|option_[a-z])").matches(key)
            }
            if (explicit || verticalStem || labeledMaterial) unparsed.add("${table.name} · 第 ${row.number} 行")
        }
        fun addMetadata(mapped: MutableMap<String, Any?>, number: Int) {
            fun has(vararg keys: String) = mapped.any { headerKey(it.key) in keys.map(::headerKey) && text(it.value).isNotBlank() }
            if (!has("source_sheet", "工作表")) mapped["source_sheet"] = table.name
            if (!has("source_row", "行号")) mapped["source_row"] = number
            if (!has("question_type", "题型", "类型")) sheetQuestionType(table.name)?.let { mapped["question_type"] = it }
            if (!has("source", "出处", "来源")) mapped["source"] = "${table.name} · 第 $number 行"
        }
        fun finishOptions() {
            pending?.let { row ->
                row["options"] = options
                val original = explicitAnswers.firstOrNull()
                row["reference_answer"] = original ?: correct.toString()
                if (explicitAnswers.size > 1) row["correct_answer"] = explicitAnswers.last()
                else if (original != null && correct.isNotEmpty() && original != correct.toString()) row["correct_answer"] = correct.toString()
                if (row.none { headerKey(it.key) in setOf("answerevidence", "答案依据") && text(it.value).isNotBlank() }) row["answer_evidence"] = evidence.toString()
                if (row.none { headerKey(it.key) in setOf("sourcetext", "原文") && text(it.value).isNotBlank() }) row["source_text"] = material.toString()
                rows.add(row)
            }
            pending = null; options = JSONArray(); correct.setLength(0); explicitAnswers.clear(); evidence.setLength(0); material.setLength(0)
        }
        for (row in table.rows) {
            if (isHeader(row.cells)) {
                finishOptions()
                val labels = row.cells.mapValues { text(it.value) }
                val normalized = labels.values.filter { it.isNotBlank() }.map(::headerKey)
                if (normalized.distinct().size != normalized.size) {
                    warnings.add("${table.name} 第 ${row.number} 行表头有重复列，已跳过这段表格，请核对或使用大模型整理。")
                    header = null; skipped++; continue
                }
                header = labels
                continue
            }
            val labels = header
            if (labels == null) { skipped++; skippedQuestion(row); continue }
            val mapped = row.cells.entries.associate { (column, value) ->
                labels[column]?.takeIf { it.isNotBlank() }.orEmpty().ifBlank { "列${columnName(column)}" } to value
            }.toMutableMap()
            val originalFields = mapped.toMap()
            val hasStem = mapped.any { headerKey(it.key) in stemKeys && text(it.value).isNotBlank() }
            if (isPerOptionHeader(labels)) {
                if (hasStem) {
                    finishOptions()
                    addMetadata(mapped, row.number)
                    mapped["_table_row"] = row.number
                    pending = mapped
                }
                if (pending == null) { skipped++; skippedQuestion(row); continue }
                mapped.entries.filter { headerKey(it.key) in answerKeys.map(::normalize) }.map { text(it.value) }.filter { it.isNotBlank() }.forEach(explicitAnswers::add)
                if (material.isNotEmpty()) material.append('\n')
                material.append("第 ${row.number} 行\n").append(originalFields.entries.filter { text(it.value).isNotBlank() }.joinToString("\n") { "${it.key}: ${text(it.value)}" })
                val choice = text(mapped.entries.firstOrNull { headerKey(it.key) in setOf("options", "选项") }?.value)
                val marker = mapped.entries.firstOrNull { headerKey(it.key) in correctOptionKeys }
                val markedCorrect = normalize(text(marker?.value)) in setOf("y", "yes", "true", "1", "是", "正确", "对") || text(marker?.value) in setOf("√", "✓")
                if (choice.isNotBlank()) {
                    val label = ('A' + options.length()).toString()
                    options.put(JSONObject().put("label", label).put("text", choice))
                    if (markedCorrect) {
                        correct.append(label)
                        if (evidence.isNotEmpty()) evidence.append('\n')
                        evidence.append("第 ${row.number} 行 ${marker?.key}: ${text(marker?.value)} → $label. $choice")
                    }
                } else if (markedCorrect) warnings.add("${table.name} 第 ${row.number} 行标为正确项但选项为空，无法确定答案，请核对。")
                continue
            }
            if (!hasStem) { skipped++; skippedQuestion(row); continue }
            addMetadata(mapped, row.number)
            rows.add(mapped)
        }
        finishOptions()
        when {
            table.rows.isEmpty() -> warnings.add("工作表 ${table.name} 为空，已跳过。")
            header == null -> warnings.add("${table.name} 未找到题干表头，已跳过；若含题目，请使用大模型整理。")
            skipped > 0 -> warnings.add("${table.name} 跳过 $skipped 行说明或空题干记录，请核对题目数量。")
        }
        return rows
    }

    private fun isPerOptionHeader(header: Map<Int, String>): Boolean = header.values.any { headerKey(it) in setOf("options", "选项") } &&
        header.values.any { headerKey(it) in correctOptionKeys }

    private fun isInstructionSheet(name: String): Boolean = normalize(name.substringBefore('（').substringBefore('(')) in
        setOf("说明", "导入规则", "导入说明", "使用说明", "填写说明", "题库说明", "模板说明", "readme", "instruction", "instructions", "指南")

    private fun sheetQuestionType(name: String): String? = when (normalize(name)) {
        "单选", "单选题" -> "单选题"
        "多选", "多选题" -> "多选题"
        "判断", "判断题" -> "判断题"
        "填空", "填空题" -> "填空题"
        "主观", "主观题", "简答", "简答题" -> "简答题"
        else -> null
    }

    /** Preserve every nonempty source row, including unfamiliar layouts, for AI classification. */
    private fun tableSource(tables: List<Table>): String {
        var index = 0
        val records = mutableListOf<String>()
        fun record(table: Table, number: Int, material: String) {
            if (material.isNotBlank()) records.add("记录 ${++index}\n工作表: ${table.name}\n行号: $number\n$material")
        }
        for (table in tables) {
            if (isInstructionSheet(table.name)) {
                for (row in table.rows) record(table, row.number, row.cells.entries.joinToString("\n") { (column, value) ->
                    text(value).lines().filter { it.isNotBlank() }.joinToString("\n") { "说明内容: 列${columnName(column)}: $it" }
                })
                continue
            }
            val optionRows = if (table.rows.any { isHeader(it.cells) && isPerOptionHeader(it.cells.mapValues { cell -> text(cell.value) }) }) {
                tableRows(table, mutableListOf()).filter { it["_table_row"] is Int }.associateBy { it["_table_row"] as Int }
            } else emptyMap()
            val singleColumnText = table.rows.all { row -> row.cells.values.count { text(it).isNotBlank() } <= 1 } &&
                table.rows.any { row -> row.cells.values.any { questionStart.matches(text(it)) || answerStart.matches(text(it)) || optionLabel.matches(text(it)) } }
            if (singleColumnText) {
                val material = StringBuilder()
                var firstRow = 0
                for (row in table.rows) {
                    val line = row.cells.values.joinToString("\n", transform = ::text).trim()
                    if (questionStart.matches(line) && material.isNotEmpty()) {
                        record(table, firstRow, material.toString().trim()); material.setLength(0)
                    }
                    if (material.isEmpty()) firstRow = row.number else material.append('\n')
                    material.append(line)
                }
                record(table, firstRow, material.toString().trim())
                continue
            }
            var header: Map<Int, String>? = null
            val vertical = StringBuilder()
            var firstRow = 0
            fun finishVertical() { record(table, firstRow, vertical.toString()); vertical.setLength(0) }
            val optionGroup = StringBuilder()
            var optionFirstRow = 0
            fun finishOptionGroup() {
                val row = optionRows[optionFirstRow]
                val canonical = if (row == null) optionGroup.toString() else {
                    val fields = linkedMapOf<String, Any?>()
                    row.entries.firstOrNull { headerKey(it.key) in setOf("questiontype", "题型", "类型") && text(it.value).isNotBlank() }?.let { fields["题型"] = it.value }
                    fields["题干"] = row.entries.firstOrNull { headerKey(it.key) in stemKeys }?.value
                    fields["选项"] = row["options"]
                    val answers = row.entries.filter { headerKey(it.key) in answerKeys.map(::normalize) }.map { text(it.value) }.filter { it.isNotBlank() }.distinct()
                    fields["参考答案"] = answers.firstOrNull().orEmpty()
                    row.entries.firstOrNull { headerKey(it.key) in setOf("explanation", "analysis", "解析", "答案解析", "试题解析", "题目解析") && text(it.value).isNotBlank() }?.let { fields["解析"] = it.value }
                    sourceFields(fields) + answers.drop(1).joinToString("", transform = { "\n正确答案: $it" })
                }
                record(table, optionFirstRow, canonical); optionGroup.setLength(0)
            }
            for (row in table.rows) {
                if (isHeader(row.cells)) {
                    finishVertical(); finishOptionGroup(); header = row.cells.mapValues { text(it.value) }; continue
                }
                val fields = row.cells.entries.filter { text(it.value).isNotBlank() }
                if (header?.let(::isPerOptionHeader) == true) {
                    val hasStem = fields.any { headerKey(header?.get(it.key).orEmpty()) in stemKeys }
                    if (hasStem) finishOptionGroup()
                    if (hasStem || optionGroup.isNotEmpty()) {
                        if (optionGroup.isEmpty()) optionFirstRow = row.number else optionGroup.append('\n')
                        optionGroup.append("第 ${row.number} 行\n").append(fields.joinToString("\n") { (column, value) ->
                            "${header?.get(column)?.let(::headerLabel) ?: "列${columnName(column)}"}: ${text(value)}"
                        })
                        continue
                    }
                }
                if (header == null && fields.size >= 2 && knownHeader(text(fields.first().value)) &&
                    (headerKey(text(fields.first().value)) in stemKeys || vertical.isNotEmpty())) {
                    val label = headerLabel(text(fields.first().value))
                    if (headerKey(label) in stemKeys) finishVertical()
                    if (vertical.isEmpty()) firstRow = row.number else vertical.append('\n')
                    vertical.append(label).append(": ").append(fields.drop(1).joinToString("\n") { text(it.value) })
                    continue
                }
                if (vertical.isNotEmpty() && header == null && fields.size == 1) {
                    vertical.append('\n').append(text(fields.first().value)); continue
                }
                finishVertical()
                val context = header?.entries?.joinToString("; ") { (column, label) -> "列${columnName(column)}=$label" }?.let { "表头: $it\n" }.orEmpty()
                val type = if (header != null && header?.values?.none { headerKey(it) in setOf("questiontype", "题型", "类型") } == true) {
                    sheetQuestionType(table.name)?.let { "题型: $it\n" }.orEmpty()
                } else ""
                record(table, row.number, context + type + row.cells.entries.joinToString("\n") { (column, value) ->
                    val label = header?.get(column)?.takeIf { it.isNotBlank() }?.let(::headerLabel) ?: "列${columnName(column)}"
                    if (headerKey(label) in setOf("options", "选项")) "$label:\n${sourceOptions(value)}" else "$label: ${text(value)}"
                })
            }
            finishVertical()
            finishOptionGroup()
        }
        return records.joinToString("\n\n")
    }

    private fun columnName(column: Int): String {
        var number = column + 1
        var name = ""
        while (number > 0) { number--; name = ('A' + number % 26) + name; number /= 26 }
        return name
    }

    private val optionLabel = Regex("^([A-Za-zＡ-Ｚａ-ｚ])[.、．:：)）]\\s*(.+)$")
    private val questionStart = Regex("^\\s*(?:第\\s*)?(\\d{1,6})(?:\\s*题\\s*[:：.]?|[.．](?!\\d)|[、)）])\\s*(.*)$")
    private val answerStart = Regex("^\\s*[【\\[]?(?:参考答案|正确答案|标准答案|答案要点|答案|reference_answer|correct_answer|answer)[】\\]]?\\s*[:：]?[ \\t]*(.*)$", RegexOption.IGNORE_CASE)
    private val explanationStart = Regex("^\\s*[【\\[]?(?:答案解析|试题解析|解析|解答|explanation|analysis)[】\\]]?\\s*[:：]?[ \\t]*(.*)$", RegexOption.IGNORE_CASE)

    private fun optionText(value: String): JSONArray {
        val options = JSONArray()
        val boundaries = Regex("(?:^|[\\n\\r;；|]|\\s+)([A-Za-zＡ-Ｚａ-ｚ])[.、．:：)）]\\s*").findAll(value).toList()
        require(boundaries.isNotEmpty() && value.substring(0, boundaries.first().range.first).isBlank()) { "选项文字需要 A. / B. 等明确标记，请检查或使用 AI 整理。" }
        boundaries.forEachIndexed { index, match ->
            val end = boundaries.getOrNull(index + 1)?.range?.first ?: value.length
            options.put(JSONObject().put("label", Normalizer.normalize(match.groupValues[1], Normalizer.Form.NFKC).uppercase(Locale.ROOT))
                .put("text", value.substring(match.range.last + 1, end).trim()))
        }
        return options
    }

    private fun textRows(value: String): List<Map<String, Any?>> {
        val lines = value.replace("\r\n", "\n").replace('\r', '\n').lines()
        val numbered = lines.any { questionStart.matches(it) }
        val rows = mutableListOf<Map<String, Any?>>()
        var stem = StringBuilder()
        var answer = StringBuilder()
        var explanation = StringBuilder()
        var source = StringBuilder()
        var options = JSONArray()
        var active = !numbered
        var section = "stem"
        fun append(target: StringBuilder, text: String) { if (target.isNotEmpty()) target.append('\n'); target.append(text) }
        fun finish() {
            if (!active || stem.isBlank()) return
            require(rows.size < MAX_QUESTIONS) { "单个题库最多 5000 条题目。" }
            rows.add(mapOf("stem" to stem.toString().trim(), "options" to options, "reference_answer" to answer.toString().trim(),
                "explanation" to explanation.toString().trim(), "source_text" to source.toString().trim()))
            stem = StringBuilder(); answer = StringBuilder(); explanation = StringBuilder(); source = StringBuilder(); options = JSONArray(); section = "stem"
        }
        for (line in lines) {
            val start = questionStart.matchEntire(line)
            if (start != null) {
                finish(); active = true
                append(stem, start.groupValues[2]); append(source, line)
                continue
            }
            if (!active || line.isBlank()) continue
            val ans = answerStart.matchEntire(line)
            val expl = explanationStart.matchEntire(line)
            val option = optionLabel.matchEntire(line.trim())
            append(source, line)
            when {
                ans != null -> {
                    require(answer.isEmpty()) { "同一道题出现多个答案标记，或缺少题号，无法确定边界；请核对或使用 AI 整理。" }
                    append(answer, ans.groupValues[1]); section = "answer"
                }
                expl != null -> { append(explanation, expl.groupValues[1]); section = "explanation" }
                option != null && section == "stem" -> {
                    val parsed = optionText(line.trim())
                    for (index in 0 until parsed.length()) options.put(parsed.getJSONObject(index))
                    section = "options"
                }
                option != null && section == "options" -> {
                    val parsed = optionText(line.trim())
                    for (index in 0 until parsed.length()) options.put(parsed.getJSONObject(index))
                }
                section == "answer" -> append(answer, line)
                section == "explanation" -> append(explanation, line)
                section == "options" -> {
                    val last = options.getJSONObject(options.length() - 1)
                    last.put("text", last.getString("text") + "\n" + line)
                }
                else -> append(stem, line)
            }
        }
        finish()
        require(rows.isNotEmpty()) { "没有识别出题目，请使用明确题号/答案标记，或启用 AI 整理。" }
        return rows
    }

    private fun docxText(bytes: ByteArray): String {
        var document: ByteArray? = null
        var total = 0
        var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val buffer = ByteArray(8192)
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 1000) { "DOCX 压缩包内容过多。" }
                val output = if (entry.name == "word/document.xml") ByteArrayOutputStream() else null
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= 40 * 1024 * 1024) { "DOCX 解压内容最多 40 MiB。" }
                    output?.write(buffer, 0, n)
                }
                if (output != null) document = output.toByteArray()
                zip.closeEntry()
            }
        }
        val result = StringBuilder()
        var reading = false
        parseXml(document ?: throw IllegalArgumentException("DOCX 缺少文档正文。")) { parser -> when {
            parser.eventType == XmlPullParser.START_TAG && parser.name in listOf("drawing", "pict", "object", "oMath", "oMathPara") ->
                throw IllegalArgumentException("DOCX 包含图片、对象或公式；请转为 PDF/图片并核对识别结果，避免丢失题目条件。")
            parser.eventType == XmlPullParser.START_TAG && parser.name == "t" -> reading = true
            parser.isText() && reading -> result.append(parser.text)
            parser.eventType == XmlPullParser.END_TAG && parser.name == "t" -> reading = false
            parser.eventType == XmlPullParser.START_TAG && parser.name in listOf("tab", "br", "cr") -> result.append('\n')
            parser.eventType == XmlPullParser.END_TAG && parser.name in listOf("p", "tc") -> result.append('\n')
        } }
        require(result.isNotBlank()) { "DOCX 没有文字；扫描图片需要先进行 OCR。" }
        return result.toString()
    }

    private fun htmlText(value: String): String {
        require(!Regex("(?i)<(?:img|svg|math|canvas)\\b").containsMatchIn(value)) { "HTML 含图片或公式，请先转为 PDF/图片并核对识别结果。" }
        return value.replace(Regex("(?is)<(?:script|style)\\b[^>]*>.*?</(?:script|style)>"), "")
            .replace(Regex("(?i)<(?:br\\b[^>]*|/(?:p|div|li|tr|h[1-6]))>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
    }

    private fun xlsxTables(bytes: ByteArray): List<Table> {
        try {
            val files = mutableMapOf<String, ByteArray>()
            var total = 0
            var entries = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 1000) { "XLSX 压缩包内容过多。" }
                    val wanted = entry.name == "xl/sharedStrings.xml" || entry.name == "xl/workbook.xml" ||
                        entry.name == "xl/_rels/workbook.xml.rels" || (entry.name.startsWith("xl/worksheets/") && entry.name.endsWith(".xml"))
                    val output = if (wanted) ByteArrayOutputStream() else null
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= 40 * 1024 * 1024) { "XLSX 解压内容最多 40 MiB。" }
                        output?.write(buffer, 0, n)
                    }
                    if (output != null) files[entry.name] = output.toByteArray()
                    zip.closeEntry()
                }
            }
            val workbook = files["xl/workbook.xml"] ?: throw IllegalArgumentException("XLSX 缺少工作簿。")
            val sheets = mutableListOf<Pair<String, String>>()
            parseXml(workbook) { parser ->
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                    val id = (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == "id" }?.let { parser.getAttributeValue(it) }.orEmpty()
                    sheets.add(parser.getAttributeValue(null, "name").orEmpty().ifBlank { "Sheet${sheets.size + 1}" } to id)
                }
            }
            val targets = mutableMapOf<String, String>()
            files["xl/_rels/workbook.xml.rels"]?.let { rels -> parseXml(rels) { parser ->
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship" && parser.getAttributeValue(null, "TargetMode") != "External") {
                    val target = parser.getAttributeValue(null, "Target").orEmpty()
                    val path = if (target.startsWith('/')) target.removePrefix("/") else "xl/$target"
                    val parts = mutableListOf<String>()
                    path.split('/').forEach { part -> when (part) { ".", "" -> Unit; ".." -> { require(parts.isNotEmpty()) { "XLSX 工作表路径无效。" }; parts.removeAt(parts.lastIndex) }; else -> parts.add(part) } }
                    targets[parser.getAttributeValue(null, "Id").orEmpty()] = parts.joinToString("/")
                }
            } }
            val shared = mutableListOf<String>()
            files["xl/sharedStrings.xml"]?.let { strings ->
                val value = StringBuilder()
                var reading = false
                var phonetic = false
                parseXml(strings) { parser -> when {
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "si" -> value.setLength(0)
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "rPh" -> phonetic = true
                    parser.eventType == XmlPullParser.END_TAG && parser.name == "rPh" -> phonetic = false
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "t" -> reading = !phonetic
                    parser.isText() && reading -> value.append(parser.text)
                    parser.eventType == XmlPullParser.END_TAG && parser.name == "t" -> reading = false
                    parser.eventType == XmlPullParser.END_TAG && parser.name == "si" -> shared.add(value.toString())
                } }
            }
            require(sheets.isNotEmpty()) { "XLSX 缺少可读取的工作表。" }
            var rowCount = 0
            return sheets.mapIndexedNotNull { sheetIndex, (name, id) ->
                val sheetName = targets[id] ?: if (targets.isEmpty()) "xl/worksheets/sheet${sheetIndex + 1}.xml" else throw IllegalArgumentException("XLSX 工作表 $name 缺少关联。")
                // Chart sheets have no question cells. Hidden worksheets are still included.
                if (!sheetName.startsWith("xl/worksheets/")) return@mapIndexedNotNull null
                val sheet = files[sheetName] ?: throw IllegalArgumentException("XLSX 工作表 $name 无法读取。")
                val rows = mutableListOf<TableRow>()
                var rowNumber = 0
                val cells = mutableMapOf<Int, Any?>()
                var col = 0
                var type = ""
                var readingValue = false
                var readingFormula = false
                var phonetic = false
                val value = StringBuilder()
                val formula = StringBuilder()
                parseXml(sheet) { parser -> when {
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "row" -> {
                        cells.clear()
                        rowNumber = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: rowNumber + 1
                    }
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "c" -> {
                        val reference = parser.getAttributeValue(null, "r").orEmpty().takeWhile { it.isLetter() }.uppercase(Locale.ROOT)
                        col = if (reference.isEmpty()) cells.size else reference.fold(0) { acc, char -> acc * 26 + (char - 'A' + 1) } - 1
                        require(col in 0..16383 && cells.size < 16384) { "XLSX 单行列数过多。" }
                        type = parser.getAttributeValue(null, "t").orEmpty(); value.setLength(0); formula.setLength(0)
                    }
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "rPh" -> phonetic = true
                    parser.eventType == XmlPullParser.END_TAG && parser.name == "rPh" -> phonetic = false
                    parser.eventType == XmlPullParser.START_TAG && parser.name in listOf("v", "t") -> readingValue = !phonetic
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "f" -> readingFormula = true
                    parser.isText() && readingValue -> value.append(parser.text)
                    parser.isText() && readingFormula -> formula.append(parser.text)
                    parser.eventType == XmlPullParser.END_TAG && parser.name in listOf("v", "t") -> readingValue = false
                    parser.eventType == XmlPullParser.END_TAG && parser.name == "f" -> readingFormula = false
                    parser.eventType == XmlPullParser.END_TAG && parser.name == "c" -> {
                        val raw = value.toString()
                        cells[col] = when {
                            formula.isNotEmpty() -> "=$formula"
                            type == "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1) ?: throw IllegalArgumentException("XLSX 字符串索引无效。")
                            type == "b" -> raw == "1"
                            type.isBlank() || type == "n" -> raw
                            else -> raw
                        }
                    }
                    parser.eventType == XmlPullParser.END_TAG && parser.name == "row" -> {
                        if (cells.values.any { text(it).isNotBlank() }) {
                            require(++rowCount <= MAX_TABLE_ROWS) { "表格非空行最多 20000 行，请拆分文件。" }
                            rows.add(TableRow(rowNumber, cells.toMap()))
                        }
                    }
                } }
                Table(name, rows)
            }
        } catch (error: IllegalArgumentException) { throw error }
        catch (_: Exception) { throw IllegalArgumentException("XLSX 文件无法读取，请检查文件格式。") }
    }

    private fun parseXml(bytes: ByteArray, visit: (XmlPullParser) -> Unit) {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(bytes), null)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            require(parser.eventType != XmlPullParser.DOCDECL) { "XLSX XML 不接受外部声明。" }
            visit(parser)
            parser.nextToken()
        }
    }

    private fun XmlPullParser.isText(): Boolean = eventType == XmlPullParser.TEXT || eventType == XmlPullParser.CDSECT ||
        eventType == XmlPullParser.ENTITY_REF || eventType == XmlPullParser.IGNORABLE_WHITESPACE

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    private fun text(value: Any?): String = if (value == null || value === JSONObject.NULL) "" else value.toString().trim()
}
