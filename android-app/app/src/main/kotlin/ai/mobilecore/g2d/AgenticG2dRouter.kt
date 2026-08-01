package ai.mobilecore.g2d

import org.json.JSONObject
import java.util.Locale
import kotlin.math.ln

/** Branches exposed to the routing model as a closed, auditable tool registry. */
enum class G2dBranchTool(
    val wireName: String,
    val expert: G2dExpert,
    val noProb: Boolean,
) {
    CLIP_DIRECT("clip_direct", G2dExpert.CLIP, false),
    VLM_FULL_LABELS("vlm_full_labels", G2dExpert.STANDALONE_GENERATOR, false),
    CANDIDATE_VERIFIER("candidate_verifier", G2dExpert.CANDIDATE_VERIFIER, false),
    CANDIDATE_VERIFIER_NO_PROB(
        "candidate_verifier_no_prob",
        G2dExpert.CANDIDATE_VERIFIER,
        true,
    );

    companion object {
        fun fromWireName(value: String): G2dBranchTool? = entries.firstOrNull {
            it.wireName == value.trim().lowercase(Locale.US)
        }
    }
}

data class G2dToolSpec(
    val tool: G2dBranchTool,
    val description: String,
    val relativeCost: Int,
)

data class G2dRoutingCandidate(
    val label: String,
    val rank: Int,
    val probability: Double,
)

/** Ground truth is intentionally absent: labels are visible only to the evaluator. */
data class G2dRoutingObservation(
    val datasetName: String,
    val sampleId: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val candidates: List<G2dRoutingCandidate>,
    val entropy: Double,
    val adaptiveK: Int,
    val batteryPercent: Int? = null,
    val temperatureCelsius: Double? = null,
) {
    init {
        require(datasetName.isNotBlank()) { "Dataset name must not be blank." }
        require(sampleId.isNotBlank()) { "Sample ID must not be blank." }
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be positive." }
        require(candidates.isNotEmpty()) { "Routing candidates must not be empty." }
        require(candidates.map { it.rank } == (1..candidates.size).toList()) {
            "Routing candidates must have contiguous one-based ranks."
        }
        require(candidates.all { it.label.isNotBlank() && it.probability in 0.0..1.0 }) {
            "Routing candidates require non-blank labels and normalized probabilities."
        }
        require(entropy.isFinite() && entropy >= 0.0) { "Entropy must be finite and non-negative." }
        require(adaptiveK in 1..candidates.size) { "Adaptive K must fit the candidate list." }
    }

    val topConfidence: Double get() = candidates.first().probability
    val topMargin: Double get() = topConfidence - (candidates.getOrNull(1)?.probability ?: 0.0)
}

data class G2dAgentToolCall(
    val toolName: String,
    val reason: String,
    val confidence: Double,
    val rawResponse: String = "",
)

data class G2dAgentRoutingRequest(
    val observation: G2dRoutingObservation,
    val tools: List<G2dToolSpec>,
)

fun interface G2dRouteAgent {
    fun selectTool(request: G2dAgentRoutingRequest): G2dAgentToolCall
}

data class G2dAgentRoutingTrace(
    val selectedTool: G2dBranchTool,
    val reason: String,
    val agentConfidence: Double,
    val fallbackUsed: Boolean,
    val fallbackReason: String?,
    val routerLatencyMs: Double,
    val rawResponse: String,
)

data class G2dAgenticInferenceResult<ID>(
    val inference: G2dInferenceResult<ID>,
    val routing: G2dAgentRoutingTrace,
)

