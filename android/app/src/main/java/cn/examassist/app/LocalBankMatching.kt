package cn.examassist.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Rank by question similarity, accept the highest score at the user's saved threshold. */
internal object LocalBankMatching {
    private val numbered = Regex("(?m)^[ \\t]*(?:第[ \\t]*\\d{1,4}[ \\t]*题[.、:：]?|\\d{1,4}[.、)）](?=[ \\t]*[^\\d\\s]))[ \\t]*")
    private val labels = Regex("[A-Z](?:[A-Z]|[\\s,，、;/&+和与]+[A-Z])*")
    private val answerPrefix = Regex("^[【\\[]?(?:正确答案|参考答案|标准答案|答案|应选|选择|选项)[】\\]]?[ \\t]*(?:是|为|[:：])?[ \\t]*")
    private data class Question(val stem: String, val options: Map<String, String>) {
        val stemText = BankTextSimilarity.prepare(stem)
        val optionIdentities = options.mapValues { LocalBankMatching.optionKey(it.value) }
        val optionSet = optionIdentities.values.toSet()
    }

    /** Only a Hit contains a usable bank answer; other outcomes may fall back to enabled AI. */
    sealed class Lookup {
        data class Hit(val result: JSONObject) : Lookup()
        data class Miss(val reason: String) : Lookup()
        data class Blocked(val reason: String) : Lookup()
    }

    fun answer(raw: String, candidates: List<JSONObject>, format: AnswerFormat = AnswerFormat.LABELS,
               threshold: Int = BankMatchSettings.DEFAULT_THRESHOLD): JSONObject =
        when (val match = lookup(raw, candidates, format, threshold)) {
            is Lookup.Hit -> match.result
            is Lookup.Miss -> throw ApiException(match.reason)
            is Lookup.Blocked -> throw ApiException(match.reason)
        }

