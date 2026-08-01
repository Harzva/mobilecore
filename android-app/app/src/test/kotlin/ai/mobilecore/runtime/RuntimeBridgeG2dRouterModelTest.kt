package ai.mobilecore.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeBridgeG2dRouterModelTest {
    @Test
    fun `adapter unwraps native chat envelope and uses deterministic decoding`() {
        var captured: List<Any> = emptyList()
        val model = RuntimeBridgeG2dRouterModel("Qwen3-0.8B-Q4") { modelId, prompt, maxTokens, temperature ->
            captured = listOf(modelId, prompt, maxTokens, temperature)
            """{"ok":true,"message":"{\"tool\":\"clip_direct\",\"confidence\":0.9}"}"""
        }

        val output = model.generate("route this", null)

        assertEquals("{\"tool\":\"clip_direct\",\"confidence\":0.9}", output)
        assertEquals(listOf("Qwen3-0.8B-Q4", "route this", 128, 0.0f), captured)
    }

    @Test
    fun `text runtime rejects an image reference instead of pretending to inspect it`() {
        val model = RuntimeBridgeG2dRouterModel("Qwen3-0.8B-Q4") { _, _, _, _ -> "{}" }

        assertThrows(IllegalArgumentException::class.java) {
            model.generate("route this", "/tmp/pet.jpg")
        }
    }
}