class G2dToolRegistry private constructor(
    private val definitions: Map<G2dBranchTool, G2dToolSpec>,
) {
    fun <ID> availableTools(experts: G2dExperts<ID>): List<G2dToolSpec> = definitions.values
        .filter { spec ->
            when (spec.tool.expert) {
                G2dExpert.CLIP -> true
                G2dExpert.STANDALONE_GENERATOR -> experts.standaloneGenerator != null
                G2dExpert.CANDIDATE_VERIFIER -> experts.candidateVerifier != null
            }
        }
        .sortedBy { it.relativeCost }

    fun resolve(name: String, allowed: List<G2dToolSpec>): G2dBranchTool? {
        val requested = G2dBranchTool.fromWireName(name) ?: return null
        return requested.takeIf { tool -> allowed.any { it.tool == tool } }
    }

    companion object {
        fun default(): G2dToolRegistry = G2dToolRegistry(
            listOf(
                G2dToolSpec(
                    G2dBranchTool.CLIP_DIRECT,
                    "Return CLIP top-1. Cheapest; prefer only when the visual prior is decisive.",
                    1,
                ),
                G2dToolSpec(
                    G2dBranchTool.CANDIDATE_VERIFIER,
                    "Inspect the image and choose inside the adaptive CLIP shortlist with probabilities.",
                    2,
                ),
                G2dToolSpec(
                    G2dBranchTool.CANDIDATE_VERIFIER_NO_PROB,
                    "Inspect the image and choose inside the shortlist without CLIP probability text.",
                    3,
                ),
                G2dToolSpec(
                    G2dBranchTool.VLM_FULL_LABELS,
                    "Ask the VLM to classify against the full label set when the CLIP prior is unreliable.",
                    4,
                ),
            ).associateBy(G2dToolSpec::tool),
        )
    }
}

/**
 * 4KAgent-inspired orchestration: closed registry -> agent call -> validation -> execution -> trace.
 * Invalid or unavailable calls fall back to the paper's two-threshold policy and are counted.
 */
class AgenticG2dEngine<ID>(
    private val routeAgent: G2dRouteAgent,
    private val registry: G2dToolRegistry = G2dToolRegistry.default(),
    private val inferenceEngine: G2dEngine<ID> = G2dEngine(),
    private val fallbackConfig: G2dConfig = G2dConfig.twoThreshold(0.20, 0.90),
    private val clock: G2dNanoClock = G2dNanoClock(System::nanoTime),
) {
    fun infer(
        datasetName: String,
        sampleId: String,
        imageWidth: Int,
        imageHeight: Int,
        clipRanking: List<G2dClipCandidate<ID>>,
        experts: G2dExperts<ID>,
        batteryPercent: Int? = null,
        temperatureCelsius: Double? = null,
    ): G2dAgenticInferenceResult<ID> {
        val observation = observation(
            datasetName,
            sampleId,
            imageWidth,
            imageHeight,
            clipRanking,
            batteryPercent,
            temperatureCelsius,
        )
        val allowed = registry.availableTools(experts)
        require(allowed.isNotEmpty()) { "No G2D tools are available." }

        val started = clock.nanoTime()
        val call = runCatching {
            routeAgent.selectTool(G2dAgentRoutingRequest(observation, allowed))
        }.getOrElse { error ->
            G2dAgentToolCall("", "", 0.0, error.message.orEmpty())
        }
        val finished = clock.nanoTime()
        val selected = registry.resolve(call.toolName, allowed)
        val fallback = selected == null || !call.confidence.isFinite() || call.confidence !in 0.0..1.0
        val tool = selected?.takeUnless { fallback } ?: fallbackTool(observation, allowed)
        val fallbackReason = if (fallback) {
            "invalid_or_unavailable_tool_call:${call.toolName.ifBlank { "empty" }}"
        } else {
            null
        }
        val result = inferenceEngine.inferWithExpert(
            clipRanking = clipRanking,
            expert = tool.expert,
            experts = experts,
            noProb = tool.noProb,
        )
        return G2dAgenticInferenceResult(
            inference = result,
            routing = G2dAgentRoutingTrace(
                selectedTool = tool,
                reason = call.reason.ifBlank { fallbackReason.orEmpty() },
                agentConfidence = call.confidence.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
                fallbackUsed = fallback,
                fallbackReason = fallbackReason,
                routerLatencyMs = elapsedMillis(started, finished),
                rawResponse = call.rawResponse,
            ),
        )
    }

    private fun observation(
        datasetName: String,
        sampleId: String,
        imageWidth: Int,
        imageHeight: Int,
        clipRanking: List<G2dClipCandidate<ID>>,
        batteryPercent: Int?,
        temperatureCelsius: Double?,
    ): G2dRoutingObservation {
        require(clipRanking.isNotEmpty()) { "CLIP ranking must not be empty." }
        val total = clipRanking.sumOf { it.probability }
        require(total.isFinite() && total >= 0.0) { "CLIP weights must have a finite sum." }
        val uniform = 1.0 / clipRanking.size
        val ranked = clipRanking.mapIndexed { index, candidate ->
            require(candidate.probability.isFinite() && candidate.probability >= 0.0) {
                "CLIP weights must be finite and non-negative."
            }
            Triple(candidate.label, total.takeIf { it > 0.0 }?.let { candidate.probability / it } ?: uniform, index)
        }.sortedWith(compareByDescending<Triple<String, Double, Int>> { it.second }.thenBy { it.third })
        val entropy = ranked.sumOf { (_, probability) ->
            if (probability <= 0.0) 0.0 else -probability * ln(probability)
        }
        val adaptiveK = inferenceEngine.adaptiveCandidateCount(entropy, ranked.size)
        return G2dRoutingObservation(
            datasetName = datasetName,
            sampleId = sampleId,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            candidates = ranked.mapIndexed { index, candidate ->
                G2dRoutingCandidate(candidate.first, index + 1, candidate.second)
            },
            entropy = entropy,
            adaptiveK = adaptiveK,
            batteryPercent = batteryPercent,
            temperatureCelsius = temperatureCelsius,
        )
    }

    private fun fallbackTool(
        observation: G2dRoutingObservation,
        allowed: List<G2dToolSpec>,
    ): G2dBranchTool {
        val preferred = when {
            observation.topConfidence >= fallbackConfig.highThreshold -> G2dBranchTool.CLIP_DIRECT
            observation.topConfidence <= fallbackConfig.lowThreshold -> G2dBranchTool.VLM_FULL_LABELS
            else -> G2dBranchTool.CANDIDATE_VERIFIER
        }
        return preferred.takeIf { tool -> allowed.any { it.tool == tool } }
            ?: allowed.minBy { it.relativeCost }.tool
    }

    private fun elapsedMillis(startNanos: Long, endNanos: Long): Double =
        (endNanos - startNanos).coerceAtLeast(0L) / 1_000_000.0
}

