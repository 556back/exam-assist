package cn.examassist.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** Real SQLite -> shared solver -> serialized AI request -> parsed answer, for text and actual Chinese OCR. */
internal object BankAiFallbackCheck {
    fun run(base: Context): List<String> {
        TestAiTransport.install()
        val databaseName = "bank-ai-check-${UUID.randomUUID()}.db"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = base.getDatabasePath(databaseName)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        }
        val banks = BankStore(context)
        val config = TestAiTransport.config
        val passed = mutableListOf<String>()
        val unknown = "水的化学式是什么？\nA. 水\nB. 氧气\nC. 二氧化碳"
        fun solve(question: String, settings: AiConfig = config) = runBlocking {
            QuestionSolver.solveText(settings, question, banks) { _, _ -> }
        }
        fun expectAi(question: String, settings: AiConfig = config) {
            val before = TestAiTransport.requests.size
            val result = solve(question, settings)
            check(result.getString("answer") == "A")
            check(result.getString("answer_source").startsWith("ai_"))
            check(TestAiTransport.requests.size == before + 1)
            val request = TestAiTransport.requests.last()
            val material = org.json.JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
            check(material.getString("current_question") == question)
        }
        try {
            expectAi(unknown)
            passed += "no enabled bank falls back to exactly one AI request containing the current question"
            val csv = "题干,A,B,C,答案\n太阳从哪个方向升起？,东,西,南,A\n二加二等于多少？,四,五,,\n人体通常有几只眼睛？,一,二,,A\n人体通常有几只眼睛？,一,二,,B\n"
            // Missing answers and conflicting records remain importable with diagnostics.
            banks.importBank(BankImporter.preview(csv.toByteArray(), "fallback.csv"))
            val hit = "太阳从哪个方向升起？\nA. 西\nB. 东\nC. 南"
            val beforeHit = TestAiTransport.requests.size
            val result = solve(hit, config.copy(apiKey = ""))
            check(result.getString("answer") == "B" && result.getString("answer_source") == "local_bank")
            check(TestAiTransport.requests.size == beforeHit)
            passed += "usable bank answer wins, remaps reordered options, and never validates or requests AI"
            expectAi(unknown)
            passed += "below-threshold question calls AI once instead of stopping at the bank miss"
            val missing = "二加二等于多少？\nA. 四\nB. 五"
            val conflict = "人体通常有几只眼睛？\nA. 一\nB. 二"
            val unmapped = "太阳从哪个方向升起？\nA. 北\nB. 西\nC. 南"
            for (question in listOf(missing, conflict, unmapped)) {
                val settings = config.copy(bankMatchThreshold = 80)
                check(banks.lookup(question, AnswerFormat.LABELS, 80) is LocalBankMatching.Lookup.Blocked)
                expectAi(question, settings)
            }
            passed += "missing, conflicting and unmappable bank answers each call AI once with the current options"
            val beforeLocal = TestAiTransport.requests.size
            for (question in listOf(unknown, missing, conflict, unmapped)) {
                check(runCatching { solve(question, config.copy(aiEnabled = false, apiKey = "", bankMatchThreshold = 80)) }
                    .exceptionOrNull() is ApiException)
            }
            check(TestAiTransport.requests.size == beforeLocal)
            check(solve(hit, config.copy(aiEnabled = false, apiKey = "")).getString("answer") == "B")
            passed += "AI disabled: unusable bank answers report errors, usable answers work, zero network requests"
            val bitmap = Bitmap.createBitmap(1080, 1600, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 52f }
                conflict.lines().forEachIndexed { index, line -> canvas.drawText(line, 64f, 320f + index * 100f, paint) }
                val before = TestAiTransport.requests.size
                val stages = mutableListOf<String>()
                val answer = runBlocking { QuestionSolver.solve(config, bitmap, banks) { stage, _ -> stages += stage } }
                check(answer.getString("answer") == "A" && answer.getString("answer_source").startsWith("ai_"))
                check(TestAiTransport.requests.size == before + 1)
                check(stages.containsAll(listOf("正在识别题目", "正在匹配本地题库", "AI 正在解答")))
                check(!bitmap.isRecycled)
                passed += "real Chinese OCR of a conflicting bank question continues to one AI request and answer"
            } finally { bitmap.recycle() }
            TestAiTransport.status = 401
            check(runCatching { solve(unknown) }.exceptionOrNull()?.message.orEmpty().contains("Key 无效"))
            TestAiTransport.status = 200
            TestAiTransport.answer = ""
            check(runCatching { solve(unknown) }.exceptionOrNull()?.message.orEmpty().contains("无法确定答案"))
            passed += "AI rejection and empty answer preserve actionable errors after fallback"
            return passed
        } finally {
            banks.close()
            base.deleteDatabase(databaseName)
        }
    }
}
