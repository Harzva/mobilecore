package ai.mobilecore.g2d

import ai.mobilecore.runtime.RuntimeBridge
import ai.mobilecore.runtime.RuntimeBridgeG2dRouterModel
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

data class OxfordPetsG2dProgress(
    val stage: String,
    val completed: Int,
    val total: Int,
    val sampleId: String? = null,
)

data class OxfordPetsG2dRunResult(
    val scale: OxfordPetsRunScale,
    val reportFile: File,
    val report: JSONObject,
)

/**
 * Resumable device-only Oxford-Pets evaluation. CLIP and VLM work are cached
 * by official image ID, so 37 -> 370 -> 3,669 only computes newly introduced
 * samples while every scale still receives an independently aggregated report.
 */
class OxfordPetsG2dRunner(
    private val root: File,
) {
    constructor(context: Context) : this(
        requireNotNull(context.getExternalFilesDir("g2d")) {
            "External app files directory is unavailable."
        },
    )

    private val running = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private val annotationsFile = File(root, "annotations/test.txt")
    private val imagesDir = File(root, "images")
    private val modelsDir = File(root, "models")
    private val reportsDir = File(root, "reports").apply { mkdirs() }
    private val cacheDir = File(root, "cache").apply { mkdirs() }
    private val clipModel = File(modelsDir, "openai-clip-vit-b16-image.onnx")
    private val clipSidecar = File(modelsDir, "oxford-pets-clip-vit-b16-text-embeddings.json")
    private val qwenModel = File(modelsDir, "Qwen3.5-0.8B-Q4_K_M.gguf")
    private val qwenProjector = File(modelsDir, "mmproj-Qwen3.5-0.8B-BF16.gguf")
    private val clipCacheFile = File(cacheDir, "clip-vit-b16.jsonl")
    private val recordCacheFile = File(cacheDir, "qwen3.5-0.8b-q4-direct-v2-g2d.jsonl")

    fun cancel() {
        cancelRequested.set(true)
        RuntimeBridge.cancel()
    }

    fun readiness(): JSONObject = JSONObject().apply {
        put("root", root.absolutePath)
        put("annotations", annotationsFile.isFile)
        put("images", imagesDir.isDirectory)
        put("clip_model", clipModel.isFile)
        put("clip_sidecar", clipSidecar.isFile)
        put("qwen_model", qwenModel.isFile)
        put("qwen_projector", qwenProjector.isFile)
        put("ready", listOf(
            annotationsFile,
            imagesDir,
            clipModel,
            clipSidecar,
            qwenModel,
            qwenProjector,
        ).all(File::exists))
    }

    fun run(
        scale: OxfordPetsRunScale,
        onProgress: (OxfordPetsG2dProgress) -> Unit = {},
    ): OxfordPetsG2dRunResult {
        check(running.compareAndSet(false, true)) { "An Oxford-Pets run is already active." }
        cancelRequested.set(false)
        try {
            require(readiness().getBoolean("ready")) { "Oxford-Pets models or dataset files are incomplete: ${readiness()}" }
            val dataset = OxfordPetsDataset.openOfficialTest(
                annotationsRoot = annotationsFile,
                imagesRoot = imagesDir,
                // Validate each selected stage lazily so 37/370 can be staged
                // without copying all 3,669 JPEGs onto a constrained device.
                requireImages = false,
            )
            val samples = dataset.samplesFor(scale)
            require(samples.size == scale.expectedSamples)
            val clipCache = readJsonLines(clipCacheFile).associateByTo(linkedMapOf()) {
                it.getString("image_id")
            }
            computeMissingClip(samples, clipCache, onProgress)

            val records = readJsonLines(recordCacheFile).associateByTo(linkedMapOf()) {
                it.getString("image_id")
            }
            computeMissingG2d(samples, clipCache, records, onProgress)
            ensureNotCancelled()
            val selectedRecords = samples.map { sample ->
                requireNotNull(records[sample.imageId]) { "Missing device record for ${sample.imageId}." }
            }
            val report = aggregateReport(scale, samples, selectedRecords)
            val output = File(reportsDir, "oxford-pets-${scale.name.lowercase()}.json")
            output.writeText(report.toString(2))
            return OxfordPetsG2dRunResult(scale, output, report)
        } finally {
            running.set(false)
        }
    }

    /**
     * Computes one deterministic slice of a scale without aggregating a report.
     * Cache files can then be merged by image_id and replayed through [run] to
     * produce the exact same report as a single-device execution.
     */
    fun runShard(
        scale: OxfordPetsRunScale,
        startInclusive: Int,
        endExclusive: Int,
        onProgress: (OxfordPetsG2dProgress) -> Unit = {},
    ): Int {
        check(running.compareAndSet(false, true)) { "An Oxford-Pets run is already active." }
        cancelRequested.set(false)
        try {
            require(readiness().getBoolean("ready")) {
                "Oxford-Pets models or dataset files are incomplete: ${readiness()}"
            }
            val dataset = OxfordPetsDataset.openOfficialTest(
                annotationsRoot = annotationsFile,
                imagesRoot = imagesDir,
                requireImages = false,
            )
            val allSamples = dataset.samplesFor(scale)
            require(allSamples.size == scale.expectedSamples)
            require(startInclusive in 0 until endExclusive && endExclusive <= allSamples.size) {
                "Invalid ${scale.name} shard [$startInclusive, $endExclusive) for ${allSamples.size} samples."
            }
            val samples = allSamples.subList(startInclusive, endExclusive)
            val clipCache = readJsonLines(clipCacheFile).associateByTo(linkedMapOf()) {
                it.getString("image_id")
            }
            computeMissingClip(samples, clipCache, onProgress)
            val records = readJsonLines(recordCacheFile).associateByTo(linkedMapOf()) {
                it.getString("image_id")
            }
            computeMissingG2d(samples, clipCache, records, onProgress)
            ensureNotCancelled()
            require(samples.all { records.containsKey(it.imageId) }) {
                "Shard completed without all requested G2D records."
            }
            onProgress(OxfordPetsG2dProgress("shard_complete", samples.size, samples.size))
            return samples.size
        } finally {
            running.set(false)
        }
    }

    private fun computeMissingClip(
        samples: List<OxfordPetsSample>,
        cache: MutableMap<String, JSONObject>,
        onProgress: (OxfordPetsG2dProgress) -> Unit,
    ) {
        val missing = samples.filterNot { cache.containsKey(it.imageId) }
        if (missing.isEmpty()) {
            onProgress(OxfordPetsG2dProgress("clip_cached", samples.size, samples.size))
            return
        }
        ClipZeroShotRuntime(
            modelFile = clipModel,
            sidecarFile = clipSidecar,
            expectedLabels = OxfordPetsDataset.CLASS_NAMES,
        ).use { clip ->
            missing.forEachIndexed { index, sample ->
                ensureNotCancelled()
                val result = clip.classify(requireNotNull(sample.imageFile))
                val json = JSONObject().apply {
                    put("image_id", sample.imageId)
                    put("elapsed_ms", result.elapsedMs)
                    put("ranking", JSONArray().apply {
                        result.ranking.forEach { candidate ->
                            put(JSONObject().apply {
                                put("id", candidate.id)
                                put("p", candidate.probability)
                            })
                        }
                    })
                }
                appendJsonLine(clipCacheFile, json)
                cache[sample.imageId] = json
                onProgress(OxfordPetsG2dProgress("clip", index + 1, missing.size, sample.imageId))
            }
        }
    }

    private fun computeMissingG2d(
        samples: List<OxfordPetsSample>,
        clipCache: Map<String, JSONObject>,
        records: MutableMap<String, JSONObject>,
        onProgress: (OxfordPetsG2dProgress) -> Unit,
    ) {
        val missing = samples.filterNot { records.containsKey(it.imageId) }
        if (missing.isEmpty()) {
            onProgress(OxfordPetsG2dProgress("vlm_cached", samples.size, samples.size))
            return
        }
        val oneEngine = G2dEngine<Int>(G2dConfig.oneThreshold(highThreshold = 0.70))
        val twoEngine = G2dEngine<Int>(
            G2dConfig.twoThreshold(lowThreshold = 0.60, highThreshold = 0.90),
        )
        QwenVisionRuntime(
            modelFile = qwenModel,
            projectorFile = qwenProjector,
            labels = OxfordPetsDataset.CLASS_NAMES,
        ).use { qwen ->
            val routerModel = RuntimeBridgeG2dRouterModel(
                modelId = qwen.modelId,
                maxTokens = 96,
                chat = { modelId, prompt, maxTokens, temperature ->
                    val wrapped = "<|im_start|>system\nReturn valid JSON only.<|im_end|>\n" +
                        "<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n" +
                        "<think>\n\n</think>\n\n"
                    RuntimeBridge.chat(modelId, wrapped, maxTokens, temperature)
                },
            )
            val agentEngine = AgenticG2dEngine<Int>(
                routeAgent = JsonToolCallingG2dAgent(routerModel),
                fallbackConfig = G2dConfig.twoThreshold(0.60, 0.90),
            )
            missing.forEachIndexed { index, sample ->
                ensureNotCancelled()
                val image = requireNotNull(sample.imageFile)
                val clipJson = requireNotNull(clipCache[sample.imageId])
                val ranking = rankingFromJson(clipJson.getJSONArray("ranking"))
                val standaloneResponse = qwen.standaloneResponse(image)
                var verifierWithProb: QwenVisionResponse? = null
                var verifierNoProb: QwenVisionResponse? = null
                val experts = G2dExperts(
                    standaloneGenerator = G2dStandaloneGenerator {
                        G2dGeneratedOutput.fromText(standaloneResponse.text)
                    },
                    candidateVerifier = G2dCandidateVerifier { input ->
                        val response = if (input.noProb) {
                            verifierNoProb ?: qwen.verifyResponse(image, input).also {
                                verifierNoProb = it
                            }
                        } else {
                            verifierWithProb ?: qwen.verifyResponse(image, input).also {
                                verifierWithProb = it
                            }
                        }
                        G2dGeneratedOutput.fromText(response.text)
                    },
                )
                val vlm = oneEngine.inferWithExpert(
                    clipRanking = ranking,
                    expert = G2dExpert.STANDALONE_GENERATOR,
                    experts = experts,
                )
                val one = oneEngine.infer(ranking, experts)
                val two = twoEngine.infer(ranking, experts)
                val dimensions = imageDimensions(image)
                val agent = agentEngine.infer(
                    datasetName = "Oxford-Pets",
                    sampleId = sample.imageId,
                    imageWidth = dimensions.first,
                    imageHeight = dimensions.second,
                    clipRanking = ranking,
                    experts = experts,
                )
                val json = JSONObject().apply {
                    put("image_id", sample.imageId)
                    put("target", sample.classIndex)
                    put("runtime_revision", "media-kv-cache-v2")
                    put("clip", methodJson(
                        prediction = ranking.first().id,
                        latencyMs = clipJson.getDouble("elapsed_ms"),
                        route = "A",
                        expert = G2dExpert.CLIP.name,
                    ))
                    put("vlm", methodJson(
                        prediction = vlm.prediction,
                        latencyMs = standaloneResponse.totalMs,
                        route = "B",
                        expert = G2dExpert.STANDALONE_GENERATOR.name,
                        raw = standaloneResponse.text,
                    ))
                    put("g2d_one", routedMethodJson(
                        prediction = one.prediction,
                        clipMs = clipJson.getDouble("elapsed_ms"),
                        trace = one.trace,
                        standalone = standaloneResponse,
                        verifier = verifierWithProb,
                    ))
                    put("g2d_two", routedMethodJson(
                        prediction = two.prediction,
                        clipMs = clipJson.getDouble("elapsed_ms"),
                        trace = two.trace,
                        standalone = standaloneResponse,
                        verifier = verifierWithProb,
                    ))
                    val agentExpertMs = when (agent.inference.trace.expert) {
                        G2dExpert.CLIP -> 0.0
                        G2dExpert.STANDALONE_GENERATOR -> standaloneResponse.totalMs
                        G2dExpert.CANDIDATE_VERIFIER -> if (agent.inference.trace.noProb) {
                            verifierNoProb?.totalMs ?: 0.0
                        } else {
                            verifierWithProb?.totalMs ?: 0.0
                        }
                    }
                    put("agentic", methodJson(
                        prediction = agent.inference.prediction,
                        latencyMs = clipJson.getDouble("elapsed_ms") +
                            agent.routing.routerLatencyMs + agentExpertMs,
                        route = agent.inference.trace.route.name,
                        expert = agent.inference.trace.expert.name,
                        raw = agent.inference.trace.rawOutput,
                    ).apply {
                        put("tool", agent.routing.selectedTool.wireName)
                        put("fallback", agent.routing.fallbackUsed)
                        put("router_ms", agent.routing.routerLatencyMs)
                        put("router_raw", agent.routing.rawResponse)
                    })
                }
                appendJsonLine(recordCacheFile, json)
                records[sample.imageId] = json
                onProgress(OxfordPetsG2dProgress("vlm_g2d", index + 1, missing.size, sample.imageId))
            }
        }
    }

    private fun routedMethodJson(
        prediction: Int,
        clipMs: Double,
        trace: G2dInferenceTrace<Int>,
        standalone: QwenVisionResponse,
        verifier: QwenVisionResponse?,
    ): JSONObject {
        val expertMs = when (trace.expert) {
            G2dExpert.CLIP -> 0.0
            G2dExpert.STANDALONE_GENERATOR -> standalone.totalMs
            G2dExpert.CANDIDATE_VERIFIER -> verifier?.totalMs ?: 0.0
        }
        return methodJson(
            prediction = prediction,
            latencyMs = clipMs + expertMs,
            route = trace.route.name,
            expert = trace.expert.name,
            raw = trace.rawOutput,
        )
    }

    private fun methodJson(
        prediction: Int,
        latencyMs: Double,
        route: String,
        expert: String,
        raw: String? = null,
    ): JSONObject = JSONObject().apply {
        put("prediction", prediction)
        put("latency_ms", latencyMs)
        put("route", route)
        put("expert", expert)
        if (raw != null) put("raw", raw)
    }

    private fun aggregateReport(
        scale: OxfordPetsRunScale,
        samples: List<OxfordPetsSample>,
        records: List<JSONObject>,
    ): JSONObject {
        val methodKeys = linkedMapOf(
            "clip" to "CLIP-only",
            "vlm" to "VLM-only",
            "g2d_one" to "G2D 1theta",
            "g2d_two" to "G2D 2theta",
            "agentic" to "Agentic G2D",
        )
        val paper08b = mapOf(
            "clip" to 87.27,
            "vlm" to 40.80,
            "g2d_one" to 87.27,
            "g2d_two" to 87.27,
        )
        val summaries = JSONArray()
        methodKeys.forEach { (key, name) ->
            val methodRows = records.map { it.getJSONObject(key) }
            val correct = methodRows.indices.count { index ->
                methodRows[index].getInt("prediction") == samples[index].classIndex
            }
            val latencies = methodRows.map { it.getDouble("latency_ms") }.sorted()
            summaries.put(JSONObject().apply {
                put("key", key)
                put("name", name)
                put("samples", records.size)
                put("correct", correct)
                put("accuracy_percent", correct * 100.0 / records.size)
                put("p50_latency_ms", percentile(latencies, 0.50))
                put("p95_latency_ms", percentile(latencies, 0.95))
                put("routes", countValues(methodRows, "route"))
                put("experts", countValues(methodRows, "expert"))
                if (key == "agentic") {
                    put("tools", countValues(methodRows, "tool"))
                    put("fallbacks", methodRows.count { it.optBoolean("fallback") })
                    put("router_p50_ms", percentile(
                        methodRows.map { it.optDouble("router_ms", 0.0) }.sorted(),
                        0.50,
                    ))
                }
                paper08b[key]?.let { paper ->
                    put("paper_qwen3_5_0_8b_accuracy_percent", paper)
                    put("delta_vs_paper_percentage_points", correct * 100.0 / records.size - paper)
                }
            })
        }
        return JSONObject().apply {
            put("schema_version", 1)
            put("dataset", "Oxford-IIIT Pet official test.txt")
            put("scale", scale.name)
            put("sample_count", records.size)
            put("selection", if (scale == OxfordPetsRunScale.FULL) {
                "all 3,669 official test entries"
            } else {
                "first ${scale.perClass} official test entries per class"
            })
            put("execution", JSONObject().apply {
                put("runtime", "Android arm64 app process; ONNX Runtime Mobile + llama.cpp/libmtmd")
                put("device_model", Build.MODEL)
                put("device_product", Build.PRODUCT)
                put("android_api", Build.VERSION.SDK_INT)
                put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                put("cpu_threads", Runtime.getRuntime().availableProcessors())
                put("native_optimization", "-O3")
                put("record_runtime_revisions", JSONObject().apply {
                    records.groupingBy { record ->
                        record.optString("runtime_revision", "baseline-v2")
                    }.eachCount().toSortedMap().forEach(::put)
                })
            })
            put("clip", JSONObject().apply {
                put("model", "OpenAI CLIP ViT-B/16")
                put("source", "onnx-community/clip-vit-base-patch16-ONNX")
                put("format", "ONNX FP32 image encoder")
                put("prompt", "a photo of a {class}")
                put("sha256", sha256(clipModel))
            })
            put("vlm", JSONObject().apply {
                put("model", "Qwen3.5-0.8B")
                put("source", "bartowski/Qwen_Qwen3.5-0.8B-GGUF")
                put("quantization", "Q4_K_M text model + BF16 vision projector")
                put("decoding", "greedy, max_new_tokens=30")
                put("image_budget", "448x448 / max 256 merged visual tokens")
                put("model_sha256", sha256(qwenModel))
                put("projector_sha256", sha256(qwenProjector))
            })
            put("thresholds", JSONObject().apply {
                put("one_theta_high", 0.70)
                put("two_theta_low", 0.60)
                put("two_theta_high", 0.90)
            })
            put("methods", summaries)
            put("paper_reference", JSONObject().apply {
                put("qwen3_5_0_8b", JSONObject().apply {
                    put("clip", 87.27)
                    put("vlm", 40.80)
                    put("g2d_one", 87.27)
                    put("g2d_two", 87.27)
                })
                put("qwen3_vl_8b_main", JSONObject().apply {
                    put("clip", 87.27)
                    put("vlm", 85.75)
                    put("g2d_two", 91.66)
                })
            })
        }
    }

    private fun rankingFromJson(array: JSONArray): List<G2dClipCandidate<Int>> =
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getInt("id")
            G2dClipCandidate(id, item.getDouble("p"), OxfordPetsDataset.CLASS_NAMES[id])
        }

    private fun imageDimensions(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Invalid image: $file" }
        return options.outWidth to options.outHeight
    }

    private fun countValues(rows: List<JSONObject>, key: String): JSONObject {
        val counts = rows.mapNotNull { row -> row.optString(key).takeIf(String::isNotBlank) }
            .groupingBy(String::toString)
            .eachCount()
        return JSONObject().apply { counts.toSortedMap().forEach(::put) }
    }

    private fun percentile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = (ceil(q * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun readJsonLines(file: File): List<JSONObject> {
        if (!file.isFile) return emptyList()
        return file.useLines { lines ->
            lines.mapNotNull { line ->
                val repaired = line.trim { character ->
                    character == '\u0000' || character.isWhitespace()
                }
                repaired.takeIf(String::isNotBlank)?.let {
                    runCatching { JSONObject(it) }.getOrNull()
                }
            }.toList()
        }
    }

    private fun appendJsonLine(file: File, value: JSONObject) {
        file.parentFile?.mkdirs()
        if (file.isFile && file.length() > 0L) {
            RandomAccessFile(file, "rw").use { cache ->
                cache.seek(cache.length() - 1L)
                if (cache.read() != '\n'.code) {
                    cache.seek(cache.length())
                    cache.write('\n'.code)
                }
            }
        }
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)).use { writer ->
            writer.append(value.toString())
            writer.newLine()
            writer.flush()
        }
    }

    private fun ensureNotCancelled() {
        check(!cancelRequested.get() && !Thread.currentThread().isInterrupted) {
            "Oxford-Pets device evaluation was cancelled."
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
