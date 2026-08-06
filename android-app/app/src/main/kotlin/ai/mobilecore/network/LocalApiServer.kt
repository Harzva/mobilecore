package ai.mobilecore.network

import ai.mobilecore.benchmark.BenchmarkReportStore
import ai.mobilecore.runtime.BenchmarkLeaderboardStore
import ai.mobilecore.runtime.ChatOptions
import ai.mobilecore.runtime.DeviceRecommendation
import ai.mobilecore.runtime.LoadOptions
import ai.mobilecore.runtime.ModelBenchmark
import ai.mobilecore.runtime.ModelBenchmarkStore
import ai.mobilecore.runtime.DeviceProbe
import ai.mobilecore.runtime.ModelManager
import ai.mobilecore.runtime.MultimodalRuntimeBackend
import ai.mobilecore.runtime.RecommendationScoringConfig
import ai.mobilecore.runtime.RecommendationScoringConfigSource
import ai.mobilecore.runtime.RuntimeBackend
import ai.mobilecore.runtime.RuntimeModel
import ai.mobilecore.runtime.SharedLeaderboardClient
import ai.mobilecore.runtime.SharedLeaderboardConfigSource
import ai.mobilecore.runtime.VisionModelManager
import ai.mobilecore.runtime.VisionRuntime
import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.ResponseException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.DoubleAdder

