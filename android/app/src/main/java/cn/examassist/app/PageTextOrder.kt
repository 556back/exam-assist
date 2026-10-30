package cn.examassist.app

/** Associate split letters and text by their row before flattening accessibility/OCR data. */
internal object PageTextOrder {
    data class Piece(val text: String, val bounds: PageBounds? = null)
    private val wordPrefix = Regex("^[A-Za-z][ \\t]+\\S")
    private fun sharesRow(letter: PageBounds?, text: PageBounds?): Boolean {
        if (letter?.usable != true || text?.usable != true) return false
        val overlap = minOf(letter.bottom, text.bottom) - maxOf(letter.top, text.top)
        return text.left >= letter.right && overlap > 0 &&
            overlap * 2 >= minOf(letter.bottom - letter.top, text.bottom - text.top)
    }

    fun order(pieces: List<Piece>, sortByPosition: Boolean = true): List<Piece> {
        val baseline = if (sortByPosition && pieces.all { it.bounds?.usable == true })
            pieces.sortedWith(compareBy<Piece> { it.bounds!!.top }.thenBy { it.bounds!!.left }) else pieces
        val available = baseline.mapNotNull { ChoiceQuestion.optionLabel(it.text) }.toSet()
        val sequence = ('A'..'Z').map { it.toString() }.takeWhile { it in available }.toSet()
        val labels = baseline.indices.filter {
            QuestionTextLayout.isStandaloneLabel(baseline[it].text) && baseline[it].bounds?.usable == true &&
                (ChoiceQuestion.optionLabel(baseline[it].text) in sequence || sequence.isEmpty() &&
                    baseline.indices.any { other -> other != it && sharesRow(baseline[it].bounds, baseline[other].bounds) })
        }
        val groups = mutableMapOf<Int, MutableList<Int>>()
        baseline.forEachIndexed { index, piece ->
            if (piece.bounds?.usable != true) return@forEachIndexed
            if (index in labels) return@forEachIndexed
            // "A 类火灾" or "a list" can be option content after a split letter.
            // A punctuated full option row already owns its label and is kept intact.
            if (ChoiceQuestion.optionLabel(piece.text) != null &&
                !QuestionTextLayout.isStandaloneLabel(piece.text) &&
                !wordPrefix.containsMatchIn(QuestionTextLayout.normalize(piece.text).trimStart())) return@forEachIndexed
            val matches = labels.filter { label -> sharesRow(baseline[label].bounds, piece.bounds) }
            // A tall text container spanning several choices cannot establish a row.
            matches.singleOrNull()?.let { groups.getOrPut(it) { mutableListOf(it) }.add(index) }
        }
        val grouped = groups.values.flatMap { it }.toSet()
        val starts = groups.values.associateBy { it.min() }
        return baseline.indices.flatMap { index ->
            starts[index]?.map { baseline[it] } ?: if (index in grouped) emptyList() else listOf(baseline[index])
        }
    }
}
