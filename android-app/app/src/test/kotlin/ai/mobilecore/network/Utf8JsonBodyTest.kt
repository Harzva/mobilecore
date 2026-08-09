package ai.mobilecore.network

import fi.iki.elonen.NanoHTTPD.IHTTPSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy

class Utf8JsonBodyTest {
    @Test
    fun applicationJsonWithoutCharsetUsesUtf8Contract() {
        val expected = "你好，MobileCore 👋🏽 — café"
        val wireBytes = JSONObject()
            .put("messages", expected)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val decoded = Utf8JsonBody.decode(wireBytes)

        assertTrue(Utf8JsonBody.handles("application/json"))
        assertTrue(Utf8JsonBody.handles("application/vnd.openai+json"))
        assertEquals(expected, JSONObject(decoded).getString("messages"))
        assertFalse(decoded.contains('\uFFFD'))
    }

    @Test
    fun utf8BomIsAcceptedWithoutLeakingIntoJsonParser() {
        val json = "{\"content\":\"中文🚀\"}".toByteArray(Charsets.UTF_8)
        val decoded = Utf8JsonBody.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + json)

        assertEquals("中文🚀", JSONObject(decoded).getString("content"))
    }

    @Test
    fun malformedUtf8IsRejectedInsteadOfSilentlyReplacingBytes() {
        try {
            Utf8JsonBody.decode(byteArrayOf('{'.code.toByte(), 0xE4.toByte(), '}'.code.toByte()))
            fail("expected invalid UTF-8 to be rejected")
        } catch (error: ApiRequestException) {
            assertEquals(ApiFailureCode.INVALID_REQUEST, error.failureCode)
            assertEquals("JSON request body is not valid UTF-8", error.publicMessage)
        }
    }

    @Test
    fun requestReaderPreservesUnicodeWhenApplicationJsonOmitsCharset() {
        val expected = "请用中文思考：移动端推理是否正常？🤔"
        val bytes = "{\"content\":${JSONObject.quote(expected)}}".toByteArray(Charsets.UTF_8)
        val session = fakeSession(
            headers = mapOf(
                "content-type" to "application/json",
                "content-length" to bytes.size.toString(),
            ),
            body = bytes,
        )

        val decoded = Utf8JsonBody.read(session, maxBytes = 4096)

        assertEquals(expected, JSONObject(decoded).getString("content"))
        assertFalse(decoded.contains('\uFFFD'))
    }

    @Test
    fun explicitNonUtf8JsonCharsetIsRejected() {
        val bytes = "{}".toByteArray(Charsets.UTF_8)
        val session = fakeSession(
            headers = mapOf(
                "content-type" to "application/json; charset=US-ASCII",
                "content-length" to bytes.size.toString(),
            ),
            body = bytes,
        )

        try {
            Utf8JsonBody.read(session, maxBytes = 4096)
            fail("expected non-UTF-8 JSON charset to be rejected")
        } catch (error: ApiRequestException) {
            assertEquals(ApiFailureCode.INVALID_REQUEST, error.failureCode)
            assertEquals("JSON requests must use UTF-8", error.publicMessage)
        }
    }

    private fun fakeSession(headers: Map<String, String>, body: ByteArray): IHTTPSession {
        val input = ByteArrayInputStream(body)
        return Proxy.newProxyInstance(
            IHTTPSession::class.java.classLoader,
            arrayOf(IHTTPSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getHeaders" -> headers
                "getInputStream" -> input
                "getUri" -> "/v1/chat/completions"
                "getRemoteIpAddress", "getRemoteHostName" -> "127.0.0.1"
                "getParms", "getParameters" -> emptyMap<String, Any>()
                "getQueryParameterString" -> null
                "execute", "parseBody" -> Unit
                else -> null
            }
        } as IHTTPSession
    }
}