class LocalApiServer(
    private val backend: RuntimeBackend,
    private val modelManager: ModelManager,
    private val context: Context,
    private val apiKey: String = "local",
    port: Int = 8080
) : NanoHTTPD("127.0.0.1", port) {
    private val logTag = "MobileCoreApi"
    private val apiVersion = "0.1.4-rc2"
    private val startedAtMs = System.currentTimeMillis()
    private val requestsTotal = AtomicLong(0)
    private val requestsFailed = AtomicLong(0)
    private val requestsCompleted = AtomicLong(0)
    private val decodeTokensPerSecondTotal = DoubleAdder()
    private val deviceProbe = DeviceProbe(context.applicationContext)
    private val scoringConfigSource = RecommendationScoringConfigSource(context.applicationContext)
    private val benchmarkStore = ModelBenchmarkStore(context.applicationContext)
    private val leaderboardStore = BenchmarkLeaderboardStore(context.applicationContext)
    private val benchmarkReportStore = BenchmarkReportStore(context.applicationContext)
    private val sharedLeaderboardConfigSource = SharedLeaderboardConfigSource(context.applicationContext)
    private val visionModelManager = VisionModelManager(context.applicationContext)
    private val visionRuntime = VisionRuntime(visionModelManager)
    private val openAiChatParser = OpenAiChatRequestParser(createSecureMediaStore(context.applicationContext))
    private val omniController = OmniLocalController(
        context = context.applicationContext,
        backend = backend,
        version = apiVersion,
        modelManager = modelManager,
    )
    private val allowedCorsOrigins = setOf(
        "https://harzva.github.io",
        "http://localhost:5173",
        "http://127.0.0.1:5173"
    )

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        if (method == Method.OPTIONS) {
            return withCors(session, newFixedLengthResponse(Response.Status.OK, "application/json", "{}"))
        }

        val response = when {
            isHealthRoute(session) && method == Method.GET -> okResponse(omniController.health().toString(2))
            isModelsRoute(session) && method == Method.GET -> {
                if (!hasAuth(session)) unauthorized() else okResponse(buildModels())
            }

            isChatRoute(session) && method == Method.POST -> {
                if (!hasAuth(session)) unauthorized() else onChat(session)
            }

            isMetricsRoute(session) && method == Method.GET -> {
                if (!hasAuth(session)) unauthorized() else okResponse(buildMetrics(backend.metrics()))
            }

            isBenchmarkLatestRoute(session) && method == Method.GET -> {
                if (!hasAuth(session)) unauthorized() else buildLatestBenchmarkReport()
            }

            isBenchmarkReportsRoute(session) && method == Method.GET -> {
                if (!hasAuth(session)) unauthorized() else okResponse(buildBenchmarkReports(session))
            }

            isRecommendationRoute(session) && method == Method.GET -> {
                if (!hasAuth(session)) unauthorized() else okResponse(buildRecommendations(session))
            }

            isLocalLeaderboardRoute(session) && method == Method.GET -> {
                if (!hasAuth(session)) unauthorized() else okResponse(buildLocalLeaderboard(session))
            }

            isSharedLeaderboardRoute(session) && method == Method.GET -> {
                if (!hasAuth(session)) unauthorized() else okResponse(buildSharedLeaderboard(session))
            }

            isSharedLeaderboardRoute(session) && method == Method.POST -> {
                if (!hasAuth(session)) unauthorized() else okResponse(syncSharedLeaderboard(session))
            }

            isVisionStatusRoute(session) && method == Method.GET -> {
                if (!hasAuth(session)) unauthorized() else okResponse(visionRuntime.status().toString(2))
            }

            isVisionModelsRoute(session) && method == Method.GET -> {
                if (!hasAuth(session)) unauthorized() else okResponse(visionModelManager.toJson().toString(2))
            }

            isVisionOcrRoute(session) && method == Method.POST -> {
                if (!hasAuth(session)) unauthorized() else okResponse(runVisionOcr(session))
            }

            isVisionClassifyRoute(session) && method == Method.POST -> {
                if (!hasAuth(session)) unauthorized() else okResponse(runVisionClassify(session))
            }

            isVisionDiffusionRoute(session) && method == Method.POST -> {
                if (!hasAuth(session)) unauthorized() else okResponse(runVisionDiffusion(session))
            }

            isModelLoadRoute(session) && method == Method.POST -> {
                if (!hasAuth(session)) unauthorized() else onLoadModel(session)
            }

            isModelUnloadRoute(session) && method == Method.POST -> {
                if (!hasAuth(session)) unauthorized() else onUnloadModel()
            }

            isModelDirsRoute(session) && method == Method.GET -> {
                if (!hasAuth(session)) unauthorized() else okResponse(buildModelDirs())
            }

            isOmniStatusRoute(session) && method == Method.GET -> {
                if (!hasAuth(session)) unauthorized() else okResponse(omniController.status().toString(2))
            }

            isOmniInstallRoute(session) && method == Method.POST -> {
                if (!hasAuth(session)) unauthorized() else onOmniInstall(session)
            }

            isOmniCancelRoute(session) && method == Method.POST -> {
                if (!hasAuth(session)) unauthorized() else okResponse(omniController.cancel().toString(2))
            }

            isOmniVerifyRoute(session) && method == Method.POST -> {
                if (!hasAuth(session)) unauthorized() else omniResult(omniController.verify())
            }

            isOmniLoadRoute(session) && method == Method.POST -> {
                if (!hasAuth(session)) unauthorized() else onOmniLoad(session)
            }

            isOmniUninstallRoute(session) && (method == Method.POST || method == Method.DELETE) -> {
                if (!hasAuth(session)) unauthorized() else omniResult(omniController.uninstall())
            }

            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                JSONObject(mapOf("error" to mapOf("message" to "not found"))).toString()
            )
        }
        return withCors(session, response)
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

    private fun isBenchmarkLatestRoute(session: IHTTPSession): Boolean {
        return session.uri == "/v1/benchmark/latest"
    }

    private fun isBenchmarkReportsRoute(session: IHTTPSession): Boolean {
        return session.uri == "/v1/benchmark/reports"
    }

    private fun isRecommendationRoute(session: IHTTPSession): Boolean {
        return session.uri == "/v1/recommendations"
    }

    private fun isLocalLeaderboardRoute(session: IHTTPSession): Boolean {
        return session.uri == "/leaderboard/local" || session.uri == "/v1/leaderboard/local"
    }

    private fun isSharedLeaderboardRoute(session: IHTTPSession): Boolean {
        return session.uri == "/leaderboard/shared" || session.uri == "/v1/leaderboard/shared"
    }

    private fun isVisionStatusRoute(session: IHTTPSession): Boolean {
        return session.uri == "/vision/status" || session.uri == "/v1/vision/status"
    }

    private fun isVisionModelsRoute(session: IHTTPSession): Boolean {
        return session.uri == "/vision/models" || session.uri == "/v1/vision/models"
    }

    private fun isVisionOcrRoute(session: IHTTPSession): Boolean {
        return session.uri == "/vision/ocr" || session.uri == "/v1/vision/ocr"
    }

    private fun isVisionClassifyRoute(session: IHTTPSession): Boolean {
        return session.uri == "/vision/classify" || session.uri == "/v1/vision/classify"
    }

    private fun isVisionDiffusionRoute(session: IHTTPSession): Boolean {
        return session.uri == "/vision/diffusion" || session.uri == "/v1/vision/diffusion"
    }

    private fun isModelLoadRoute(session: IHTTPSession): Boolean {
        return session.uri == "/mobilecore/model/load"
    }

    private fun isModelUnloadRoute(session: IHTTPSession): Boolean {
        return session.uri == "/mobilecore/model/unload"
    }

    private fun isModelDirsRoute(session: IHTTPSession): Boolean {
        return session.uri == "/mobilecore/models/dirs"
    }

    private fun isOmniStatusRoute(session: IHTTPSession): Boolean =
        session.uri == "/mobilecore/omni/status"

    private fun isOmniInstallRoute(session: IHTTPSession): Boolean =
        session.uri == "/mobilecore/omni/install"

    private fun isOmniCancelRoute(session: IHTTPSession): Boolean =
        session.uri == "/mobilecore/omni/cancel"

    private fun isOmniVerifyRoute(session: IHTTPSession): Boolean =
        session.uri == "/mobilecore/omni/verify"

    private fun isOmniLoadRoute(session: IHTTPSession): Boolean =
        session.uri == "/mobilecore/omni/load"

    private fun isOmniUninstallRoute(session: IHTTPSession): Boolean =
        session.uri == "/mobilecore/omni/uninstall"

    private fun hasAuth(session: IHTTPSession): Boolean {
        val headerValue = session.headers["authorization"] ?: session.headers["Authorization"] ?: return false
        return headerValue == "Bearer $apiKey"
    }

    private fun onChat(session: IHTTPSession): Response {
        requestsTotal.incrementAndGet()
        val declaredLength = session.headers["content-length"]?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_CHAT_REQUEST_BYTES) {
            requestsFailed.incrementAndGet()
            return apiError(
                ApiFailureCode.MEDIA_TOO_LARGE,
                "request exceeds the configured size limit",
            )
        }
        return try {
            val body = parseBody(session)
            if (body.toByteArray(Charsets.UTF_8).size.toLong() > MAX_CHAT_REQUEST_BYTES) {
                requestsFailed.incrementAndGet()
                return apiError(
                    ApiFailureCode.MEDIA_TOO_LARGE,
                    "request exceeds the configured size limit",
                )
            }
            val request = JSONObject(body)
            val model = request.optString("model", modelManager.defaultModelId())
            val options = ChatOptions(
                model = model,
                maxTokens = request.optInt("max_tokens", 512),
                temperature = request.optDouble("temperature", 0.7).toFloat(),
                stream = request.optBoolean("stream", false)
            )

            val parsedRequest = openAiChatParser.parse(request)
            val result = parsedRequest.use { parsed ->
                OpenAiChatDispatcher.dispatch(backend, parsed, options)
            }
            requestsCompleted.incrementAndGet()
            decodeTokensPerSecondTotal.add(result.decodeTokensPerSecond)
            benchmarkStore.record(model, result)
            if (!result.model.equals(model, ignoreCase = true)) {
                benchmarkStore.record(result.model, result)
            }
            val created = System.currentTimeMillis() / 1000
            val signaturePayload = benchmarkSignaturePayload(model, result, created)
            val benchmarkSignature = sha256Hex("$signaturePayload|$apiKey")
            val mobileCoreMetadata = JSONObject().apply {
                put("backend", "llama.cpp")
                put("prompt_eval_ms", result.promptEvalMs)
                put("decode_tokens_per_second", result.decodeTokensPerSecond)
                put("first_token_ms", result.firstTokenMs)
                put("decode_ms", result.decodeMs)
                put("total_ms", result.totalMs)
                put("memory_peak_mb", result.memoryPeakMb)
                put("signature_algorithm", "sha256")
                put("signature_payload", signaturePayload)
                put("benchmark_signature", benchmarkSignature)
            }
            val responseId = "chatcmpl-local-${System.currentTimeMillis()}"

            if (options.stream) {
                val streamBody = OpenAiSseEncoder.encode(
                    id = responseId,
                    created = created,
                    model = model,
                    content = result.message,
                    finishReason = result.finishReason,
                    promptTokens = result.promptTokens,
                    completionTokens = result.completionTokens,
                    totalTokens = result.totalTokens,
                    mobileCoreMetadata = mobileCoreMetadata
                )
                newChunkedResponse(
                    Response.Status.OK,
                    "text/event-stream; charset=utf-8",
                    streamBody.byteInputStream(Charsets.UTF_8)
                ).apply {
                    addHeader("Cache-Control", "no-cache")
                    addHeader("Connection", "keep-alive")
                    addHeader("X-Accel-Buffering", "no")
                }
            } else {
                okResponse(JSONObject().apply {
                    put("id", responseId)
                    put("object", "chat.completion")
                    put("created", created)
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
                    put("mobilecore", mobileCoreMetadata)
                }.toString(2))
            }
        } catch (e: ApiRequestException) {
            requestsFailed.incrementAndGet()
            Log.e(logTag, "chat_failed code=${e.failureCode.wireValue} type=${e.javaClass.simpleName}")
            apiError(e.failureCode, e.publicMessage)
        } catch (e: Exception) {
            requestsFailed.incrementAndGet()
            Log.e(logTag, "chat_failed code=invalid_request type=${e.javaClass.simpleName}")
            apiError(ApiFailureCode.INVALID_REQUEST, "invalid request")
        }
    }

    private fun onLoadModel(session: IHTTPSession): Response {
        return try {
            val body = parseBody(session)
            val request = JSONObject(body)
            val requestedModelId = request.optString("model_id", "").trim()
            val requestedPath = request.optString("path", "")
            val runtimeModel = when {
                requestedModelId.isNotBlank() -> modelManager.modelById(requestedModelId)
                requestedPath.isNotBlank() -> modelManager.scanModels().firstOrNull { candidate ->
                    runCatching { File(candidate.path).canonicalPath == File(requestedPath).canonicalPath }
                        .getOrDefault(false)
                }
                else -> modelManager.firstAvailableModel()
            }
            val model = runtimeModel?.path ?: requestedPath

            if (model.isBlank()) {
                return apiError(ApiFailureCode.ARTIFACT_MISSING, "required model artifact is missing")
            }

            val modelFile = runCatching { File(model).canonicalFile }.getOrElse {
                return apiError(ApiFailureCode.ARTIFACT_MISSING, "required model artifact is missing")
            }
            val allowedModelRoots = modelManager.modelDirectories().mapNotNull { directory ->
                runCatching { directory.canonicalFile }.getOrNull()
            }
            if (!modelFile.isFile ||
                modelFile.extension.lowercase() != "gguf" ||
                allowedModelRoots.none { root ->
                    modelFile == root || modelFile.path.startsWith(root.path + File.separator)
                }
            ) {
                return apiError(ApiFailureCode.ARTIFACT_MISSING, "required model artifact is missing")
            }

            val options = LoadOptions(
                contextLength = request.optInt("context_length", 4096),
                threads = request.optInt("threads", 4),
                gpuLayers = request.optInt("gpu_layers", 0)
            )
            val result = backend.loadModel(modelFile.absolutePath, options)
            if (!result.ok) {
                Log.e(logTag, "model_load_failed stage=main model_id=${runtimeModel?.id ?: "legacy"}")
                return apiError(ApiFailureCode.MODEL_LOAD_FAILED, "model failed to load")
            }

            val requestedProjectorId = request.optString("projector_id", "").trim()
            val projector = when {
                requestedProjectorId.isNotBlank() -> modelManager.projectorById(requestedProjectorId)
                runtimeModel != null -> modelManager.projectorForModel(runtimeModel.id)
                else -> null
            }
            if (requestedProjectorId.isNotBlank() && projector == null) {
                backend.unloadModel()
                return apiError(ApiFailureCode.ARTIFACT_MISSING, "required model artifact is missing")
            }
            if (requestedProjectorId.isNotBlank() &&
                (runtimeModel == null ||
                    !modelManager.isProjectorCompatible(runtimeModel.id, requestedProjectorId))
            ) {
                backend.unloadModel()
                return apiError(ApiFailureCode.INVALID_REQUEST, "projector is not compatible with the selected model")
            }
            if (projector != null) {
                val multimodal = backend as? MultimodalRuntimeBackend
                val projectorLoaded = multimodal?.loadProjector(
                    projectorPath = projector.path,
                    projectorId = projector.id,
                    threads = options.threads,
                ) == true
                if (!projectorLoaded) {
                    Log.e(
                        logTag,
                        "model_load_failed stage=projector model_id=${runtimeModel?.id ?: "legacy"} projector_id=${projector.id}",
                    )
                    backend.unloadModel()
                    return apiError(ApiFailureCode.MODEL_LOAD_FAILED, "model failed to load")
                }
            }
            val multimodalStatus = (backend as? MultimodalRuntimeBackend)?.multimodalStatus()

            okResponse(
                JSONObject().apply {
                    put("ok", result.ok)
                    put("model", result.modelId)
                    put("load_time_ms", result.loadTimeMs)
                    put("memory_used_mb", result.memoryUsedMb)
                    put("backend", backend.backendInfo().id)
                    put("projector_id", projector?.id ?: JSONObject.NULL)
                    put("capabilities", JSONObject().apply {
                        put("text_input", true)
                        put("image_input", multimodalStatus?.imageInput == true)
                        put("audio_input", multimodalStatus?.audioInput == true)
                        put("video_input", false)
                        put("text_output", true)
                        put("audio_output", false)
                    })
                }.toString(2)
            )
        } catch (e: Exception) {
            Log.e(logTag, "model_load_failed code=model_load_failed type=${e.javaClass.simpleName}")
            apiError(ApiFailureCode.MODEL_LOAD_FAILED, "model failed to load")
        }
    }

    private fun onUnloadModel(): Response {
        return try {
            val previousModel = backend.metrics().activeModel
            val ok = backend.unloadModel()
            if (!ok) {
                apiError(ApiFailureCode.MODEL_LOAD_FAILED, "model failed to unload")
            } else {
                okResponse(
                    JSONObject().apply {
                        put("ok", true)
                        put("previous_model", previousModel ?: JSONObject.NULL)
                        put("model_loaded", backend.isModelLoaded())
                        put("backend", backend.backendInfo().id)
                    }.toString(2)
                )
            }
        } catch (e: Exception) {
            Log.e(logTag, "model_unload_failed code=model_load_failed type=${e.javaClass.simpleName}")
            apiError(ApiFailureCode.MODEL_LOAD_FAILED, "model failed to unload")
        }
    }

    private fun onOmniInstall(session: IHTTPSession): Response {
        return runCatching {
            val body = parseBody(session)
            if (body.toByteArray(Charsets.UTF_8).size > MAX_CONTROL_REQUEST_BYTES) {
                return apiError(ApiFailureCode.INVALID_REQUEST, "control request exceeds the configured limit")
            }
            omniController.install(JSONObject(body))
        }.fold(
            onSuccess = { result -> omniResult(result, acceptedStatus = result.accepted) },
            onFailure = { apiError(ApiFailureCode.INVALID_REQUEST, "invalid install request") },
        )
    }

    private fun onOmniLoad(session: IHTTPSession): Response {
        return runCatching { omniController.load(JSONObject(parseBody(session))) }.fold(
            onSuccess = { result -> omniResult(result) },
            onFailure = { apiError(ApiFailureCode.MODEL_LOAD_FAILED, "model failed to load") },
        )
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
                    val projector = modelManager.projectorForModel(model.id)
                    put(
                        JSONObject().apply {
                            put("id", model.id)
                            put("object", "model")
                            put("created", System.currentTimeMillis() / 1000)
                            put("owned_by", "mobilecore")
                            put(
                                "mobilecore",
                                JSONObject().apply {
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
                                    put("projector_id", projector?.id ?: JSONObject.NULL)
                                    put("projector_size_bytes", projector?.sizeBytes ?: 0L)
                                    put("capabilities", JSONObject().apply {
                                        put("text_input", true)
                                        put("image_input", projector != null)
                                        put("audio_input", false)
                                        put("video_input", false)
                                        put("text_output", true)
                                        put("audio_output", false)
                                    })
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
        val completed = requestsCompleted.get()
        val averageDecodeTokensPerSecond = if (completed > 0) {
            decodeTokensPerSecondTotal.sum() / completed.toDouble()
        } else {
            0.0
        }
        val payload = JSONObject()
        payload.put("active_model", metrics.activeModel)
        payload.put("backend", metrics.backend)
        payload.put("uptime_seconds", ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L))
        payload.put("requests_total", requestsTotal.get())
        payload.put("requests_failed", requestsFailed.get())
        payload.put("last_prompt_eval_ms", metrics.promptEvalMs)
        payload.put("last_decode_tokens_per_second", metrics.decodeTokensPerSecond)
        payload.put("average_decode_tokens_per_second", averageDecodeTokensPerSecond)
        payload.put("last_first_token_ms", metrics.firstTokenMs)
        payload.put("last_decode_ms", metrics.decodeMs)
        payload.put("last_total_ms", metrics.totalMs)
        payload.put("last_prompt_tokens", metrics.promptTokens)
        payload.put("last_completion_tokens", metrics.completionTokens)
        payload.put("last_total_tokens", metrics.totalTokens)
        payload.put("memory_peak_mb", metrics.memoryPeakMb)
        return payload.toString(2)
    }

    private fun buildLatestBenchmarkReport(): Response {
        val latest = benchmarkReportStore.latest() ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "application/json",
            JSONObject(mapOf("error" to mapOf("message" to "no benchmark report"))).toString()
        )
        return okResponse(latest.toString(2))
    }

    private fun buildBenchmarkReports(session: IHTTPSession): String {
        val limit = queryParam(session, "limit")?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        return benchmarkReportStore.toJson(limit).toString(2)
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

    private fun buildLocalLeaderboard(session: IHTTPSession): String {
        val limit = queryParam(session, "limit")?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        return leaderboardStore.toJson(maxItems = limit).toString(2)
    }

    private fun buildSharedLeaderboard(session: IHTTPSession): String {
        val limit = queryParam(session, "limit")?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        return sharedLeaderboardClient().fetch(limit).toString(2)
    }

    private fun syncSharedLeaderboard(session: IHTTPSession): String {
        val limit = queryParam(session, "limit")?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        val entries = leaderboardStore.entries().take(limit)
        val result = sharedLeaderboardClient().upload(entries)
        result.put("local_count", entries.size)
        return result.toString(2)
    }

    private fun sharedLeaderboardClient(): SharedLeaderboardClient {
        return SharedLeaderboardClient(sharedLeaderboardConfigSource.load())
    }

    private fun runVisionOcr(session: IHTTPSession): String {
        val request = JSONObject(parseBody(session))
        val imageName = request.optString("image_name", "selected-image").ifBlank { "selected-image" }
        val imagePath = request.optString("image_path", "")
        return visionRuntime.ocr(imageName, imagePath).toString(2)
    }

    private fun runVisionClassify(session: IHTTPSession): String {
        val request = JSONObject(parseBody(session))
        val imageName = request.optString("image_name", "selected-image").ifBlank { "selected-image" }
        val imagePath = request.optString("image_path", "")
        val dataset = request.optString("dataset", "clip").ifBlank { "clip" }
        return visionRuntime.classify(imageName, imagePath, dataset).toString(2)
    }

    private fun runVisionDiffusion(session: IHTTPSession): String {
        val request = JSONObject(parseBody(session))
        val prompt = request.optString("prompt", "a small mobilecore smoke image")
        val width = request.optInt("width", 512)
        val height = request.optInt("height", 512)
        val steps = request.optInt("steps", 4)
        val seed = request.optLong("seed", 42L)
        return visionRuntime.generateDiffusion(prompt, width, height, steps, seed).toString(2)
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
                        put("size_bytes", recommendation.sizeBytes)
                        put("estimated_memory_mb", recommendation.estimatedMemoryMb)
                        put("fit", recommendation.fit.name.lowercase())
                        put("score", recommendation.score)
                        put("expected_tokens_per_second", recommendation.expectedTokensPerSecond)
                        put("loaded", recommendation.loaded)
                        if (model != null) {
                            val projector = modelManager.projectorForModel(model.id)
                            put("architecture", model.architecture)
                            put("parameter_count_b", model.parameterCountB)
                            put("parameter_label", model.parameterLabel)
                            put("quantization", model.quantization)
                            put("context_length", model.contextLength)
                            put("metadata_source", model.metadataSource)
                            put("projector_id", projector?.id ?: JSONObject.NULL)
                            put("projector_size_bytes", projector?.sizeBytes ?: 0L)
                            put("capabilities", JSONObject().apply {
                                put("text_input", true)
                                put("image_input", projector != null)
                                put("audio_input", false)
                                put("video_input", false)
                                put("text_output", true)
                                put("audio_output", false)
                            })
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

    private fun benchmarkSignaturePayload(model: String, result: ai.mobilecore.runtime.ChatResult, created: Long): String {
        return listOf(
            "mobilecore-benchmark-v1",
            created.toString(),
            model,
            result.model,
            result.promptEvalMs.toString(),
            result.firstTokenMs.toString(),
            result.decodeMs.toString(),
            result.totalMs.toString(),
            result.decodeTokensPerSecond.toString(),
            result.completionTokens.toString(),
            result.memoryPeakMb.toString()
        ).joinToString("|")
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun withCors(session: IHTTPSession, response: Response): Response {
        val origin = session.headers["origin"] ?: session.headers["Origin"]
        if (origin != null && allowedCorsOrigins.contains(origin)) {
            response.addHeader("Access-Control-Allow-Origin", origin)
            response.addHeader("Vary", "Origin")
        }
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-MobileCore-Client")
        response.addHeader("Access-Control-Allow-Private-Network", "true")
        response.addHeader("Access-Control-Max-Age", "86400")
        return response
    }

    private fun okResponse(body: String) =
        newFixedLengthResponse(Response.Status.OK, "application/json", body)

    private fun unauthorized() =
        newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
            JSONObject(mapOf("error" to mapOf("message" to "unauthorized"))).toString()
        )

    private fun apiError(code: ApiFailureCode, message: String): Response =
        newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "application/json",
            OpenAiApiError.json(code, message),
        )

    private fun omniResult(result: OmniControllerResult, acceptedStatus: Boolean = false): Response {
        val status = when {
            !result.accepted -> Response.Status.BAD_REQUEST
            acceptedStatus -> Response.Status.ACCEPTED
            else -> Response.Status.OK
        }
        return newFixedLengthResponse(status, "application/json", result.body.toString(2))
    }

    fun cancelBackgroundOperations() {
        omniController.cancel()
    }

    private companion object {
        const val MAX_CHAT_REQUEST_BYTES = 36L * 1024L * 1024L
        const val MAX_CONTROL_REQUEST_BYTES = 32 * 1024
    }
}
