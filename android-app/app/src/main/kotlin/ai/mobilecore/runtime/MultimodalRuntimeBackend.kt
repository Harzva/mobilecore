package ai.mobilecore.runtime

/** Optional runtime capability used by the localhost OpenAI-compatible API. */
interface MultimodalRuntimeBackend {
    fun mediaChat(
        modelId: String,
        mediaPath: String,
        mediaType: String,
        prompt: String,
        maxTokens: Int,
    ): ChatResult
}

/** Carries only a stable failure code across the runtime/API trust boundary. */
class MultimodalRuntimeException(
    val failureCode: String,
) : RuntimeException(failureCode)
