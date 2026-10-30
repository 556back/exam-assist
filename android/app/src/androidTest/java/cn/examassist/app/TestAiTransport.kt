package cn.examassist.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.Collections

/** Test APK only: exercise the real request serializer/parser without credentials or a remote service. */
internal object TestAiTransport {
    val requests: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    var answer = "A"
    var status = 200
    val config = AiConfig(provider = "custom", model = "test-text", apiKey = "test-only-key",
        baseUrl = "https://answer-test.invalid/v1", imageInput = false)

    fun install() {
        URL.setURLStreamHandlerFactory { protocol ->
            if (protocol != "https") null else object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection {
                    check(url.host == "answer-test.invalid") { "Unexpected network destination during test" }
                    return object : HttpURLConnection(url) {
                        private val sent = ByteArrayOutputStream()
                        override fun connect() = Unit
                        override fun disconnect() = Unit
                        override fun usingProxy() = false
                        override fun getOutputStream() = sent
                        override fun getResponseCode() = status
                        private fun response(): ByteArrayInputStream {
                            check(requestMethod == "POST")
                            check(getRequestProperty("Authorization") == "Bearer test-only-key")
                            requests += JSONObject(sent.toString("UTF-8"))
                            val body = JSONObject().put("choices", JSONArray().put(JSONObject()
                                .put("finish_reason", "stop")
                                .put("message", JSONObject().put("content", JSONObject().put("answer", answer).toString()))))
                            return ByteArrayInputStream(body.toString().toByteArray(Charsets.UTF_8))
                        }
                        override fun getInputStream() = response()
                        override fun getErrorStream() = response()
                    }
                }
            }
        }
    }
}
