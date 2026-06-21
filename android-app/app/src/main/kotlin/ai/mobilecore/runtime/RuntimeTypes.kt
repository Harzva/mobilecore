package ai.mobilecore.runtime

data class RuntimeModel(
    val id: String,
    val path: String,
    val format: String,
    val backend: String,
    val quantization: String,
    val contextLength: Int,
    val sizeBytes: Long,
    val loaded: Boolean,
    val architecture: String = "unknown",
    val parameterCountB: Double? = null,
    val parameterLabel: String? = null,
    val metadataSource: String = "filename"
)

data class ChatMessage(
    val role: String,
    val content: String
)

data class LoadOptions(
    val contextLength: Int = 4096,
    val threads: Int = 4,
    val gpuLayers: Int = 0
)

data class LoadResult(
    val ok: Boolean,
    val modelId: String,
    val loadTimeMs: Long,
    val memoryUsedMb: Long
)

data class ChatOptions(
    val model: String,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val stream: Boolean = false
)

data class ChatToken(
    val index: Int,
    val content: String
)

data class ChatResult(
    val model: String,
    val message: String,
    val finishReason: String = "stop",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val promptEvalMs: Int = 0,
    val firstTokenMs: Int = 0,
    val decodeMs: Int = 0,
    val totalMs: Int = 0,
    val decodeTokensPerSecond: Double = 0.0,
    val memoryPeakMb: Int = 0
)

data class RuntimeMetrics(
    val activeModel: String?,
    val backend: String,
    val promptEvalMs: Int = 0,
    val firstTokenMs: Int = 0,
    val decodeMs: Int = 0,
    val totalMs: Int = 0,
    val decodeTokensPerSecond: Double = 0.0,
    val memoryPeakMb: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)

data class ModelBenchmark(
    val modelId: String,
    val samples: Int,
    val lastDecodeTokensPerSecond: Double,
    val averageDecodeTokensPerSecond: Double,
    val lastFirstTokenMs: Int,
    val averageFirstTokenMs: Int,
    val lastPromptEvalMs: Int,
    val lastDecodeMs: Int,
    val lastTotalMs: Int,
    val memoryPeakMb: Int,
    val updatedAtMs: Long
)

enum class RecommendationFit {
    PERFECT,
    GOOD,
    MARGINAL,
    TOO_TIGHT
}

enum class RecommendationPreferenceMode(val apiName: String) {
    SPEED("speed"),
    STABILITY("stability"),
    SMALL_MODEL("small")
}

data class RecommendationScoringConfig(
    val mode: RecommendationPreferenceMode,
    val fitWeight: Double,
    val speedWeight: Double,
    val sizeWeight: Double,
    val measuredSpeedWeight: Double,
    val baseTokensPerSecond: Double,
    val targetTokensPerSecond: Double,
    val memoryPressurePenalty: Double
) {
    companion object {
        fun fromModeName(value: String?): RecommendationScoringConfig {
            return when (value?.lowercase()) {
                RecommendationPreferenceMode.SPEED.apiName -> speedFirst()
                RecommendationPreferenceMode.SMALL_MODEL.apiName,
                "small_model",
                "tiny" -> smallModelFirst()
                else -> stabilityFirst()
            }
        }

        fun speedFirst() = RecommendationScoringConfig(
            mode = RecommendationPreferenceMode.SPEED,
            fitWeight = 0.30,
            speedWeight = 0.46,
            sizeWeight = 0.10,
            measuredSpeedWeight = 0.14,
            baseTokensPerSecond = 6.0,
            targetTokensPerSecond = 8.0,
            memoryPressurePenalty = 14.0
        )

        fun stabilityFirst() = RecommendationScoringConfig(
            mode = RecommendationPreferenceMode.STABILITY,
            fitWeight = 0.56,
            speedWeight = 0.18,
            sizeWeight = 0.16,
            measuredSpeedWeight = 0.10,
            baseTokensPerSecond = 4.5,
            targetTokensPerSecond = 5.0,
            memoryPressurePenalty = 22.0
        )

        fun smallModelFirst() = RecommendationScoringConfig(
            mode = RecommendationPreferenceMode.SMALL_MODEL,
            fitWeight = 0.34,
            speedWeight = 0.16,
            sizeWeight = 0.40,
            measuredSpeedWeight = 0.10,
            baseTokensPerSecond = 4.2,
            targetTokensPerSecond = 4.0,
            memoryPressurePenalty = 10.0
        )
    }
}

data class DeviceProfile(
    val device: String,
    val manufacturer: String,
    val model: String,
    val abi: String,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val lowRamDevice: Boolean,
    val coreCount: Int,
    val backend: String,
    val internalStorageFreeMb: Long,
    val externalStorageFreeMb: Long
)

data class DeviceRecommendation(
    val modelId: String,
    val path: String,
    val sizeBytes: Long,
    val estimatedMemoryMb: Int,
    val fit: RecommendationFit,
    val score: Double,
    val expectedTokensPerSecond: Double,
    val reasons: List<String>,
    val loaded: Boolean,
    val benchmark: ModelBenchmark? = null
)

data class BackendInfo(
    val id: String,
    val platform: String,
    val engine: String,
    val modelFormats: List<String>,
    val acceleration: List<String>,
    val status: String
)

interface RuntimeBackend {
    fun backendInfo(): BackendInfo
    fun loadModel(modelPath: String, options: LoadOptions = LoadOptions()): LoadResult
    fun unloadModel(): Boolean
    fun isModelLoaded(): Boolean
    fun chat(messages: List<ChatMessage>, options: ChatOptions): ChatResult
    fun streamChat(messages: List<ChatMessage>, options: ChatOptions): Sequence<ChatToken>
    fun metrics(): RuntimeMetrics
}