fun interface G2dRouterModel {
    fun generate(prompt: String, imageReference: String?): String
}

/** JSON-only adapter suitable for a local 0.8B/0.9B Qwen router or a multimodal router. */
class JsonToolCallingG2dAgent(
    private val model: G2dRouterModel,
    private val imageReference: (G2dRoutingObservation) -> String? = { null },
) : G2dRouteAgent {
    override fun selectTool(request: G2dAgentRoutingRequest): G2dAgentToolCall {
        val prompt = buildPrompt(request)
        val raw = model.generate(prompt, imageReference(request.observation))
        val json = extractJson(raw)
        return G2dAgentToolCall(
            toolName = json.getString("tool"),
            reason = json.optString("reason"),
            confidence = json.optDouble("confidence", Double.NaN),
            rawResponse = raw,
        )
    }

    fun buildPrompt(request: G2dAgentRoutingRequest): String {
        val observation = request.observation
        val tools = request.tools.joinToString("\n") { spec ->
            "- ${spec.tool.wireName} (cost=${spec.relativeCost}): ${spec.description}"
        }
        val candidates = observation.candidates.take(observation.adaptiveK).joinToString("\n") {
            "- rank=${it.rank}, label=${it.label}, p=${"%.6f".format(Locale.US, it.probability)}"
        }
        return """
            You are the routing controller for zero-shot image classification.
            Choose exactly one registered tool. Do not predict the class label.
            Optimise expected correctness first, then latency and energy. Never assume access to ground truth.

            Dataset: ${observation.datasetName}
            Sample: ${observation.sampleId}
            Image: ${observation.imageWidth}x${observation.imageHeight}
            CLIP top confidence: ${"%.6f".format(Locale.US, observation.topConfidence)}
            CLIP top margin: ${"%.6f".format(Locale.US, observation.topMargin)}
            Distribution entropy: ${"%.6f".format(Locale.US, observation.entropy)}
            Adaptive K: ${observation.adaptiveK}
            Battery: ${observation.batteryPercent?.let { "$it%" } ?: "unknown"}
            Temperature: ${observation.temperatureCelsius?.let { "${"%.1f".format(Locale.US, it)}C" } ?: "unknown"}

            Registered tools:
            $tools

            CLIP shortlist:
            $candidates

            Return one JSON object only:
            {"tool":"registered_tool_name","reason":"brief evidence","confidence":0.0}
        """.trimIndent()
    }

    private fun extractJson(raw: String): JSONObject {
        val direct = runCatching { JSONObject(raw.trim()) }.getOrNull()
        if (direct != null && direct.has("tool")) return direct
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "Router response does not contain a JSON object." }
        return JSONObject(raw.substring(start, end + 1))
    }
}
