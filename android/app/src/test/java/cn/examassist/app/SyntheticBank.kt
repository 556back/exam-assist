package cn.examassist.app

/** Synthetic regression inputs only; never packaged with the application. */
internal object SyntheticBank {
    fun preview() = BankTemplate.normalize(BankImporter.preview("""
        [{"stem":"1加1等于多少？","options":["1","2","3"],"answer":"B","source_text":"1加1等于多少？ 答案：B"},
         {"stem":"哪些数字是偶数？","options":["1","2","3","4"],"answer":"BD","source_text":"哪些数字是偶数？ 答案：BD"},
         {"stem":"2大于1。","answer":"对","source_text":"2大于1。 答案：对"},
         {"stem":"一周有多少天？","answer":"7","source_text":"一周有多少天？ 答案：7"}]
    """.trimIndent().toByteArray(), "regression.json"))
}
