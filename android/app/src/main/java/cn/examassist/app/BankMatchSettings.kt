package cn.examassist.app

import org.json.JSONObject
import java.util.Locale

internal object BankMatchSettings {
    const val DEFAULT_THRESHOLD = 85
    const val JSON_KEY = "bank_match_threshold"
    fun valid(value: Int) = value in 0..100
    fun read(json: JSONObject): Int {
        val value = json.opt(JSON_KEY) as? Number ?: return DEFAULT_THRESHOLD
        val threshold = value.toDouble()
        return if (threshold.isFinite() && threshold in 0.0..100.0 && threshold == threshold.toInt().toDouble())
            threshold.toInt() else DEFAULT_THRESHOLD
    }
    fun percent(score: Double): String = String.format(Locale.ROOT, "%.1f%%", score * 100)
}
