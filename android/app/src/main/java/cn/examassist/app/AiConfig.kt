package cn.examassist.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AiProvider(val id: String, val title: String, val model: String, val baseUrl: String)

enum class AnswerFormat(val title: String) {
    LABELS("选项编号（如 A、ABCD）"), CONTENT("答案内容（选项原文）")
}

val AiProviders = listOf(
    AiProvider("deepseek", "DeepSeek", "deepseek-flash", "https://api.deepseek.com"),
    AiProvider("qwen", "通义千问", "qwen-plus", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    AiProvider("custom", "自定义", "", "")
)

data class AiConfig(
    val provider: String = "deepseek",
    val model: String = "deepseek-flash",
    val apiKey: String = "",
    val baseUrl: String = "",
    val imageInput: Boolean? = null,
    val ocrEnabled: Boolean = true,
    val aiEnabled: Boolean = true,
    val pageTextEnabled: Boolean = false,
    val autoAnswerEnabled: Boolean = false,
    val answerFormat: AnswerFormat = AnswerFormat.LABELS,
    val bankMatchThreshold: Int = BankMatchSettings.DEFAULT_THRESHOLD
) {
    val usesPageText: Boolean get() = pageTextEnabled || autoAnswerEnabled
    val endpoint: String
        get() {
            val base = (AiProviders.firstOrNull { it.id == provider && it.id != "custom" }?.baseUrl
                ?: baseUrl).trim().trimEnd('/')
            return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }
    val supportsImages: Boolean
        get() {
            imageInput?.let { return it }
            val name = model.trim().lowercase(Locale.ROOT)
            return name == "deepseek-flash" || name == "deepseek-v4-flash-vision-exp" ||
                name.contains("-vl") || Regex("^qwen3\\.[5-8](?:-|$)").containsMatchIn(name) ||
                name.startsWith("gpt-4o") || name.startsWith("gpt-4.1") || name.startsWith("gpt-5")
        }
    val usesOcr: Boolean get() = !aiEnabled || ocrEnabled || !supportsImages
    override fun toString() = "AiConfig(provider=$provider, model=$model, apiKey=<redacted>, baseUrl=$baseUrl, ocrEnabled=$ocrEnabled, aiEnabled=$aiEnabled, pageTextEnabled=$pageTextEnabled)"
}

/** Only ciphertext and its random IV leave Android Keystore; keys are never exported. */
class AiConfigStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("ai_config", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): AiConfig {
        val encrypted = preferences.getString("encrypted", null) ?: return AiConfig()
        try {
            val iv = Base64.decode(preferences.getString("iv", null) ?: error("Missing IV"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
            val config = AiConfig(json.optString("provider", "deepseek"), json.optString("model", "deepseek-flash"),
                json.optString("api_key"), json.optString("base_url"), json.opt("image_input") as? Boolean,
                json.optBoolean("ocr_enabled", true), json.optBoolean("ai_enabled", true), json.optBoolean("page_text_enabled", false),
                json.optBoolean("auto_answer_enabled", false),
                AnswerFormat.entries.firstOrNull { it.name == json.optString("answer_format") } ?: AnswerFormat.LABELS,
                BankMatchSettings.read(json))
            // Removed/unknown presets must never send a saved key to another vendor.
            return if (AiProviders.any { it.id == config.provider }) config
                else config.copy(provider = "deepseek", model = AiProviders.first().model, apiKey = "", baseUrl = "", imageInput = null)
        } catch (e: Exception) {
            throw IllegalStateException("无法读取已保存的 AI 配置，请重新填写并保存。", e)
        }
    }

    @Synchronized
    fun save(config: AiConfig) {
        require(BankMatchSettings.valid(config.bankMatchThreshold)) { "题库匹配阈值必须在 0% 到 100% 之间。" }
        val plain = JSONObject().put("provider", config.provider).put("model", config.model.trim())
            .put("api_key", config.apiKey.trim()).put("base_url", config.baseUrl.trim())
            .put("ocr_enabled", config.ocrEnabled)
            .put("ai_enabled", config.aiEnabled)
            .put("page_text_enabled", config.pageTextEnabled)
            .put("auto_answer_enabled", config.autoAnswerEnabled)
            .put("answer_format", config.answerFormat.name)
            .put(BankMatchSettings.JSON_KEY, config.bankMatchThreshold)
            .apply { config.imageInput?.let { put("image_input", it) } }.toString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) {
            "无法保存 AI 配置，请检查手机存储空间。"
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "exam_assist_ai_config_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