    fun lookup(raw: String, candidates: List<JSONObject>, format: AnswerFormat = AnswerFormat.LABELS,
               threshold: Int = BankMatchSettings.DEFAULT_THRESHOLD, report: ((JSONObject) -> Unit)? = null): Lookup {
        require(BankMatchSettings.valid(threshold)) { "题库匹配阈值必须在 0% 到 100% 之间。" }
        val details = JSONObject().put("raw_text", raw.take(20000)).put("threshold", threshold).put("read_at", System.currentTimeMillis())
            .put("enabled_questions", candidates.size).put("questions", JSONArray())
        fun finish(result: Lookup): Lookup {
            if (report != null) {
                details.put("status", when (result) { is Lookup.Hit -> "已匹配"; is Lookup.Miss -> "未匹配"; is Lookup.Blocked -> "答案不可用" })
                details.put("reason", when (result) {
                    is Lookup.Hit -> "题库答案已对应当前选项。" + if (details.getJSONArray("questions").objects().any { it.optBoolean("layout_recovered") })
                        "选项标签读取顺序错位，已用完整选项内容恢复边界。" else ""
                    is Lookup.Miss -> result.reason
                    is Lookup.Blocked -> result.reason
                })
                report(details)
            }
            return result
        }
        if (raw.isBlank()) return finish(Lookup.Miss("没有题目文字，请粘贴题目或完整显示题干和选项后重试。"))
        if (raw.length > 20000) return finish(Lookup.Miss("题目文字过多，请只显示当前题目后重试。"))
        if (candidates.isEmpty()) return finish(Lookup.Miss("没有启用的本地题库，请先导入并启用题库。"))
        val cleaned = trimChrome(normalize(raw))
        details.put("cleaned_text", cleaned)
        // Parse saved records once, even when the page contains several questions.
        val prepared = candidates.mapNotNull { item -> stored(item)?.let { item to it } }
        details.put("parseable_questions", prepared.size)
        if (prepared.isEmpty()) return finish(Lookup.Miss("已读取 ${candidates.size} 条启用题库记录，但题干或选项格式均不可用，请在题库导入预览中核对格式。"))
        // Numbered code lines and enumerated conditions can occur inside one stem.
        // Prefer a complete, labelled question supported by all bank option contents
        // before interpreting its internal numbers as separate question headers.
        val whole = if (numbered.containsMatchIn(cleaned)) parse(cleaned) ?: recoverStandaloneLayout(cleaned, prepared).singleOrNull() else null
        val complete = whole != null && whole.options.isNotEmpty() && prepared.any { (item, saved) ->
            whole.optionSet == saved.optionSet && whole.stemText.facts == saved.stemText.facts &&
                questionScore(whole, saved, item) >= threshold / 100.0
        }
        val blocks = try { if (complete) listOf(cleaned) else split(cleaned) } catch (error: ApiException) {
            return finish(Lookup.Miss(error.message.orEmpty()))
        }
        val references = JSONArray()
        val warnings = linkedSetOf<String>()
        var scored = false
        var lowestScore = 1.0
        var miss: String? = null
        val answers = blocks.mapIndexed { index, block ->
            val text = trimChrome(block)
            val parsed = parse(text)
            val layouts = if (parsed != null && prepared.any { (_, saved) ->
                parsed.optionSet == saved.optionSet
            }) emptyList() else recoverStandaloneLayout(text, prepared)
            val current = if (layouts.size > 1) null else layouts.singleOrNull() ?: parsed
            val recovered = current != null && current != parsed
            val detail = JSONObject().put("number", index + 1)
            details.getJSONArray("questions").put(detail)
            if (current == null) {
                detail.put("parse_error", "选项不完整或题目边界不明确").put("text", block)
                miss = "第 ${index + 1} 题选项不完整或题目边界不明确，请单独显示完整题目后重试。"
                return@mapIndexed ""
            }
            if (recovered) detail.put("layout_recovered", true)
            val ranked = prepared.map { (item, saved) -> Triple(item, saved, questionScore(current, saved, item)) }
            val score = ranked.maxOf { it.third }
            if (report != null) {
                detail.put("stem", current.stem).put("options", JSONObject(current.options)).put("score", score)
                    .put("best", ranked.first { it.third == score }.first)
                    .put("tied_candidates", ranked.count { it.third == score })
            }
            if (score < threshold / 100.0) {
                val sameStemRows = prepared.filter { sameStem(current.stem, it.second.stem) }
                val detail = if (sameStemRows.isNotEmpty()) {
                    val counts = sameStemRows.map { it.second.options.size }.distinct().sorted().joinToString("/")
                    "第 ${index + 1} 题题干已命中题库，但选项未对应：页面读到 ${current.options.size} 项，题库 $counts 项。请核对是否漏读选项、混入页面文字或选项内容不同。"
                } else {
                    "第 ${index + 1} 题未命中已启用的 ${candidates.size} 条本地题库记录（可解析 ${prepared.size} 条）。读到题干「${current.stem.take(80)}」，选项 ${current.options.size} 项。请核对题干是否混入标题、题型或附加文字，以及对应题库是否启用。"
                }
                miss = "最高匹配度 ${BankMatchSettings.percent(score)}，未达到设定阈值 $threshold%。$detail"
                return@mapIndexed ""
            }
            // Only tied winners participate in answer validation. A close runner-up never vetoes the winner.
            val matches = ranked.filter { it.third == score }.map { it.first to it.second }
            val matchType = if (recovered) "layout_recovered" else if (score < 1.0) "scored" else if (matches.any { it.second.stem != current.stem }) "normalized" else "exact"
            scored = scored || score < 1.0
            lowestScore = minOf(lowestScore, score)
            val values = matches.map { (item, saved) -> remap(current, saved, item.optString("reference_answer")) }
            if (values.any { it == null }) {
                val hasMissing = matches.any { it.first.optString("reference_answer").isBlank() }
                val invalidAnswer = matches.any { (item, saved) -> remap(saved, saved, item.optString("reference_answer")) == null }
                val reason = when {
                    hasMissing -> "题干达到匹配阈值，但参考答案缺失，请核对题库。"
                    invalidAnswer -> "题干达到匹配阈值，但参考答案格式不可用或题库选项重复，请核对导入预览。"
                    else -> "题干达到匹配阈值，但答案项未对应当前选项（页面读到 ${current.options.size} 项）。请在题库页查看最近读取文字，核对漏读、连行及选项差异。"
                }
                return finish(Lookup.Blocked("第 ${index + 1} 题$reason"))
            }
            if (values.distinct().size != 1) return finish(Lookup.Blocked("第 ${index + 1} 题在题库中的参考答案不一致，请核对题库。"))
            matches.forEach { (item, _) -> references.put(JSONObject(item.toString()).put("score", score).put("match_type", matchType)) }
            if (score < 1.0) warnings += "第 ${index + 1} 题匹配度 ${BankMatchSettings.percent(score)}，达到阈值 $threshold%；匹配度不代表答案准确率。"
            if (matches.any { it.first.optString("answer_origin") == "model" }) warnings += "题库中包含模型补全答案，请核对原题。"
            val answer = values.first()!!
            if (format == AnswerFormat.CONTENT && current.options.isNotEmpty())
                answer.map { current.options.getValue(it.toString()) }.joinToString("；") else answer
        }
        miss?.let { return finish(Lookup.Miss(it)) }
        return finish(Lookup.Hit(JSONObject().put("answer", answers.joinToString("\n")).put("explanation", "")
            .put("warnings", JSONArray(warnings.toList())).put("references", references)
            .put("match_score", lowestScore).put("match_threshold", threshold)
            .put("answer_source", if (scored) "local_bank_scored" else "local_bank")))
    }

