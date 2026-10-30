package cn.examassist.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiRequestTest {
    @Test fun imageOnlyRequestAttachesScreenshotAndUsesShortAnswerBudget() {
        val config = AiConfig(provider = "custom", model = " vision-model ", imageInput = true, ocrEnabled = false)
        val body = Api.buildRequest(config, "", byteArrayOf(1, 2, 3))
        assertEquals("vision-model", body.getString("model"))
        assertEquals(1024, body.getInt("max_tokens"))
        assertFalse(body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).getString("content").contains("不要解析"))
        val content = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("解答截图中的题目。", content.getJSONObject(0).getString("text"))
        assertEquals("data:image/jpeg;base64,AQID", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test fun referenceRequestOmitsExplanationsAndMetadataWithoutChangingLocalReferences() {
        val reference = JSONObject().put("stem", "题干").put("options", JSONArray().put(JSONObject().put("label", "B").put("text", "水")))
            .put("reference_answer", "B").put("explanation", "很长的解析".repeat(1000)).put("source", "教材").put("bank_name", "题库")
        val body = Api.buildRequest(AiConfig(model = "text-model", imageInput = false), "当前题", byteArrayOf(1), JSONArray().put(reference))
        val material = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("当前题", material.getString("current_question"))
        val sent = material.getJSONArray("references").getJSONObject(0)
        assertEquals(3, sent.length())
        assertEquals("题干", sent.getString("stem"))
        assertEquals("B", sent.getString("reference_answer"))
        assertEquals("水", sent.getJSONArray("options").getJSONObject(0).getString("text"))
        assertFalse(sent.has("explanation"))
        assertFalse(sent.has("source"))
        assertTrue(reference.has("explanation"))
    }

    @Test fun disablesSupportedProviderThinkingWithoutAddingVendorFieldsToCustomModels() {
        for (model in listOf("deepseek-flash", "deepseek-v4-pro")) {
            val body = Api.buildRequest(AiConfig(provider = "deepseek", model = model), "题目")
            assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
            assertFalse(body.has("reasoning_effort"))
        }
        for (model in listOf("qwen-plus", "qwen-flash", "qwen3-max", "qwen3.5-plus", "qwen3.8-max")) {
            assertFalse(Api.buildRequest(AiConfig(provider = "qwen", model = model), "题目").getBoolean("enable_thinking"))
        }
        for (config in listOf(AiConfig(provider = "custom", model = "deepseek-flash"),
            AiConfig(provider = "qwen", model = "qwen3-max-thinking"),
            AiConfig(provider = "qwen", model = "qwen3.8-2.4t-a95b"), AiConfig(provider = "qwen", model = "qwen3.7-max-preview"),
            AiConfig(provider = "qwen", model = "qwen3.7-max-2026-05-17"))) {
            val body = Api.buildRequest(config, "题目")
            assertFalse(body.has("thinking"))
            assertFalse(body.has("enable_thinking"))
            assertFalse(body.has("reasoning_effort"))
        }
    }
}
