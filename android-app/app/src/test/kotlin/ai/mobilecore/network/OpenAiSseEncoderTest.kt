package ai.mobilecore.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiSseEncoderTest {
    @Test
    fun encodesOpenAiCompatibleDeltasUsageMetadataAndDoneMarker() {
        val body = OpenAiSseEncoder.encode(
            id = "chatcmpl-local-test",
            created = 1234,
            model = "qwen",
            content = "本地推理 works 🚀",
            finishReason = "stop",
            promptTokens = 7,
            completionTokens = 4,
            totalTokens = 11,
            mobileCoreMetadata = JSONObject().put("backend", "llama.cpp"),
            chunkCodePoints = 3
        )

        val payloads = body.lineSequence()
            .filter { it.startsWith("data: ") && it != "data: [DONE]" }
            .map { JSONObject(it.removePrefix("data: ")) }
            .toList()
        val content = payloads.mapNotNull { payload ->
            payload.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("delta")
                .optString("content")
                .takeIf { it.isNotEmpty() }
        }.joinToString("")

        assertEquals("本地推理 works 🚀", content)
        assertEquals(11, payloads.last().getJSONObject("usage").getInt("total_tokens"))
        assertEquals("llama.cpp", payloads.last().getJSONObject("mobilecore").getString("backend"))
        assertTrue(body.endsWith("data: [DONE]\n\n"))
    }
}
