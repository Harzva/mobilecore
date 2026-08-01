package ai.mobilecore.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RuntimeBridgeMtmdTest {
    @Test
    fun `string media entry point rejects video before JNI`() {
        val response = JSONObject(
            RuntimeBridge.mediaChat(
                modelId = "local-model",
                mediaPath = "/private/media.mp4",
                modality = "video",
                prompt = "describe",
                maxTokens = 16,
            ),
        )

        assertFalse(response.getBoolean("ok"))
        assertEquals("unsupported_modality", response.getString("code"))
        assertFalse(response.toString().contains("/private/media.mp4"))
    }

    @Test
    fun `fallback capability report does not advertise multimodal support`() {
        val response = JSONObject(RuntimeBridge.info())

        assertFalse(response.getBoolean("mtmdLoaded"))
        assertFalse(response.getBoolean("visionInput"))
        assertFalse(response.getBoolean("audioInput"))
        assertEquals(0, response.getInt("audioSampleRate"))
    }
}
