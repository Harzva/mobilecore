package ai.mobilecore.network

import ai.mobilecore.runtime.BackendInfo
import ai.mobilecore.runtime.ChatMessage
import ai.mobilecore.runtime.ChatOptions
import ai.mobilecore.runtime.DeviceRecommendation
import ai.mobilecore.runtime.LoadOptions
import ai.mobilecore.runtime.ModelBenchmark
import ai.mobilecore.runtime.ModelBenchmarkStore
import ai.mobilecore.runtime.DeviceProbe
import ai.mobilecore.runtime.ModelManager
import ai.mobilecore.runtime.RecommendationScoringConfig
import ai.mobilecore.runtime.RecommendationScoringConfigSource
import ai.mobilecore.runtime.RuntimeBackend
import ai.mobilecore.runtime.RuntimeModel
import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.ResponseException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class LocalApiServer(
    private val backend: RuntimeBackend,
    private val modelManager: ModelManager,
    private val context: Context,
    private val apiKey: String = "local",
    host: String = "127.0.0.1",
    port: Int = 8080
) : NanoHTTPD(host, port) {
    private val deviceProbe = DeviceProbe(context.applicationContext)
    private val scoringConfigSource = RecommendationScoringConfigSource(context.applicationContext)
    private val benchmarkStore = ModelBenchmarkStore(context.applicationContext)

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        return when {
            isHealthRoute(session) && method == Method.GET -> okResponse(buildHealth(backend.backendInfo()))
            isModelsRoute(session) && method == Method.GET -> {
                if (!hasAuth(session)) return unauthorized()
                okResponse(buildModels())
            }

            isChatRoute(session) && method == Method.POST -> {
                if (!hasAuth(session)) return unauthorized()
                onChat(session)
            }

            isMetricsRoute(session) && method == Method.GET -> {
                if (!hasAuth(session)) return unauthorized()
                okResponse(buildMetrics(backend.metrics()))
            }

            isRecommendationRoute(session) && method == Method.GET -> {
                if (!hasAuth(session)) return unauthorized()
                okResponse(buildRecommendations(session))
            }

            isModelLoadRoute(session) && method == Method.POST -> {
                if (!hasAuth(session)) return unauthorized()
                onLoadModel(session)
            }

            isModelDirsRoute(session) && method == Method.GET -> {
                if (!hasAuth(session)) return unauthorized()
                okResponse(buildModelDirs())
            }

            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                JSONObject(mapOf("error" to mapOf("message" to "not found"))).toString()
            )
        }
    }

    private fun isModelsRoute(session: IHTTPSession): Boolean {
        return session.uri == "/v1/models"
    }

    private fun isChatRoute(session: IHTTPSession): Boolean {
        return session.uri == "/v1/chat/completions"
    }

    private fun isHealthRoute(session: IHTTPSession): Boolean {
        return session.uri == "/health"
    }

    private fun isMetricsRoute(session: IHTTPSession): Boolean {
        return session.uri == "/metrics"
    }

    private fun isRecommendationRoute(session: IHTTPSession): Boolean {
        return session.uri == "/v1/recommendations"
    }

    private fun isModelLoadRoute(session: IHTTPSession): Boolean {
        return session.uri == "/mobilecore/model/load"
    }

    private fun isModelDirsRoute(session: IHTTPSession): Boolean {
        return session.uri == "/mobilecore/models/dirs"
    }

    private fun hasAuth(session: IHTTPSession): Boolean {
        val headerValue = session.headers["authorization"] ?: session.headers["Authorization"] ?: return false
        return headerValue == "Bearer $apiKey"
    }

    private fun onChat(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session)
            val request = JSONObject(body)
            val model = request.optString("model", modelManager.defaultModelId())
            val messages = request.optJSONArray("messages") ?: JSONArray()
            val chatMessages = messages.toChatMessages()
            val options = ChatOptions(
                model = model,
                maxTokens = request.optInt("max_tokens", 512),
                temperature = request.optDouble("temperature", 0.7).toFloat(),
                stream = request.optBoolean("stream", false)
            )

            val result = backend.chat(chatMessages, options)
            benchmarkStore.record(model, result)
            if (!result.model.equals(model, ignoreCase = true)) {
                benchmarkStore.record(result.model, result)
            }

            okResponse(
                JSONObject().apply {
                    put("id", "chatcmpl-local-0001")
                    put("object", "chat.completion")
                    put("created", System.currentTimeMillis() / 1000)
                    put("model", model)
                    put("choices", JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("index", 0)
                                put(
                                    "message",
                                    JSONObject().apply {
                                        put("role", "assistant")
                                        put("content", result.message)
                                    }
                                )
                                put("finish_reason", result.finishReason)
                            }
                        )
                    })
                    put(
                        "usage",
                        JSONObject().apply {
                            put("prompt_tokens", result.promptTokens)
                            put("completion_tokens", result.completionTokens)
                            put("total_tokens", result.totalTokens)
                        }
                    )
                    put(
                        "mobilecore",
                        JSONObject().apply {
                            put("backend", "llama.cpp")
                            put("prompt_eval_ms", result.promptEvalMs)
                            put("decode_tokens_per_second", result.decodeTokensPerSecond)
                            put("first_token_ms", result.firstTokenMs)
                            put("decode_ms", result.decodeMs)
                            put("total_ms", result.totalMs)
                            put("memory_peak_mb", result.memoryPeakMb)
                        }
                    )
                }.toString(2)
            )
        } catch (e: Exception) {
            badRequest(e.message ?: "invalid request")
        }
    }

    private fun onLoadModel(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session)
            val request = JSONObject(body)
            val requestedPath = request.optString("path", "")
            val model = if (requestedPath.isNotBlank()) {
                requestedPath
            } else {
                modelManager.firstAvailableModel()?.path ?: ""
            }

            if (model.isBlank()) {
                return badRequest("No GGUF model found. Push one to the app external models directory first.")
            }

            val options = LoadOptions(
                contextLength = request.optInt("context_length", 4096),
                threads = request.optInt("threads", 4),
                gpuLayers = request.optInt("gpu_layers", 0)
            )
            val result = backend.loadModel(model, options)

            okResponse(
                JSONObject().apply {
                    put("ok", result.ok)
                    put("model", result.modelId)
                    put("path", model)
                    put("load_time_ms", result.loadTimeMs)
                    put("memory_used_mb", result.memoryUsedMb)
                    put("backend", backend.backendInfo().id)
                }.toString(2)
            )
        } catch (e: Exception) {
            badRequest(e.message ?: "model load failed")
        }
    }

    private fun parseBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            files["postData"] ?: "{}"
        } catch (_: IOException) {
            "{}"
        } catch (_: ResponseException) {
            "{}"
        }
    }

    private fun buildModels(): String {
        val models = modelManager.scanModels()
        val benchmarks = benchmarkStore.snapshot()
        val payload = JSONObject()
        payload.put("object", "list")
        payload.put(
            "data",
            JSONArray().apply {
                models.forEach { model ->
                    put(
                        JSONObject().apply {
                            put("id", model.id)
                            put("object", "model")
                            put("created", System.currentTimeMillis() / 1000)
                            put("owned_by", "mobilecore")
                            put(
                                "mobilecore",
                                JSONObject().apply {
                                    put("path", model.path)
                                    put("format", model.format)
                                    put("backend", model.backend)
                                    put("quantization", model.quantization)
                                    put("context_length", model.contextLength)
                                    put("size_bytes", model.sizeBytes)
                                    put("loaded", model.loaded)
                                    put("architecture", model.architecture)
                                    put("parameter_count_b", model.parameterCountB)
                                    put("parameter_label", model.parameterLabel)
                                    put("metadata_source", model.metadataSource)
                                    benchmarkFor(model, benchmarks)?.let { benchmark ->
                                        put("benchmark", benchmarkJson(benchmark))
                                    }
                                }
                            )
                        }
                    )
                }
            }
        )
        return payload.toString(2)
    }

    private fun buildMetrics(metrics: ai.mobilecore.runtime.RuntimeMetrics): String {
        val payload = JSONObject()
        payload.put("active_model", metrics.activeModel)
        payload.put("backend", metrics.backend)
        payload.put("uptime_seconds", 0)
        payload.put("requests_total", 0)
        payload.put("requests_failed", 0)
        payload.put("last_prompt_eval_ms", metrics.promptEvalMs)
        payload.put("last_decode_tokens_per_second", metrics.decodeTokensPerSecond)
        payload.put("average_decode_tokens_per_second", metrics.decodeTokensPerSecond)
        payload.put("last_first_token_ms", metrics.firstTokenMs)
        payload.put("last_decode_ms", metrics.decodeMs)
        payload.put("last_total_ms", metrics.totalMs)
        payload.put("last_prompt_tokens", metrics.promptTokens)
        payload.put("last_completion_tokens", metrics.completionTokens)
        payload.put("last_total_tokens", metrics.totalTokens)
        payload.put("memory_peak_mb", metrics.memoryPeakMb)
        return payload.toString(2)
    }

    private fun buildRecommendations(session: IHTTPSession): String {
        val profile = deviceProbe.probe()
        val models = modelManager.scanModels()
        val metrics = backend.metrics()
        val scoring = scoringConfig(session)
        val benchmarks = benchmarkStore.snapshot()
        val recommendations = deviceProbe.recommendModels(models, metrics, 5, scoring, benchmarks)
        val modelsById = models.associateBy { it.id }

        val payload = JSONObject()
        payload.put("generated_at_ms", System.currentTimeMillis())
        payload.put(
            "device",
            JSONObject().apply {
                put("device", profile.device)
                put("manufacturer", profile.manufacturer)
                put("model", profile.model)
                put("abi", profile.abi)
                put("total_ram_mb", profile.totalRamMb)
                put("available_ram_mb", profile.availableRamMb)
                put("low_ram_device", profile.lowRamDevice)
                put("core_count", profile.coreCount)
                put("backend", profile.backend)
                put("internal_storage_free_mb", profile.internalStorageFreeMb)
                put("external_storage_free_mb", profile.externalStorageFreeMb)
            }
        )
        payload.put(
            "runtime",
            JSONObject().apply {
                put("active_model", metrics.activeModel)
                put("backend", metrics.backend)
                put("last_decode_tokens_per_second", metrics.decodeTokensPerSecond)
            }
        )
        payload.put(
            "scoring",
            JSONObject().apply {
                put("preference", scoring.mode.apiName)
                put("fit_weight", scoring.fitWeight)
                put("speed_weight", scoring.speedWeight)
                put("size_weight", scoring.sizeWeight)
                put("measured_speed_weight", scoring.measuredSpeedWeight)
                put("base_tokens_per_second", scoring.baseTokensPerSecond)
                put("target_tokens_per_second", scoring.targetTokensPerSecond)
                put("memory_pressure_penalty", scoring.memoryPressurePenalty)
            }
        )
        payload.put("recommendations", recommendationArray(recommendations, modelsById))
        return payload.toString(2)
    }

    private fun scoringConfig(session: IHTTPSession): RecommendationScoringConfig {
        val modeName = queryParam(session, "preference") ?: queryParam(session, "mode")
        val preset = scoringConfigSource.configFor(modeName)
        return preset.copy(
            fitWeight = queryClampedDouble(session, "fit_weight", preset.fitWeight, 0.0, 1.0),
            speedWeight = queryClampedDouble(session, "speed_weight", preset.speedWeight, 0.0, 1.0),
            sizeWeight = queryClampedDouble(session, "size_weight", preset.sizeWeight, 0.0, 1.0),
            measuredSpeedWeight = queryClampedDouble(
                session,
                "measured_speed_weight",
                preset.measuredSpeedWeight,
                0.0,
                1.0
            ),
            baseTokensPerSecond = queryClampedDouble(session, "base_tps", preset.baseTokensPerSecond, 0.1, 100.0),
            targetTokensPerSecond = queryClampedDouble(session, "target_tps", preset.targetTokensPerSecond, 0.1, 100.0),
            memoryPressurePenalty = queryClampedDouble(session, "memory_penalty", preset.memoryPressurePenalty, 0.0, 100.0)
        )
    }

    private fun queryParam(session: IHTTPSession, name: String): String? {
        return session.parameters[name]?.firstOrNull()
    }

    private fun queryClampedDouble(
        session: IHTTPSession,
        name: String,
        fallback: Double,
        min: Double,
        max: Double
    ): Double {
        val value = queryParam(session, name)?.toDoubleOrNull() ?: fallback
        return if (value.isFinite()) value.coerceIn(min, max) else fallback
    }

    private fun recommendationArray(
        recommendations: List<DeviceRecommendation>,
        modelsById: Map<String, RuntimeModel>
    ): JSONArray {
        return JSONArray().apply {
            recommendations.forEach { recommendation ->
                val model = modelsById[recommendation.modelId]
                put(
                    JSONObject().apply {
                        put("model_id", recommendation.modelId)
                        put("path", recommendation.path)
                        put("size_bytes", recommendation.sizeBytes)
                        put("estimated_memory_mb", recommendation.estimatedMemoryMb)
                        put("fit", recommendation.fit.name.lowercase())
                        put("score", recommendation.score)
                        put("expected_tokens_per_second", recommendation.expectedTokensPerSecond)
                        put("loaded", recommendation.loaded)
                        if (model != null) {
                            put("architecture", model.architecture)
                            put("parameter_count_b", model.parameterCountB)
                            put("parameter_label", model.parameterLabel)
                            put("quantization", model.quantization)
                            put("context_length", model.contextLength)
                            put("metadata_source", model.metadataSource)
                        }
                        recommendation.benchmark?.let { benchmark ->
                            put("benchmark", benchmarkJson(benchmark))
                        }
                        put(
                            "reasons",
                            JSONArray().apply {
                                recommendation.reasons.forEach { reason ->
                                    put(reason)
                                }
                            }
                        )
                    }
                )
            }
        }
    }

    private fun benchmarkFor(
        model: RuntimeModel,
        benchmarks: Map<String, ModelBenchmark>
    ): ModelBenchmark? {
        return benchmarks[model.id]
            ?: benchmarks[model.path.substringAfterLast('/').substringBeforeLast(".gguf")]
            ?: benchmarks[model.path]
    }

    private fun benchmarkJson(benchmark: ModelBenchmark): JSONObject {
        return JSONObject().apply {
            put("model_id", benchmark.modelId)
            put("samples", benchmark.samples)
            put("last_decode_tokens_per_second", benchmark.lastDecodeTokensPerSecond)
            put("average_decode_tokens_per_second", benchmark.averageDecodeTokensPerSecond)
            put("last_first_token_ms", benchmark.lastFirstTokenMs)
            put("average_first_token_ms", benchmark.averageFirstTokenMs)
            put("last_prompt_eval_ms", benchmark.lastPromptEvalMs)
            put("last_decode_ms", benchmark.lastDecodeMs)
            put("last_total_ms", benchmark.lastTotalMs)
            put("memory_peak_mb", benchmark.memoryPeakMb)
            put("updated_at_ms", benchmark.updatedAtMs)
        }
    }

    private fun buildModelDirs(): String {
        val payload = JSONObject()
        payload.put(
            "dirs",
            JSONArray().apply {
                modelManager.modelDirectories().forEach { dir ->
                    put(dir.absolutePath)
                }
            }
        )
        return payload.toString(2)
    }

    private fun buildHealth(info: BackendInfo): String {
        val payload = JSONObject()
        payload.put("status", "ok")
        payload.put("service", "mobilecore")
        payload.put("version", "0.1.0")
        payload.put("model_loaded", backend.isModelLoaded())
        payload.put("active_model", backend.metrics().activeModel)
        payload.put("backend", info.id)
        return payload.toString(2)
    }

    private fun okResponse(body: String) =
        newFixedLengthResponse(Response.Status.OK, "application/json", body)

    private fun unauthorized() =
        newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
            JSONObject(mapOf("error" to mapOf("message" to "unauthorized"))).toString()
        )

    private fun badRequest(msg: String): Response =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
            JSONObject(mapOf("error" to mapOf("message" to msg, "code" to "invalid_request"))).toString()
        )
}

private fun JSONArray.toChatMessages(): List<ChatMessage> {
    val messages = ArrayList<ChatMessage>(length())
    for (i in 0 until length()) {
        val item = optJSONObject(i) ?: continue
        val role = item.optString("role", "user")
        val content = item.optString("content", "")
        messages.add(ChatMessage(role = role, content = content))
    }
    return messages
}
