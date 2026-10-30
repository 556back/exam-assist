package cn.examassist.app

import java.text.Normalizer
import java.util.Locale

/** Reject only contradictions that can be proved from the current option labels and text. */
object AnswerCheck {
    private val insensitive = setOf(RegexOption.IGNORE_CASE)
    private const val LETTERS = "([A-Z](?:\\s*[,，、/&+和与]\\s*[A-Z])*|[A-Z]+)(?![A-Za-z])"
    private val option = Regex("^\\s*([A-Z])[.、:)]+\\s*(\\S[^\\n]*)$", RegexOption.MULTILINE)
    private val selected = Regex("^(?:(?:正确答案|答案|选择|选项|选|应选)\\s*(?:应为|是|为|[:：])?\\s*|" +
        "(?:the\\s+)?(?:correct\\s+)?answer\\s*(?:is|[:：])?\\s*|option\\s*)?" + LETTERS, insensitive)
    private val conclusion = Regex("(?:正确答案|答案|应选|应该选|因此选|所以选|故选|answer)\\s*" +
        "(?:应为|是|为|选|[:：]|is)*\\s*(不是|不应选|不能选|不选|排除|not)?\\s*" + LETTERS, insensitive)
    private val mapping = Regex("(?:当前(?:题(?:目|干)?)?|本题)(?:的)?(?:正确)?选项\\s*([A-Z])\\s*" +
        "(?:为|是|[.(:=])\\s*", insensitive)
    private val reference = Regex("参考|原(?:题|答案|参考|版本)|旧(?:题|答案|参考|版本)|" +
        "\\b(?:reference|original|previous|old)\\b", insensitive)
    private val scope = Regex("参考|原(?:题|答案|参考|版本)|旧(?:题|答案|参考|版本)|初次|先前|之前|当前|本题|最终|更正|" +
        "\\b(?:reference|original|previous|old|current|final)\\b", insensitive)
    private val currentScopes = setOf("当前", "本题", "最终", "更正", "current", "final")
    private val hypothetical = Regex("如果|假设|可能|也许|不确定|例如|比如|\\b(?:if|suppose|maybe|perhaps)\\b", insensitive)
    private val rejectedSuffix = Regex("^\\s*(?:是|为)?\\s*(?:错误|不正确|不符合|不可|不能|不应)")

    fun conflicts(question: String, answer: String, explanation: String, warnings: List<String>): Boolean {
        val pairs = option.findAll(normalize(question)).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
        val options = pairs.toMap()
        if (options.size < 2 || options.size != pairs.size) return false
        val normalizedAnswer = normalize(answer).trim()
        val selection = selected.find(normalizedAnswer) ?: return false
        val actual = labels(selection.groupValues[1])
        if (!options.keys.containsAll(actual)) return false

        fun wrongOptionText(label: String, text: String): Boolean {
            val named = options.filterValues {
                Regex("^" + Regex.escape(it) + "(?=$|[\\s，,。.;；:：()（）])", insensitive).containsMatchIn(text)
            }.keys
            return named.isNotEmpty() && label !in named
        }
        // ponytail: explicit single-line options only; semantic contradictions remain AI's responsibility.
        if (actual.size == 1 && wrongOptionText(actual.first(),
                normalizedAnswer.substring(selection.range.last + 1).trimStart(' ', '.', '、', ':', '：', '(', ')', '（', '）'))) {
            return true
        }
        for (entry in listOf(explanation) + warnings) {
            val value = normalize(entry)
            for (match in mapping.findAll(value)) {
                val context = sentencePrefix(value, match.range.first)
                if (!reference.containsMatchIn(context) && wrongOptionText(match.groupValues[1].uppercase(Locale.ROOT),
                        value.substring(match.range.last + 1))) return true
            }
            for (match in conclusion.findAll(value)) {
                val named = labels(match.groupValues[2])
                if (!options.keys.containsAll(named)) continue
                val context = sentencePrefix(value, match.range.first)
                if (Regex("其\\s*$").containsMatchIn(context)) continue
                val lastScope = scope.findAll(context).lastOrNull()?.value?.lowercase(Locale.ROOT)
                if (lastScope != null && lastScope !in currentScopes) continue
                if (hypothetical.containsMatchIn(context)) continue
                val rejected = match.groupValues[1].isNotEmpty() ||
                    rejectedSuffix.containsMatchIn(value.substring(match.range.last + 1))
                if ((rejected && actual.any { it in named }) || (!rejected && !actual.containsAll(named))) return true
            }
        }
        return false
    }

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
    private fun labels(value: String) = Regex("[A-Z]").findAll(value.uppercase(Locale.ROOT)).map { it.value }.toSet()
    private fun sentencePrefix(value: String, position: Int): String {
        val boundary = "。；;\n!！?？".maxOf { value.lastIndexOf(it, position - 1) } + 1
        return value.substring(boundary, position)
    }
}
