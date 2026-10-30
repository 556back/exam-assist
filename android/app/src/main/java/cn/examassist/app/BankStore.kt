package cn.examassist.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** All question banks stay in the app's private database, including their enabled state. */
class BankStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "question-banks.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE banks(id TEXT PRIMARY KEY, name TEXT NOT NULL, question_count INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE questions(id INTEGER PRIMARY KEY AUTOINCREMENT, bank_id TEXT NOT NULL REFERENCES banks(id) ON DELETE CASCADE, stem TEXT NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE INDEX questions_bank ON questions(bank_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Only this fixed ID was automatically installed. User imports have independent IDs,
        // even when their names/content match the former bundled bank. SQLiteOpenHelper wraps
        // this version migration in a transaction; ON DELETE CASCADE removes its questions.
        if (oldVersion < 2) db.delete("banks", "id=?", arrayOf("bundled-fire-safety-0924-v1"))
    }

    @Synchronized fun list(): List<Bank> = readableDatabase.rawQuery(
        "SELECT id,name,question_count,enabled FROM banks ORDER BY created_at DESC,id", null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(Bank(cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getInt(3) != 0))
        }
    }

    @Synchronized fun importBank(preview: JSONObject, id: String = UUID.randomUUID().toString()): Bank {
        BankTemplate.normalize(preview)
        val name = preview.optString("name").trim()
        require(name.isNotBlank() && name.length <= 100) { "题库名称需要 1 到 100 个字符。" }
        val questions = preview.optJSONArray("questions") ?: throw IllegalArgumentException("题库没有题目。")
        require(questions.length() in 1..BankImporter.MAX_QUESTIONS) { "单个题库需要 1 到 5000 条题目。" }
        // Validate the preview again so a failed import never leaves a partially saved bank.
        for (index in 0 until questions.length()) BankImporter.validateQuestion(questions.getJSONObject(index), index + 1)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val enabled = enabledCount(db) + questions.length() <= BankImporter.MAX_QUESTIONS
            val bank = Bank(id, name, questions.length(), enabled)
            db.insertOrThrow("banks", null, ContentValues().apply {
                put("id", bank.id); put("name", name); put("question_count", bank.questionCount)
                put("enabled", if (enabled) 1 else 0); put("created_at", System.currentTimeMillis())
            })
            val insert = db.compileStatement("INSERT INTO questions(bank_id,stem,data) VALUES(?,?,?)")
            insert.use {
                for (index in 0 until questions.length()) {
                    val question = questions.getJSONObject(index)
                    insert.bindString(1, bank.id); insert.bindString(2, question.getString("stem"))
                    insert.bindString(3, question.toString()); insert.executeInsert()
                }
            }
            db.setTransactionSuccessful()
            return bank
        } finally { db.endTransaction() }
    }

    @Synchronized fun setEnabled(id: String, enabled: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val count = db.rawQuery("SELECT question_count,enabled FROM banks WHERE id=?", arrayOf(id)).use {
                require(it.moveToFirst()) { "题库不存在或已经删除。" }
                if (it.getInt(1) != 0) 0 else it.getInt(0)
            }
            require(!enabled || enabledCount(db) + count <= BankImporter.MAX_QUESTIONS) {
                "启用题库总计最多 5000 条题目，请先停用其他题库。"
            }
            db.update("banks", ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun delete(id: String) {
        writableDatabase.delete("banks", "id=?", arrayOf(id))
    }

    @Synchronized fun references(question: String): JSONArray {
        if (question.isBlank()) return JSONArray()
        val current = BankMatching.prepare(question)
        val best = mutableListOf<JSONObject>()
        // ponytail: bounded scan of 5000 enabled questions; add an index after measured growth.
        readableDatabase.rawQuery(
            "SELECT q.id,q.bank_id,q.stem,q.data,b.name FROM questions q JOIN banks b ON b.id=q.bank_id WHERE b.enabled=1 ORDER BY q.id", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val score = BankMatching.score(current, BankMatching.prepare(cursor.getString(2)))
                if (score < 0.32 || (best.size == 5 && score <= best.last().getDouble("score"))) continue
                val item = JSONObject(cursor.getString(3)).put("bank_id", cursor.getString(1))
                    .put("question_id", cursor.getLong(0).toString()).put("bank_name", cursor.getString(4))
                    .put("score", score)
                best.add(item)
                best.sortByDescending { it.getDouble("score") }
                if (best.size > 5) best.removeAt(5)
            }
        }
        return JSONArray(best)
    }

    @Synchronized fun localAnswer(question: String, format: AnswerFormat = AnswerFormat.LABELS,
                                  threshold: Int = BankMatchSettings.DEFAULT_THRESHOLD): JSONObject {
        return LocalBankMatching.answer(question, enabledQuestions(), format, threshold)
    }

    @Synchronized internal fun lookup(question: String, format: AnswerFormat,
                                     threshold: Int = BankMatchSettings.DEFAULT_THRESHOLD): LocalBankMatching.Lookup =
        LocalBankMatching.lookup(question, enabledQuestions(), format, threshold) { AppSession.bankDiagnostic.value = it }

    private fun enabledQuestions(): List<JSONObject> {
        val candidates = readableDatabase.rawQuery(
            "SELECT q.id,q.bank_id,q.data,b.name FROM questions q JOIN banks b ON b.id=q.bank_id WHERE b.enabled=1 ORDER BY q.id", null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(JSONObject(cursor.getString(2))
                    .put("question_id", cursor.getLong(0).toString()).put("bank_id", cursor.getString(1))
                    .put("bank_name", cursor.getString(3)))
            }
        }
        return candidates
    }

    private fun enabledCount(db: SQLiteDatabase): Int = db.rawQuery(
        "SELECT COALESCE(SUM(question_count),0) FROM banks WHERE enabled=1", null
    ).use { it.moveToFirst(); it.getInt(0) }
}

/** Linear-time text overlap; reference matches are always checked by the AI. */
internal object BankMatching {
    data class Text(val value: String, val pairs: Set<String>, val numbers: List<String>, val negative: Boolean)
    private val number = Regex("[+-]?\\d+(?:\\.\\d+)?")
    private val negative = Regex("不|错误|除外|\\b(?:not|except|incorrect|false)\\b", RegexOption.IGNORE_CASE)

    fun prepare(raw: String): Text {
        val stem = LocalBankMatching.retrievalStem(raw).lowercase(Locale.ROOT)
        val value = stem.filterNot { it.isWhitespace() }
        return Text(value, value.windowed(2).toSet(), number.findAll(stem).map { it.value }.toList(), negative.containsMatchIn(stem))
    }

    fun score(current: Text, candidate: Text): Double {
        if (candidate.value.isEmpty() || current.value.isEmpty()) return 0.0
        var score = when {
            current.value.contains(candidate.value) -> 1.0
            current.pairs.isEmpty() || candidate.pairs.isEmpty() -> 0.0
            else -> 2.0 * candidate.pairs.count { it in current.pairs } / (current.pairs.size + candidate.pairs.size)
        }
        if (current.numbers != candidate.numbers || current.negative != candidate.negative) score *= 0.5
        return score
    }
}