    private fun questionScore(current: Question, saved: Question, item: JSONObject): Double {
        if (sameQuestion(current, saved) || sameJudgment(current, saved, item)) return 1.0
        val stem = BankTextSimilarity.score(current.stemText, saved.stemText)
        val compatibleJudgment = (judgmentOptions(current) || current.options.isEmpty()) &&
            (judgmentOptions(saved) || saved.options.isEmpty() &&
                (item.optString("question_type") == "判断题" || truth(item.optString("reference_answer")) != null))
        if (compatibleJudgment || current.options.isEmpty() && saved.options.isEmpty()) return stem
        val left = current.optionSet
        val right = saved.optionSet
        val options = 2.0 * left.count { it in right } / (left.size + right.size)
        return minOf(0.999, 0.8 * stem + 0.2 * options)
    }

    private fun split(raw: String): List<String> {
        val starts = numbered.findAll(raw).toList()
        if (starts.isEmpty()) return listOf(raw.trim())
        if (raw.substring(0, starts.first().range.first).isNotBlank()) {
            throw ApiException("题目边界不明确，请只粘贴题干和选项，或单独显示完整题目后重试。")
        }
        return starts.mapIndexed { index, start ->
            raw.substring(start.range.last + 1, starts.getOrNull(index + 1)?.range?.first ?: raw.length).trim()
        }
    }

    private fun parse(raw: String): Question? {
        val markers = QuestionTextLayout.markers(raw)
        val stem = stemKey(raw.substring(0, markers.firstOrNull()?.start ?: raw.length))
        if (stem.isBlank()) return null
        val pairs = markers.mapIndexed { index, marker ->
            marker.label to raw.substring(marker.end, markers.getOrNull(index + 1)?.start ?: raw.length).trim()
        }
        if (pairs.isNotEmpty() && (pairs.size < 2 || pairs.any { it.second.isBlank() } ||
            pairs.map { it.first }.distinct().size != pairs.size || pairs.map { optionKey(it.second) }.distinct().size != pairs.size)) return null
        return Question(stem, pairs.toMap())
    }

