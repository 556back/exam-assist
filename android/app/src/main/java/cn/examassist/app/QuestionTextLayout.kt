package cn.examassist.app

import java.text.Normalizer
import java.util.Locale

/** Shared layout rules for bank lookup and accessible choice parsing. Never remove arbitrary prose. */
internal object QuestionTextLayout {
    private const val typeName = "(?:单项选择题|多项选择题|不定项选择题|单选题|多选题|判断题|填空题|选择题|问答题|单选|多选|判断|填空)"
    private const val score = "(?:[0-9]+(?:\\.[0-9]+)?[ \\t]*分)"
    private const val progress = "(?:\\d{1,4}\\s*[/|]\\s*\\d{1,4}|[xX]+\\s*[/|]\\s*[xX]+)"
    private val typePrefix = Regex("^(?:[\\[【(]$typeName[\\]】)]|$typeName(?=[\\s(:：\\dXx]|$)|(?:单项选择题|多项选择题|不定项选择题|单选题|多选题|判断题|填空题|选择题|问答题))(?:[ \\t]*\\(?$score\\)?)?[ \\t]*[:：]?[ \\t]*")
    private val progressPrefix = Regex("^\\(?$progress\\)?[ \\t]*[:：|]?[ \\t]*")
    private val titleBeforeType = Regex("^([^\\n。?!！？:：]{1,80}?)[ \\t]*(?=[\\[【(]?$typeName)")
    private val condition = Regex("条件|假设|如果|若|假如|在|当|根据|按照|对于|关于|给定|以下|下列|上述|本题|题目|正确|错误")
    private val numberPrefix = Regex("^(?:第[ \\t]*\\d{1,4}[ \\t]*题[.、:]?|\\d{1,4}[.、)](?=[ \\t]*(?:[^\\d\\s]|$)))[ \\t]*")
    private val metadata = Regex("(?:返回|上一题|下一题|下题|下一道题|提交|提交试卷|交卷|答题卡|收藏|取消收藏|已收藏|(?:本题分值|分值)[ \\t]*:?[ \\t]*$score|$score|第\\s*\\d+\\s*题\\s*[,，]?\\s*共\\s*\\d+\\s*题|\\d+\\s*/\\s*\\d+|(?:(?:剩余时间|倒计时|用时|已用时间|答题用时|考试时间)\\s*:?[ \\t]*)?\\d{1,2}:\\d{2}(?::\\d{2})?)")
    private val feedback = Regex("(?:纠错|题目纠错|试题纠错|我要纠错|报错|题目反馈|问题反馈|举报|Report(?: an? (?:error|issue))?|Feedback)", RegexOption.IGNORE_CASE)
    private val heading = Regex("[^。?!！？:：]{0,80}(?:学习平台|培训平台|教育平台|在线学习|学习中心|培训中心|网络学院|学堂|课堂|练习|考试|测验|训练|知识竞赛|题库|答题)(?:\\([^()]{0,30}\\))?")
    private val lineMarker = Regex("(?m)^[ \\t]*(?:[\\[(]([A-Za-z])[)\\]]|([A-Za-z])(?:[.、:)][ \\t]*|[ \\t]+|(?=\\n|$)))[ \\t]*")
    private val compactMarker = Regex("(?m)^[ \\t]*([A-Z])(?=[\\p{IsHan}\\d])")
    private val standaloneLabel = Regex("(?:[A-Za-z][.、:)]?|[\\[(][A-Za-z][)\\]])")
    private val wordLabel = Regex("[A-Za-z][ \\t]+")
    private val invisible = Regex("[\u200B\uFEFF]")
    // Inline choices need explicit punctuation and an ordered A/B/... sequence. Plain English
    // words and isolated option references in a stem must not become choice boundaries.
    private val inlineMarker = Regex("(?:^|[ \\t\\n]+)(?:[\\[(]([A-Za-z])[)\\]]|([A-Za-z])[.、:)])[ \\t]*")
    private val attachedMarker = Regex("(?<=[\\p{IsHan}。?!;:)\\]])(?:[\\[(]([A-Za-z])[)\\]]|([A-Za-z])[.、:)])[ \\t]*")
    data class Marker(val start: Int, val end: Int, val label: String)

