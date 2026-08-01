package ai.mobilecore.runtime

import ai.mobilecore.g2d.G2dRouterModel
import org.json.JSONObject

/** Text-only local Qwen adapter for Agentic G2D branch selection from CLIP statistics. */
class RuntimeBridgeG2dRouterModel(
    private val modelId: String,
    private val maxTokens: Int = 128,
    private val chat: (String, String, Int, Float) -> String = RuntimeBridge::chat,
) : G2dRouterModel {
    init {
        require(modelId.isNotBlank()) { "G2D router model ID must not be blank." }
        require(maxTokens > 0) { "G2D router max tokens must be positive." }
    }

    override fun generate(prompt: String, imageReference: String?): String {
        require(imageReference == null) {
            "RuntimeBridge is text-only; use a multimodal G2dRouterModel for image-aware routing."
        }
        val raw = chat(modelId, prompt, maxTokens, 0.0f)
        val wrapper = runCatching { JSONObject(raw) }.getOrNull()
        return wrapper?.optString("message")?.takeIf { it.isNotBlank() } ?: raw
    }
}
