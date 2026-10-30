package cn.examassist.app

import org.junit.Assert.*
import org.junit.Test

class ApiAddressTest {
    private fun validate(address: String) = Api.validate(AiConfig("custom", "my-model", "test-key", address))

    @Test fun presetsUseOfficialEndpointsAndDoNotExposeKeys() {
        AiProviders.filter { it.id != "custom" }.forEach { provider ->
            val config = AiConfig(provider.id, provider.model, "test-secret", "https://untrusted.example")
            assertTrue(config.endpoint.startsWith(provider.baseUrl))
            assertNull(Api.validate(config))
            assertFalse(config.toString().contains("test-secret"))
        }
    }
    @Test fun customAcceptsBaseOrCompleteEndpoint() {
        assertNull(validate("https://example.com/v1"))
        assertNull(validate("https://example.com/v1/chat/completions"))
        assertEquals("https://example.com/v1/chat/completions", AiConfig("custom", "model", "key", "https://example.com/v1/").endpoint)
    }
    @Test fun insecureAndSecretBearingUrlsAreRejected() {
        listOf("http://localhost:8000", "http://example.com", "https://user:secret@example.com", "https://example.com?key=secret", "https://example.com#secret", "ftp://example.com", "", "not a url").forEach { assertNotNull(it, validate(it)) }
    }
    @Test fun missingAndMalformedCredentialsAreRejected() {
        assertNotNull(Api.validate(AiConfig(apiKey = "")))
        assertNotNull(Api.validate(AiConfig(model = "", apiKey = "key")))
        assertNotNull(Api.validate(AiConfig(apiKey = "key\nheader")))
    }
    @Test fun imageSupportFollowsModelFamily() {
        assertTrue(AiConfig("qwen", "qwen-vl-plus").supportsImages)
        assertFalse(AiConfig("qwen", "qwen-plus").supportsImages)
    }
}
