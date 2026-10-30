package cn.examassist.app

import org.junit.Assert.*
import org.junit.Test

class AiConfigTest {
    @Test fun presetsContainOnlyDeepSeekAndQwenAlongsideCustom() {
        assertEquals(listOf("deepseek", "qwen", "custom"), AiProviders.map { it.id })
    }
    @Test fun pageTextRequiresExplicitEnablementAndKeepsApiKeysRedacted() {
        val config = AiConfig(apiKey = "sensitive-page-text-key")
        assertFalse(config.pageTextEnabled)
        val enabled = config.copy(pageTextEnabled = true)
        assertTrue(enabled.pageTextEnabled)
        assertTrue(enabled.copy(ocrEnabled = false, aiEnabled = false).pageTextEnabled)
        assertTrue(enabled.toString().contains("pageTextEnabled=true"))
        assertFalse(enabled.toString().contains("sensitive-page-text-key"))
        assertNull(Api.validateAnswerMode(enabled.copy(apiKey = "", aiEnabled = false)))
    }

    @Test fun localModeNeedsNoCredentialsAndCaptureUsesOcrIndependentlyOfVisionSetting() {
        val config = AiConfig(apiKey = "", ocrEnabled = false, aiEnabled = false)
        assertTrue(config.usesOcr)
        assertNull(Api.validateAnswerMode(config))
        assertNotNull(Api.validate(config)) // Import normalization still requires credentials.
        assertNotNull(Api.validateAnswerMode(config.copy(aiEnabled = true)))
        kotlinx.coroutines.runBlocking {
            try { Api.solve(config, "题目"); fail("Disabled AI must not issue a network request") }
            catch (e: ApiException) { assertTrue(e.message!!.contains("大模型解答已关闭")) }
        }
    }
    @Test fun imageModelsCanSkipOcrButTextModelsAlwaysRecognize() {
        assertTrue(AiConfig().usesOcr)
        assertFalse(AiConfig(ocrEnabled = false).usesOcr)
        assertTrue(AiConfig(provider = "qwen", model = "qwen-plus", ocrEnabled = false).usesOcr)
        val custom = AiConfig(provider = "custom", model = "vision", imageInput = true, ocrEnabled = false)
        assertFalse(custom.usesOcr)
        assertTrue(custom.copy(imageInput = false).usesOcr)
        assertFalse(custom.copy(apiKey = "saved-key").ocrEnabled)
    }
    @Test fun presetEndpointsIgnoreStaleCustomAddress() {
        AiProviders.filter { it.id != "custom" }.forEach {
            val config = AiConfig(provider = it.id, model = it.model, baseUrl = "https://stale.example/v1")
            assertEquals(it.baseUrl + "/chat/completions", config.endpoint)
        }
        assertEquals("https://example.com/v1/chat/completions",
            AiConfig(provider = "custom", baseUrl = " https://example.com/v1/chat/completions/ ").endpoint)
    }
    @Test fun modelCapabilityAndRedactionAreConservative() {
        assertTrue(AiConfig().supportsImages)
        assertFalse(AiConfig(provider = "qwen", model = "qwen-plus").supportsImages)
        assertTrue(AiConfig(provider = "qwen", model = "qwen-vl-plus").supportsImages)
        listOf("qwen3.5-plus", "qwen3.6-plus", "qwen3.7-plus", "qwen3.8-max").forEach {
            assertTrue(AiConfig(provider = "qwen", model = it).supportsImages)
        }
        assertFalse(AiConfig(provider = "custom", model = "unknown-model").supportsImages)
        assertTrue(AiConfig(provider = "custom", model = "unknown-model", imageInput = true).supportsImages)
        assertFalse(AiConfig(imageInput = false).supportsImages)
        assertFalse(AiConfig(apiKey = "sensitive-test-key").toString().contains("sensitive-test-key"))
    }
}
