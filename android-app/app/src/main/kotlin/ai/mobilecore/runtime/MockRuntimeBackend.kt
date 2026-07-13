package ai.mobilecore.runtime

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

class MockRuntimeBackend(private val context: Context) : RuntimeBackend {
    private var activeModel: String? = null
    private var lastMetrics: RuntimeMetrics = RuntimeMetrics(
        activeModel = null,
        backend = "android-llama-cpp-stub"
    )

    override fun backendInfo(): BackendInfo {
        if (RuntimeBridge.isLibraryReady()) {
            val info = JSONObject(RuntimeBridge.info())
            return BackendInfo(
                id = info.optString("id", "android-llama-cpp-native-stub"),
                platform = "android",
                engine = info.optString("backend", "llama.cpp"),
                modelFormats = listOf("gguf"),
                acceleration = listOf("cpu"),
                status = info.optString("status", "stub")
            )
        }

        return BackendInfo(
            id = "android-llama-cpp-stub",
            platform = "android",
            engine = "llama.cpp-stub",
            modelFormats = listOf("gguf"),
            acceleration = listOf("cpu"),
            status = "available"
        )
    }

    override fun loadModel(modelPath: String, options: LoadOptions): LoadResult {
        val start = SystemClock.elapsedRealtime()
        val nativeResult = JSONObject(
            RuntimeBridge.loadModel(modelPath, options.contextLength, options.threads)
        )
        val candidateModel = nativeResult.optString(
            "modelId",
            modelPath.substringAfterLast('/')
                .substringBeforeLast(".gguf", missingDelimiterValue = modelPath.substringAfterLast('/'))
        )
        val ok = nativeResult.optBoolean("ok", RuntimeBridge.isLibraryReady())
        if (ok) {
            activeModel = candidateModel
            lastMetrics = lastMetrics.copy(
                activeModel = activeModel,
                backend = backendInfo().id,
                memoryPeakMb = nativeResult.optInt("memoryUsedMb", lastMetrics.memoryPeakMb)
            )
        }
        val elapsed = SystemClock.elapsedRealtime() - start

        return LoadResult(
            ok = ok,
            modelId = if (ok) candidateModel else activeModel ?: candidateModel,
            loadTimeMs = elapsed,
            memoryUsedMb = nativeResult.optLong("memoryUsedMb", 120)
        )
    }

    override fun unloadModel(): Boolean {
        val hadModel = activeModel != null
        RuntimeBridge.unload()
        activeModel = null
        lastMetrics = RuntimeMetrics(activeModel = null, backend = backendInfo().id)
        return hadModel
    }

    override fun isModelLoaded(): Boolean = activeModel != null

    override fun chat(messages: List<ChatMessage>, options: ChatOptions): ChatResult {
        val prompt = messages.joinToString("\n") { "${it.role}: ${it.content}" }
        val startedAt = SystemClock.elapsedRealtime()
        val rawAnswer = if (RuntimeBridge.isLibraryReady()) {
            RuntimeBridge.chat(options.model, prompt, options.maxTokens, options.temperature)
        } else {
            "Mock response: 这是当前 MobileCore 骨架返回的演示文本。你问的是「${messages.lastOrNull()?.content ?: ""}」。"
        }
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).toInt()
        val result = parseNativeChatResult(rawAnswer, prompt, options, elapsed)

        lastMetrics = RuntimeMetrics(
            activeModel = activeModel ?: result.model,
            backend = backendInfo().id,
            promptEvalMs = result.promptEvalMs,
            firstTokenMs = result.firstTokenMs,
            decodeMs = result.decodeMs,
            totalMs = result.totalMs,
            decodeTokensPerSecond = result.decodeTokensPerSecond,
            memoryPeakMb = result.memoryPeakMb,
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens,
            totalTokens = result.totalTokens
        )
        return result
    }

    override fun streamChat(messages: List<ChatMessage>, options: ChatOptions): Sequence<ChatToken> {
        val result = chat(messages, options)
        return result.message.split(" ").withIndex().asSequence().map { (idx, word) ->
            ChatToken(index = idx, content = "$word ")
        }
    }

    override fun metrics(): RuntimeMetrics {
        return lastMetrics.copy(activeModel = activeModel ?: lastMetrics.activeModel, backend = backendInfo().id)
    }

    fun describeModelList(): String = JSONObject().apply {
        put("backend", backendInfo().engine)
        put("modelDir", context.filesDir.absolutePath)
        put("status", if (isModelLoaded()) "loaded" else "idle")
    }.toString()

    private fun parseNativeChatResult(
        rawAnswer: String,
        prompt: String,
        options: ChatOptions,
        elapsedMs: Int
    ): ChatResult {
        val fallbackPromptTokens = prompt.length / 4
        val fallbackCompletionTokens = rawAnswer.length / 4
        if (!rawAnswer.trimStart().startsWith("{")) {
            return ChatResult(
                model = options.model,
                message = rawAnswer,
                finishReason = "stop",
                promptTokens = fallbackPromptTokens,
                completionTokens = fallbackCompletionTokens,
                totalTokens = fallbackPromptTokens + fallbackCompletionTokens,
                totalMs = elapsedMs
            )
        }

        return try {
            val payload = JSONObject(rawAnswer)
            val message = payload.optString("message", rawAnswer)
            val promptTokens = payload.optInt("promptTokens", fallbackPromptTokens)
            val completionTokens = payload.optInt("completionTokens", message.length / 4)
            val decodeMs = payload.optInt("decodeMs", 0)
            val nativeTps = payload.optDouble("decodeTokensPerSecond", 0.0)
            val computedTps = if (nativeTps > 0.0) {
                nativeTps
            } else if (decodeMs > 0) {
                completionTokens * 1000.0 / decodeMs
            } else {
                0.0
            }
            ChatResult(
                model = payload.optString("model", options.model),
                message = message,
                finishReason = if (payload.optBoolean("ok", true)) "stop" else "error",
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = payload.optInt("totalTokens", promptTokens + completionTokens),
                promptEvalMs = payload.optInt("promptEvalMs", 0),
                firstTokenMs = payload.optInt("firstTokenMs", 0),
                decodeMs = decodeMs,
                totalMs = payload.optInt("totalMs", elapsedMs),
                decodeTokensPerSecond = computedTps,
                memoryPeakMb = payload.optInt("memoryPeakMb", lastMetrics.memoryPeakMb)
            )
        } catch (_: Exception) {
            ChatResult(
                model = options.model,
                message = rawAnswer,
                finishReason = "stop",
                promptTokens = fallbackPromptTokens,
                completionTokens = fallbackCompletionTokens,
                totalTokens = fallbackPromptTokens + fallbackCompletionTokens,
                totalMs = elapsedMs
            )
        }
    }
}