    private fun uniqueOrderedChoices(markers: List<Marker>): List<Marker> {
        data class Chain(val length: Int, val count: Int, val index: Int)
        val best = mutableMapOf<Char, Chain>()
        val next = IntArray(markers.size) { -1 }
        for (index in markers.indices.reversed()) {
            val label = markers[index].label.single()
            val following = best[label + 1]
            next[index] = following?.index ?: -1
            val chain = Chain(1 + (following?.length ?: 0), following?.count ?: 1, index)
            val previous = best[label]
            best[label] = when {
                previous == null || chain.length > previous.length -> chain
                chain.length == previous.length -> previous.copy(count = minOf(2, previous.count + chain.count))
                else -> previous
            }
        }
        val first = best['A'] ?: return markers
        if (first.length < 2 || first.count != 1) return markers
        val result = mutableListOf<Marker>()
        var index = first.index
        while (index >= 0) { result += markers[index]; index = next[index] }
        return result
    }

    fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFC).map { char ->
        when (char) {
            in '\uFF01'..'\uFF5E' -> (char.code - 0xFEE0).toChar()
            '\u3000', '\u00A0' -> ' '
            else -> char
        }
    }.joinToString("").replace(invisible, "").replace("℃", "°C").replace("℉", "°F")
        .replace("\r\n", "\n").replace('\r', '\n')

    fun markers(raw: String): List<Marker> {
        fun read(pattern: Regex) = pattern.findAll(raw).map {
            Marker(it.range.first, it.range.last + 1, it.groupValues[1].ifEmpty { it.groupValues[2] }.uppercase(Locale.ROOT))
        }.toList()
        fun ordered(markers: List<Marker>) = markers.size >= 2 && markers.map { it.label } == markers.indices.map { ('A' + it).toString() }
        val explicit = mutableListOf<Marker>()
        for (candidate in read(lineMarker)) {
            // Lowercase assignment statements in programming questions are stem content.
            val writtenLabel = raw.substring(candidate.start, candidate.end).trim()
            if (Regex("[a-z]").matches(writtenLabel) && raw.substring(candidate.end).trimStart().startsWith("=")) continue
            val previous = explicit.lastOrNull()
            // Accessibility commonly emits "A\nA 类火灾". The second A is option
            // content, not another label. Only collapse a space-delimited word directly
            // after a standalone label; repeated punctuated labels stay invalid.
            val contentAfterLabel = previous != null &&
                standaloneLabel.matches(raw.substring(previous.start, previous.end).trim()) &&
                raw.substring(previous.end, candidate.start).isBlank() &&
                wordLabel.matches(raw.substring(candidate.start, candidate.end).trimStart())
            if (!contentAfterLabel) explicit += candidate
        }
        // Roman-numeral statements (I./II./III.) can precede actual A/B/... choices.
        val firstA = explicit.indexOfFirst { it.label == "A" }
        val choices = uniqueOrderedChoices(if (firstA > 0 && ordered(explicit.drop(firstA))) explicit.drop(firstA) else explicit)
        val combined = (choices + read(compactMarker)).sortedBy { it.start }
        // A compact A文字/B文字 layout is accepted only as a complete ordered sequence,
        // never from one ambiguous standalone token such as "B组".
        val lines = if (combined.size > choices.size && ordered(combined)) combined else choices
        val inline = read(inlineMarker)
        val regular = (if (inline.size > lines.size && ordered(inline)) inline else lines).let {
            if (it.size == 1 && it.first().label != "A") emptyList() else it
        }
        // WebView descriptions can concatenate the stem and A, or adjacent choices.
        // Accept attached punctuation only when it completes one ordered A/B/... sequence.
        val attached = (regular + inline + read(attachedMarker)).distinctBy { it.end }.sortedBy { it.start }
        return if (attached.size > regular.size && ordered(attached)) attached else regular
    }

    /** Strip only explicit leading question-number/type tokens, including combined headings. */
    fun stem(raw: String): String {
        var value = trimPage(raw).trim()
        repeat(6) {
            value = value.replaceFirst(numberPrefix, "").trimStart()
            value = stripType(value)
        }
        return value
    }

    /** A merged accessibility header can be "学习平台单选题12|100题干". */
    private fun stripType(raw: String): String {
        var value = raw
        val title = titleBeforeType.find(value)
        if (title != null) {
            val remainder = value.removePrefix(title.value)
            val afterType = typePrefix.find(remainder)?.let { remainder.removePrefix(it.value) }
            if (heading.matches(title.value.trim()) ||
                (afterType != null && progressPrefix.containsMatchIn(afterType) && neutralTitle(title.value))) value = remainder
        }
        val type = typePrefix.find(value) ?: return value
        value = value.removePrefix(type.value).trimStart()
        return value.replaceFirst(progressPrefix, "").trimStart()
    }

    private fun typeLine(raw: String): Boolean {
        val value = raw.trim().replaceFirst(numberPrefix, "").trim()
        return value.isNotBlank() && stripType(value).isBlank()
    }
    private fun chrome(raw: String): Boolean = raw.isBlank() || metadata.matches(raw.trim()) || feedback.matches(raw.trim()) ||
        Regex(progress).matches(raw.trim()) || typeLine(raw) || ChoiceQuestion.isNext(raw)

    // Unknown platform/course names are accepted only before an explicit type boundary.
    // Never treat a sentence, numeric condition or negation as an arbitrary title.
    private fun neutralTitle(raw: String): Boolean = raw.trim().let {
        it.length in 2..40 && !Regex("[。?!！？:：\\d]").containsMatchIn(it) &&
            !condition.containsMatchIn(it) && BankTextSimilarity.sameFacts(it, "")
    }

    fun isFooter(raw: String): Boolean = chrome(normalize(raw))

    internal fun isStandaloneLabel(raw: String): Boolean = standaloneLabel.matches(normalize(raw).trim())

    fun trimFooter(raw: String): String {
        val lines = raw.lines()
        var end = lines.size
        // A standalone final label can be followed by a real option named "返回" or
        // "纠错". Protect its first content line; only subsequent terminal controls
        // may be removed. Never strip these words from inside prose or inline options.
        val last = markers(raw).lastOrNull()
        val contentLine = last?.let { marker ->
            val first = raw.substring(marker.end).indexOfFirst { !it.isWhitespace() }
            if (first < 0) null else raw.take(marker.end + first).count { it == '\n' }
        } ?: -1
        while (end > 0 && end - 1 > contentLine && chrome(lines[end - 1])) end--
        return lines.take(end).joinToString("\n")
    }

    fun trimPage(raw: String): String {
        val lines = raw.lines().toMutableList()
        var start = 0
        var end = lines.size
        // A title is removable only in the header before a recognized type boundary.
        val boundary = lines.take(10).indexOfFirst { typeLine(it) || stripType(it.trim()) != it.trim() }
        if (boundary > 0 && lines.take(boundary).all { chrome(it) || heading.matches(it.trim()) || neutralTitle(it) }) start = boundary
        var typeSeen = false
        while (start < end && chrome(lines[start])) {
            // Keep a numbered type header as the boundary of the first question on a
            // multi-question page; split() will remove the number before parsing its body.
            if (typeLine(lines[start]) && numberPrefix.containsMatchIn(lines[start].trim())) break
            typeSeen = typeSeen || typeLine(lines[start])
            start++
        }
        if (typeSeen && start < end) lines[start] = lines[start].trimStart().replaceFirst(progressPrefix, "")
        return trimFooter(lines.subList(start, end).joinToString("\n"))
    }
}