    /** Recover lost row geometry using complete option text, without consulting answers.
     * Each explicit label must occur once, at the start or end of its own content block.
     * All question text must be consumed and every bank option used exactly once. Different best
     * interpretations remain a parse failure; no missing option is invented.
     */
    private fun recoverStandaloneLayout(raw: String, prepared: List<Pair<JSONObject, Question>>): List<Question> {
        val markers = QuestionTextLayout.markers(raw).filter { marker ->
            val lineStart = raw.lastIndexOf('\n', marker.start - 1) + 1
            val lineEnd = raw.indexOf('\n', marker.end).takeIf { it >= 0 } ?: raw.length
            raw.substring(lineStart, marker.start).isBlank() &&
                QuestionTextLayout.isStandaloneLabel(raw.substring(marker.start, lineEnd))
        }
        val first = markers.firstOrNull { it.label == "A" } ?: return emptyList()
        val stem = stemKey(raw.substring(0, first.start))
        if (stem.isBlank()) return emptyList()
        val tokens = raw.substring(first.start).lines().map(String::trim).filter(String::isNotBlank)
        val tokenLabels = tokens.map { if (QuestionTextLayout.isStandaloneLabel(it)) ChoiceQuestion.optionLabel(it) else null }
        val layouts = mutableListOf<Pair<Question, Double>>()
        val cache = mutableMapOf<Set<String>, List<Map<String, String>>>()
        val identities = mutableMapOf<Pair<Int, Int>, String>()
        // The stem plus option identities are sufficient to share work for duplicate records.
        val ranked = prepared.filter { it.second.options.size in 2..26 }
            .distinctBy { (_, saved) -> saved.stem to saved.optionSet }
            .map { (item, saved) -> Triple(item, saved, questionScore(Question(stem, saved.options), saved, item)) }
            .sortedByDescending { it.third }
        var best = -1.0
        for ((_, saved, score) in ranked) {
            if (score < best) break
            val vocabulary = saved.optionSet
            val choiceCount = saved.options.size
            if (vocabulary.size != choiceCount) continue
            val results = mutableListOf<Map<String, String>>()
            val texts = vocabulary.map { it.substringBefore('\u0000') }
            fun identity(from: Int, until: Int): String = identities.getOrPut(from to until) {
                optionKey(tokens.subList(from, until).joinToString("\n"))
            }
            fun visit(start: Int, index: Int, options: Map<String, String>, used: Set<String>) {
                if (results.size >= 2) return // Two interpretations already establish ambiguity.
                if (index == choiceCount) {
                    // A delayed final letter can be followed by controls that the ordinary
                    // footer cleaner protected as possible content. Remove them only after
                    // all actual option content has been accounted for.
                    if (used == vocabulary && tokens.drop(start).all(QuestionTextLayout::isFooter) && options !in results) results += options
                    return
                }
                if (start >= tokens.size) return
                val label = ('A' + index).toString()
                fun accept(from: Int, until: Int, next: Int) {
                    if (from >= until) return
                    val content = tokens.subList(from, until).joinToString("\n")
                    val id = identity(from, until)
                    if (id in vocabulary && id !in used) visit(next, index + 1, options + (label to content), used + id)
                }
                if (tokenLabels[start] == label) {
                    for (end in start + 2..tokens.size) {
                        // Extending a content block cannot repair an already different
                        // lexical prefix. This also bounds work for long fragmented pages.
                        val prefix = identity(start + 1, end).substringBefore('\u0000')
                        if (texts.none { it.startsWith(prefix) }) break
                        accept(start + 1, end, end)
                    }
                }
                if (index > 0) {
                    // F, t or even B can be the option's literal content. A letter
                    // becomes a boundary only when all complete option texts validate it.
                    for (end in start + 1 until tokens.size) {
                        val prefix = identity(start, end).substringBefore('\u0000')
                        if (texts.none { it.startsWith(prefix) }) break
                        if (tokenLabels[end] == label) accept(start, end, end + 1)
                    }
                }
            }
            val recovered = cache.getOrPut(vocabulary) {
                visit(0, 0, emptyMap(), emptySet())
                results
            }
            recovered.forEach { options ->
                val question = Question(stem, options)
                layouts += question to score
                best = score
            }
        }
        return layouts.filter { it.second == best }.map { it.first }.distinct()
    }

    private fun stored(candidate: JSONObject): Question? {
        val embedded = parse(normalize(candidate.optString("stem"))) ?: return null
        val pairs = candidate.optJSONArray("options").objects().map {
            normalize(it.optString("label")).trim().uppercase(Locale.ROOT).trimEnd('.', '、', ':', ')') to normalize(it.optString("text")).trim()
        }
        if (pairs.isEmpty()) return embedded
        if (pairs.size < 2 || pairs.any { !Regex("[A-Z]").matches(it.first) || it.second.isBlank() } ||
            pairs.map { it.first }.distinct().size != pairs.size || pairs.map { optionKey(it.second) }.distinct().size != pairs.size) return null
        val options = pairs.toMap()
        if (embedded.options.isNotEmpty() && embedded.options.mapValues { optionKey(it.value) } != options.mapValues { optionKey(it.value) }) return null
        return Question(embedded.stem, options)
    }

