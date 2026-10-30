package cn.examassist.app

/** Answers map to explicit labels or exact content, never to an assumed screen order. */
internal data class ChoiceQuestion(val stem: String, val options: Map<String, String>) {
    val text: String get() = stem + "\n" + options.entries.joinToString("\n") { "${it.key}. ${it.value}" }

    fun labels(answer: String, preferContent: Boolean = false): Set<String> {
        val value = answer.trim()
        val byContent = options.entries.singleOrNull { key(it.value) == key(value) }
        if (preferContent && byContent != null) return setOf(byContent.key)
        val normalized = value.map { if (it in 'Ａ'..'Ｚ') (it.code - 0xFEE0).toChar() else it }.joinToString("")
        if (Regex("[A-Z](?:[A-Z]|[\\s,，、;/&+和与]+[A-Z])*").matches(normalized)) {
            val labels = normalized.filter { it in 'A'..'Z' }.map { it.toString() }
            if (labels.distinct().size == labels.size && options.keys.containsAll(labels)) return labels.toSet()
        }
        if (byContent != null) return setOf(byContent.key)
        val annotated = Regex("^([A-Z])[.、:：)）]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL).matchEntire(normalized)
        if (annotated != null && key(options[annotated.groupValues[1]].orEmpty()) == key(annotated.groupValues[2]))
            return setOf(annotated.groupValues[1])
        val parts = value.split('；', ';', '\n').map(String::trim).filter(String::isNotBlank)
        if (parts.size > 1) {
            val found = parts.map { part -> options.entries.singleOrNull { key(it.value) == key(part) }?.key }
            if (found.all { it != null } && found.distinct().size == found.size) return found.filterNotNull().toSet()
        }
        throw ApiException("答案无法唯一对应当前选项，已暂停，请手工核对。")
    }

    fun render(answer: String, format: AnswerFormat, preferContent: Boolean = false): String {
        val selected = labels(answer, preferContent)
        return options.entries.filter { it.key in selected }.joinToString(if (format == AnswerFormat.CONTENT) "；" else "") {
            if (format == AnswerFormat.CONTENT) it.value else it.key
        }
    }

    companion object {
        private val marker = Regex("(?m)^[ \\t]*[（(\\[]?([A-Za-zＡ-Ｚ])(?:[.．、:：)）\\]][ \\t]*|[ \\t]+|(?=\\n|$))")
        private val chrome = Regex("(?:返回|上一题|下一题|下题|下一道题|提交|提交试卷|交卷|答题卡|Next(?: question)?)", RegexOption.IGNORE_CASE)
        private val next = Regex("(?:下一题|下题|下一道题|next(?:question)?)(?:[（(]?\\d+(?:/\\d+)?[)）]?)?[>›»→]?", RegexOption.IGNORE_CASE)
        private val timer = Regex("(?:(?:剩余时间|倒计时|用时|已用时间|答题用时|考试时间)[：:]?\\s*)?\\d{1,2}[:：]\\d{2}(?:[:：]\\d{2})?")
        /** One explicitly labelled row (including a standalone letter), never a multi-option container. */
        fun optionLabel(value: String): String? {
            val matches = marker.findAll(value.trim()).toList()
            val match = matches.singleOrNull()?.takeIf { it.range.first == 0 } ?: return null
            return normalizeLabel(match.groupValues[1])
        }
        private fun normalizeLabel(value: String): String = value.single().let {
            if (it in 'Ａ'..'Ｚ') (it.code - 0xFEE0).toChar() else it.uppercaseChar()
        }.toString()
        fun key(value: String) = value.filterNot(Char::isWhitespace)
        fun isNext(value: String) = next.matches(key(value))
        private fun isChrome(value: String): Boolean = value.trim().let {
            chrome.matches(it) || isNext(it) || timer.matches(it) || Regex("\\d+\\s*/\\s*\\d+").matches(it)
        }
        fun parse(raw: String): ChoiceQuestion? {
            val lines = QuestionTextLayout.trimFooter(QuestionTextLayout.normalize(raw)).trim().lines().toMutableList()
            while (lines.isNotEmpty() && (lines.first().isBlank() || isChrome(lines.first()))) lines.removeAt(0)
            val text = lines.joinToString("\n")
            val markers = QuestionTextLayout.markers(text)
            if (markers.size < 2) return null
            val stem = text.substring(0, markers.first().start).lines().filterNot {
                val line = it.trim()
                timer.matches(line) && Regex("剩余时间|倒计时|用时|已用时间|答题用时|考试时间").containsMatchIn(line)
            }.joinToString("\n").trim()
            if (stem.isBlank()) return null
            val options = markers.mapIndexed { index, match ->
                match.label to text.substring(match.end, markers.getOrNull(index + 1)?.start ?: text.length).trim()
            }
            if (options.map { it.first } != options.indices.map { ('A'.code + it).toChar().toString() } ||
                options.any { it.second.isBlank() } || options.map { key(it.second) }.distinct().size != options.size) return null
            return ChoiceQuestion(stem, options.toMap())
        }
    }
}
