package cn.examassist.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One schema for extracted files and questions saved on the phone. */
object BankTemplate {
    const val VERSION = 1
    val csv = "\uFEFF题型,题干,选项A,选项B,选项C,选项D,选项E,选项F,参考答案,解析,来源,答案来源\r\n"

    fun normalize(preview: JSONObject): JSONObject {
        val questions = preview.getJSONArray("questions")
        require(questions.length() in 1..BankImporter.MAX_QUESTIONS) { "单个题库需要 1 到 5000 条题目。" }
        for (index in 0 until questions.length()) {
            val question = questions.getJSONObject(index)
            BankImporter.validateQuestion(question, index + 1)
            normalizeQuestion(question)
            val answer = question.getString("reference_answer")
            val origin = question.optString("answer_origin")
            question.put("answer_origin", when {
                answer.isBlank() -> "missing"
                origin in setOf("model", "模型补全", "AI", "大模型") -> "model"
                else -> "original"
            })
            if (question.optString("question_type").isBlank()) question.put("question_type", when {
                question.getJSONArray("options").length() > 0 -> if (Regex("[A-Z]{2,}").matches(answer.trim())) "多选题" else "选择题"
                answer.trim() in setOf("对", "错", "正确", "错误", "√", "×", "true", "false") -> "判断题"
                Regex("_{2,}|[（(]\\s*[）)]").containsMatchIn(question.getString("stem")) -> "填空题"
                else -> "问答题"
            })
            question.put("schema_version", VERSION)
        }
        return preview.put("schema_version", VERSION).put("total", questions.length())
            .put("quality", LocalBankMatching.audit(questions.objects()))
    }

    /** Deterministic formatting only: preserve originals and never guess missing answers. */
    internal fun normalizeQuestion(question: JSONObject) {
        val originalStem = question.optString("stem")
        val text = QuestionTextLayout.normalize(originalStem).trim()
        val markers = QuestionTextLayout.markers(text)
        val embedded = markers.mapIndexed { index, marker -> marker.label to
            text.substring(marker.end, markers.getOrNull(index + 1)?.start ?: text.length).trim() }
        val options = question.optJSONArray("options") ?: return
        fun optionKey(value: String) = BankTextSimilarity.layoutKey(compactBankText(QuestionTextLayout.normalize(value)))
        val canExtract = embedded.size >= 2 && embedded.map { it.first }.distinct().size == embedded.size &&
            embedded.all { it.second.isNotBlank() } && text.substring(0, markers.first().start).isNotBlank()
        if (canExtract && (options.length() == 0 || options.objects().associate { it.optString("label") to optionKey(it.optString("text")) } ==
                    embedded.associate { it.first to optionKey(it.second) })) {
            if (options.length() == 0) question.put("options", JSONArray(embedded.map { (label, content) -> JSONObject().put("label", label).put("text", content) }))
            question.put("stem", text.substring(0, markers.first().start).trim())
        }
        question.optJSONArray("options").objects().forEach { option ->
            val rawLabel = option.optString("label")
            val label = QuestionTextLayout.normalize(rawLabel).uppercase(Locale.ROOT).trim()
                .replace(Regex("^(?:选项)?(?:\\(([A-Z])\\)|\\[([A-Z])\\]|([A-Z])[.、:)]?)$")) { match ->
                    match.groupValues.drop(1).first { it.isNotBlank() }
                }
            if (Regex("[A-Z]").matches(label)) {
                if (label != rawLabel && !option.has("original_label")) option.put("original_label", rawLabel)
                option.put("label", label)
            }
        }
        val rawAnswer = question.optString("reference_answer")
        if (question.getJSONArray("options").length() >= 2) LocalBankMatching.canonicalAnswer(question)?.let { answer ->
            if (answer != rawAnswer && !question.has("original_reference_answer")) question.put("original_reference_answer", rawAnswer)
            question.put("reference_answer", answer)
        }
        if (question.optString("stem") != originalStem && !question.has("original_stem")) question.put("original_stem", originalStem)
    }
}