    private fun sameStem(left: String, right: String): Boolean = left == right ||
        BankTextSimilarity.contentKey(left) == BankTextSimilarity.contentKey(right) && BankTextSimilarity.sameFacts(left, right)
    private fun sameStem(left: Question, right: Question): Boolean = left.stem == right.stem ||
        left.stemText.content == right.stemText.content && left.stemText.facts == right.stemText.facts
    private fun sameQuestion(current: Question, stored: Question): Boolean = sameStem(current, stored) && current.optionSet == stored.optionSet

    private fun truth(raw: String): String? = when (key(raw).lowercase(Locale.ROOT)) {
        "正确", "对", "√", "true" -> "对"
        "错误", "错", "×", "false" -> "错"
        else -> null
    }
    private fun judgmentOptions(question: Question) = question.options.size == 2 &&
        question.options.values.map(::truth).toSet() == setOf("对", "错")

    private fun sameJudgment(current: Question, saved: Question, item: JSONObject): Boolean =
        sameStem(current, saved) &&
            (judgmentOptions(current) || current.options.isEmpty()) &&
            (judgmentOptions(saved) || saved.options.isEmpty() &&
                (item.optString("question_type") == "判断题" || truth(item.optString("reference_answer")) != null))

    /** Import diagnostics use the same parser and remapper as actual answering. */
    fun audit(rows: List<JSONObject>): JSONObject {
        val parsed = rows.map(::stored)
        val warnings = JSONArray()
        var missing = 0
        var invalid = 0
        rows.forEachIndexed { index, row ->
            val saved = parsed[index]
            if (row.optString("reference_answer").isBlank()) missing++
            else if (saved == null || remap(saved, saved, row.optString("reference_answer")) == null) {
                invalid++
                if (warnings.length() < 50) warnings.put("第 ${index + 1} 条无法用于完整题库匹配，请核对选项标签、重复选项及答案格式。")
            }
        }
        val groups = rows.indices.filter { parsed[it] != null }.groupBy { index ->
            val saved = parsed[index]!!
            BankTextSimilarity.contentKey(saved.stem) to saved.options.values.map(::optionKey).sorted()
        }.values.filter { it.size > 1 }
        var conflicts = 0
        val conflictingRows = mutableSetOf<Int>()
        groups.forEach { indices ->
            val target = parsed[indices.first()]!!
            val answers = indices.mapNotNull { remap(target, parsed[it]!!, rows[it].optString("reference_answer")) }.distinct()
            if (answers.size > 1) {
                conflicts++
                conflictingRows.addAll(indices)
                if (warnings.length() < 50) warnings.put("第 ${indices.take(8).joinToString("、") { (it + 1).toString() }} 条为同题但答案冲突，请核对后导入。")
            }
        }
        if (missing > 0) warnings.put("$missing 条题目缺少答案，完整命中时会提示核对。")
        return JSONObject().put("total", rows.size).put("missing_answers", missing).put("unusable_answers", invalid)
            .put("usable_answers", rows.indices.count { index -> index !in conflictingRows && parsed[index]?.let { remap(it, it, rows[index].optString("reference_answer")) != null } == true })
            .put("model_answers", rows.count { it.optString("answer_origin") == "model" })
            .put("duplicate_groups", groups.size).put("conflict_groups", conflicts).put("warnings", warnings)
    }

    internal fun canonicalAnswer(item: JSONObject): String? = stored(item)?.let { remap(it, it, item.optString("reference_answer")) }

