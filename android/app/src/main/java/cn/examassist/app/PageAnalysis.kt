package cn.examassist.app

/** Immutable accessibility snapshot, also used by layout compatibility tests. */
internal data class PageNode(
    val target: PageTarget,
    val values: List<String> = emptyList(),
    val selected: Boolean? = null,
    val multiple: Boolean = false,
    val children: List<PageNode> = emptyList()
)

internal object PageAnalysis {
    private data class State(val selected: Boolean, val multiple: Boolean)
    private data class Summary(val pieces: List<PageTextOrder.Piece>, val states: List<State>, val clicks: List<PageTarget>) {
        val values: List<String> get() = pieces.map { it.text }
    }
    private data class Candidate(val target: PageTarget, val values: List<String>, val state: State?, val sourcePath: List<Int>)

    fun inspect(root: PageNode, packageName: String, windowId: Int): AnswerPage {
        val candidates = mutableListOf<Candidate>()
        val anchors = mutableListOf<Candidate>()
        fun visit(node: PageNode): Summary {
            val children = node.children.map(::visit)
            // Keep semantic tree order between rows; repair letters whose sibling text
            // precedes them using the same geometric evidence as the text reader.
            val childPieces = PageTextOrder.order(children.flatMap { it.pieces }, sortByPosition = false)
            val childValues = childPieces.map { it.text }
            val own = node.values.filter(String::isNotBlank).distinctBy(ChoiceQuestion::key)
            // Merged web rows may repeat all child text. Preserve equal text in unrelated rows.
            val ownPieces = own.map { PageTextOrder.Piece(it, node.target.bounds) }
            val pieces = if (own.isEmpty()) childPieces else if (childValues.isEmpty()) ownPieces else {
                if (own.any { duplicatesChildren(it, childValues) }) childPieces
                else ownPieces.filter { piece -> childValues.none { ChoiceQuestion.key(it) == ChoiceQuestion.key(piece.text) } } + childPieces
            }
            val values = pieces.map { it.text }
            val childStates = children.flatMap { it.states }
            val states = if (childStates.isNotEmpty()) childStates else node.selected?.let { listOf(State(it, node.multiple)) }.orEmpty()
            val clicks = if (node.target.clickAction) listOf(node.target) else children.flatMap { it.clicks }
            val action = if (node.target.clickAction) node.target else clicks.singleOrNull()
                ?: node.target.takeIf { it.bounds?.usable == true }
            if (values.isNotEmpty() && action != null) candidates += Candidate(action, values, states.singleOrNull(), node.target.path)
            // A fallback position belongs to the node actually displaying the letter,
            // not to a surrounding container that might also contain navigation buttons.
            if (ChoiceQuestion.optionLabel(own.joinToString("\n")) != null &&
                ChoiceQuestion.optionLabel(values.joinToString("\n")) != null &&
                (node.target.clickAction || node.target.bounds?.usable == true))
                anchors += Candidate(node.target, own, states.singleOrNull(), node.target.path)
            return Summary(pieces, states, clicks)
        }
        val raw = visit(root).values.joinToString("\n")
        // A newly opened WebView can be briefly empty. The controller waits for a stable page
        // after navigation; missing text must not be turned into an immediate terminal error.
        val question = ChoiceQuestion.parse(raw)
        fun unique(matches: List<Candidate>): Candidate? {
            if (matches.isEmpty()) return null
            val actionable = matches.filter { it.target.clickAction }.ifEmpty { matches }
            // Ancestor wrappers refer to one control; unrelated duplicate matches are ambiguous.
            val leaves = actionable.filter { a -> actionable.none { b ->
                b.target.path.size > a.target.path.size && b.target.path.take(a.target.path.size) == a.target.path
            } }.groupBy { it.target.path }
            return leaves.values.singleOrNull()?.let { group ->
                val candidate = group.firstOrNull { it.state != null } ?: group.first()
                // A letter can be a child of a row exposing the selection state. Keep that
                // state even when only the letter itself supplies the gesture location.
                val state = candidate.state ?: matches.filter {
                    candidate.target.path.take(it.sourcePath.size) == it.sourcePath
                }.maxByOrNull { if (it.state != null) it.sourcePath.size else -1 }?.state
                candidate.copy(state = state)
            }
        }
        val options = question?.options?.mapNotNull { (label, content) ->
            // Locate the displayed letter first. Full option text may be split across nodes,
            // decorated with hints, or include footer text in the parsed question.
            val labelled = anchors.filter { ChoiceQuestion.optionLabel(it.values.joinToString("\n")) == label }
            val exact = candidates.filter { matchesOption(it.values.joinToString(""), label, content) }
            val matches = if (labelled.isEmpty()) exact else labelled + exact.filter { row ->
                (row.target.clickAction || row.state != null) && labelled.any { anchor ->
                    anchor.sourcePath.take(row.sourcePath.size) == row.sourcePath &&
                        (row.target.path == row.sourcePath ||
                            anchor.sourcePath.take(row.target.path.size) == row.target.path || row.state != null)
                }
            }
            unique(matches)?.let { PageOption(label, it.target, it.state?.selected == true, it.state != null, it.state?.multiple == true) }
        }.orEmpty()
        val next = unique(candidates.filter { ChoiceQuestion.isNext(it.values.joinToString("")) })?.target
        return AnswerPage(packageName, windowId, raw, question, options, next)
    }

    private fun mergedKey(value: String): String = ChoiceQuestion.key(value)
        .replace(Regex("^[（(\\[]([A-Za-zＡ-Ｚ])[)）\\]]"), "$1")
        .replace(Regex("^([A-Za-zＡ-Ｚ])[.．、:：)）]"), "$1")

    internal fun duplicatesChildren(value: String, children: List<String>): Boolean =
        children.isNotEmpty() && mergedKey(value) == mergedKey(children.joinToString(""))

    private fun matchesOption(value: String, label: String, content: String): Boolean {
        val normalized = ChoiceQuestion.key(value).map { if (it in 'Ａ'..'Ｚ') (it.code - 0xFEE0).toChar() else it }.joinToString("")
        val text = ChoiceQuestion.key(content)
        if (normalized == text) return true
        return listOf(label, label.lowercase()).any { letter ->
            listOf("", ".", "、", ":", "：", ")", "）", "．").any { normalized == "$letter$it$text" } ||
                listOf("($letter)", "（$letter）", "[$letter]").any { normalized == "$it$text" }
        }
    }

    private val descriptionSuffix = Regex("(?:[,，;；\\s]+)(?:按钮|单选按钮|复选框|button|radio button|checkbox|已选中|未选中|已选择|未选择|selected|not selected|checked|not checked|unchecked)$", RegexOption.IGNORE_CASE)
    fun descriptionText(value: String): String {
        var text = value.trim()
        // Android/WebView versions append role and state in different orders.
        while (true) {
            val cleaned = text.replace(descriptionSuffix, "").trimEnd()
            if (cleaned == text) break
            text = cleaned
        }
        return text.takeUnless { describedState(it) != null }.orEmpty()
    }

    fun describedState(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        "已选中", "已选择", "选中", "selected", "checked" -> true
        "未选中", "未选择", "未选", "not selected", "not checked", "unchecked" -> false
        else -> null
    }
}
