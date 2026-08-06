package ai.mobilecore.runtime

/** Optional runtime capability used by the localhost OpenAI-compatible API. */
interface MultimodalRuntimeBackend {
    /** Load a projector selected by public model metadata, never by client path. */
    fun loadProjector(
        projectorPath: String,
        projectorId: String,
        threads: Int,
    ): Boolean = false

    fun multimodalStatus(): RuntimeMultimodalStatus = RuntimeMultimodalStatus()

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