    private fun remap(current: Question, stored: Question, rawAnswer: String): String? {
        val answer = normalize(rawAnswer).trim().replace(answerPrefix, "").trim()
        if (answer.isBlank() || answer.startsWith("=")) return null
        if ((judgmentOptions(stored) || stored.options.isEmpty()) && (judgmentOptions(current) || current.options.isEmpty())) {
            val value = truth(answer) ?: stored.options[answer.uppercase(Locale.ROOT)]?.let(::truth)
            if (value != null) return if (current.options.isEmpty()) value
                else current.options.entries.singleOrNull { truth(it.value) == value }?.key
        }
        if (stored.options.isEmpty()) {
            // Scored matching may select a bare-answer record for a page with options.
            // Return a current label only when its content is known, never arbitrary prose.
            if (current.options.isNotEmpty()) {
                if (labels.matches(answer) || Regex("[a-z]").matches(answer)) return null
                return current.options.entries.singleOrNull { optionKey(it.value) == optionKey(answer) }?.key
            }
            when (answer.lowercase(Locale.ROOT)) {
                "正确", "对", "√", "true" -> return "对"
                "错误", "错", "×", "false" -> return "错"
            }
            // A bare letter without option content cannot survive a changed option order.
            if (labels.matches(answer) || Regex("[a-z]").matches(answer)) return null
            return answer
        }
        // "A和B" / "以上选项" depend on order, even if all visible texts still match.
        val reordered = current.optionIdentities != stored.optionIdentities
        if (reordered && stored.options.values.any { orderDependent.containsMatchIn(it) }) return null
        val token = "(?:选项)?(?:[A-Z]|\\([A-Z]\\)|\\[[A-Z]\\]|【[A-Z]】)(?:项)?"
        val decoratedPattern = Regex("$token(?:[\\s,，、;/&+和与及]*$token)*[。.]?")
        val literalLabels = decoratedPattern.matches(answer.uppercase(Locale.ROOT))
        val encoded = if (answer.startsWith("[") && !literalLabels) runCatching {
            val array = JSONArray(answer)
            if ((0 until array.length()).any { array.opt(it) !is String }) null else array.strings().joinToString(",")
        }.getOrNull() ?: return null else answer
        val upper = encoded.uppercase(Locale.ROOT)
        val decorated = decoratedPattern.matches(upper)
        val labelText = if (decorated) Regex("[A-Z]").findAll(upper).joinToString("") { it.value } else upper
        val selectedLabels = Regex("[A-Z]").findAll(upper).map { it.value }.toList()
        val chosen = when {
            labels.matches(labelText) && stored.options.keys.containsAll(selectedLabels) -> selectedLabels
            else -> {
                val annotated = Regex("^([A-Z])[.、:)\\s]+(.+)$", RegexOption.DOT_MATCHES_ALL).matchEntire(encoded)
                if (annotated != null) {
                    val label = annotated.groupValues[1]
                    if (optionKey(stored.options[label].orEmpty()) != optionKey(annotated.groupValues[2])) return null
                    listOf(label)
                } else {
                    val byText = stored.options.filterValues { optionKey(it) == optionKey(answer) }.keys.toList()
                    if (byText.size == 1) byText else {
                        // Only explicit separators; never split arbitrary words into option letters.
                        val groups = if (answer.startsWith("[") && !literalLabels) listOf(JSONArray(answer).strings())
                            else listOf(answer.split(';', '；', '\n'), answer.split('、', '，', ','))
                        val resolved = groups.filter { it.size >= 2 }.mapNotNull { parts ->
                            val found = parts.map { part -> stored.options.entries.singleOrNull { optionKey(it.value) == optionKey(part) }?.key }
                            if (found.any { it == null }) null else found.filterNotNull()
                        }.distinct()
                        resolved.singleOrNull() ?: return null
                    }
                }
            }
        }
        if (chosen.isEmpty() || chosen.distinct().size != chosen.size || !stored.options.keys.containsAll(chosen)) return null
        val currentByText = current.optionIdentities.entries.associate { it.value to it.key }
        return chosen.map { currentByText[stored.optionIdentities.getValue(it)] ?: return null }.sorted().joinToString("")
    }

    private val orderDependent = Regex("(?:以上|上述|下列|前面|前述).*(?:都|均|皆|选项|各项)|(?:选项\\s*[A-Z]|[A-Z]\\s*(?:项|选项|和|与|及|、|,|或|\\+|/|&|\\band\\b|\\bor\\b)\\s*[A-Z])|\\b(?:all|none|both)\\s+(?:of\\s+the\\s+)?(?:above|below)\\b", RegexOption.IGNORE_CASE)

    private fun normalize(raw: String): String = QuestionTextLayout.normalize(raw)
    private fun key(raw: String): String = compactBankText(normalize(raw))
    private fun optionKey(raw: String): String = BankTextSimilarity.layoutKey(key(raw))
    internal fun retrievalStem(raw: String): String = parse(trimChrome(normalize(raw)))?.stem ?: normalize(raw)
    private fun trimChrome(raw: String): String = QuestionTextLayout.trimPage(raw)
    private fun stemKey(raw: String): String {
        return key(QuestionTextLayout.stem(raw)).trimEnd('?', '。')
    }
}
