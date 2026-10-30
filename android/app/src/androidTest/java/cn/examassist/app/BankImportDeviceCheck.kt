package cn.examassist.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Synthetic import data and a private, disposable Android SQLite database. */
internal object BankImportDeviceCheck {
    fun run(instrumentation: Instrumentation): List<String> {
        val passed = mutableListOf<String>()
        val context = IsolatedBankContext(instrumentation.targetContext)
        var store: BankStore? = null
        val bytes = """[{"stem":"1加1等于多少？","options":["1","2","3"],"answer":"B"}]""".toByteArray()
        val preview = BankTemplate.normalize(BankImporter.preview(bytes, "regression.json"))
        try {
            store = BankStore(context)
            check(store.list().isEmpty()) { "A new installation must have no question banks." }
            store.close()
            store = BankStore(context)
            check(store.list().isEmpty()) { "Reopening an empty database must not seed question banks." }
            passed += "new database and reopen both contain no sample banks"
            val automatic = store.importBank(JSONObject(preview.toString()), "bundled-fire-safety-0924-v1")
            val manual = store.importBank(JSONObject(preview.toString()), "user-import-with-same-name")
            store.setEnabled(manual.id, false)
            store.close()
            context.openOrCreateDatabase("question-banks.db", Context.MODE_PRIVATE, null).use { it.version = 1 }
            store = BankStore(context)
            check(store.list().single() == manual.copy(enabled = false))
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM questions WHERE bank_id=?", arrayOf(automatic.id)).use {
                check(it.moveToFirst() && it.getInt(0) == 0)
            }
            passed += "migration removes only the former automatic bank and preserves user imports"
            store.setEnabled(manual.id, true)
            val config = AiConfig(aiEnabled = false, apiKey = "", pageTextEnabled = true)
            val result = kotlinx.coroutines.runBlocking {
                QuestionSolver.solveText(config, "1加1等于多少？\nA.3\nB.1\nC.2", store) { _, _ -> }
            }
            check(result.getString("answer") == "C")
            check(result.getString("answer_source") == "local_bank")
            passed += "real SQLite offline lookup remaps reordered options without AI credentials"
            store.delete(manual.id)
            check(store.list().isEmpty())
            passed += "deleting the imported bank leaves an empty database"
        } finally {
            store?.close()
            context.cleanup()
        }
        return passed
    }

    private class IsolatedBankContext(base: Context) : ContextWrapper(base) {
        private val prefix = "bank-device-check-${UUID.randomUUID()}"
        private val databaseName = "$prefix.db"
        private val preferencesName = "${prefix}_bundled_banks"

        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File {
            require(name == "question-banks.db") { "Unexpected database name: $name" }
            return baseContext.getDatabasePath(databaseName)
        }

        // ContextWrapper delegates these calls directly to its base unless both are overridden.
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)

        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            require(name == "bundled_banks") { "Unexpected preference name: $name" }
            return baseContext.getSharedPreferences(preferencesName, mode)
        }

        fun cleanup() {
            baseContext.deleteDatabase(databaseName)
            baseContext.deleteSharedPreferences(preferencesName)
        }
    }
}
