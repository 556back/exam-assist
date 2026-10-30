package cn.examassist.app

/** Offline lexical retrieval. Scores express text similarity, never answer accuracy. */
internal object BankTextSimilarity {
    private val facts = Regex("[+-]?\\d+(?:[.,]\\d+)*(?:[%‰]|°[CF])?|[<>≤≥=≠²³+−±*/÷×^]|(?:mm|cm|km|mg|kg|ml|ms|kPa|MPa|Pa|m|g|l|s|h)(?![A-Za-z])|毫米|厘米|千米|公里|毫克|千克|公斤|毫升|分钟|小时|摄氏|华氏|米|克|升|秒|天|年|月|倍|百分之|万|亿|不得|不能|不可|不应|不是|没有|禁止|允许|必须|应当|不|未|无|非|正确|错误|除外|除了|至少|至多|最多|最少|最大|最小|最高|最低|超过|不足|大于|小于|增加|减少|增大|减小|升高|降低|上升|下降|首先|最后|先|后|高于|低于|早于|晚于|正比|反比|平均|平方|立方|加|减|乘|除|\\b(?:not|no|never|except|incorrect|false|true|must|before|after)\\b", RegexOption.IGNORE_CASE)
    private val punctuation = Regex("[，,。；;：:？！?!、\"“”‘’]")
    data class Text(val content: String, val facts: List<String>)
    fun prepare(value: String): Text = Text(contentKey(value), facts.findAll(value).map { it.value }.toList())
    fun contentKey(value: String): String = punctuation.replace(value, "")
    // Punctuation may differ in layout, but numeric separators and protected conditions remain
    // part of option identity ("1,000" must not silently become "1000" or "1 000").
    fun layoutKey(value: String): String = contentKey(value) + "\u0000" + facts.findAll(value).joinToString("\u0000") { it.value }
    fun sameFacts(left: String, right: String): Boolean = facts.findAll(left).map { it.value }.toList() ==
        facts.findAll(right).map { it.value }.toList()

    fun score(left: String, right: String): Double = score(prepare(left), prepare(right))

    fun score(left: Text, right: Text): Double {
        val a = left.content
        val b = right.content
        val lexical = when {
            a == b -> 1.0
            a.isEmpty() || b.isEmpty() -> 0.0
            a.length == 1 || b.length == 1 -> if (a in b || b in a) 2.0 / (a.length + b.length) else 0.0
            else -> {
                // Multiset bigram Dice: linear work, with no length or edit-count cutoff.
                // Counting repeated pairs prevents repeated page text from receiving a perfect score.
                val pairs = a.windowed(2).groupingBy { it }.eachCount().toMutableMap()
                var overlap = 0
                for (pair in b.windowed(2)) {
                    val remaining = pairs[pair] ?: 0
                    if (remaining > 0) {
                        overlap++
                        pairs[pair] = remaining - 1
                    }
                }
                minOf(0.999, 2.0 * overlap / (a.length + b.length - 2))
            }
        }
        // Changed quantities/negations lower the score; the user's threshold still decides.
        return lexical * if (left.facts == right.facts) 1.0 else 0.7
    }
}
