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

/** Offline screenshot -> bundled Chinese OCR -> actual SQLite matching, without any AI credentials. */
internal object ScreenshotSolveCheck {
    fun run(base: Context, pageText: String? = null): List<String> {
        val databaseName = "screenshot-check-${UUID.randomUUID()}.db"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = base.getDatabasePath(databaseName)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        }
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val store = BankStore(context)
        try {
            val csv = "题干,A,B,C,D,答案\n太阳从哪个方向升起？,东,西,南,北,A\n"
            store.importBank(BankImporter.preview(csv.toByteArray(Charsets.UTF_8), "screenshot-check.csv"))
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 52f }
            listOf("太阳从哪个方向升起？", "A. 东", "B. 西", "C. 南", "D. 北").forEachIndexed { index, text ->
                canvas.drawText(text, 64f, 320f + index * 100f, paint)
            }
            val stages = mutableListOf<String>()
            val answer = runBlocking {
                val config = AiConfig(aiEnabled = false, apiKey = "", ocrEnabled = false, pageTextEnabled = pageText != null)
                if (pageText == null) QuestionSolver.solve(config, bitmap, store) { stage, _ -> stages += stage }
                else QuestionSolver.solveText(config, pageText, store) { stage, _ -> stages += stage }
            }
            check(answer.getString("answer") == "A") { "Screenshot OCR/local matching returned an unexpected answer." }
            check(answer.getString("answer_source") == "local_bank")
            check(stages == if (pageText == null) listOf("正在识别题目", "正在匹配本地题库") else listOf("正在匹配本地题库"))
            check(!bitmap.isRecycled) { "QuestionSolver recycled its caller's bitmap." }
            return listOf(if (pageText == null) "real screenshot -> Chinese ML Kit OCR -> SQLite local answer A, no Key/network, caller retains bitmap"
                else "protected window text -> SQLite local answer A, no screenshot/OCR/Key/network")
        } finally {
            bitmap.recycle()
            store.close()
            base.deleteDatabase(databaseName)
        }
    }
}
