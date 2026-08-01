package ai.mobilecore.g2d

import kotlin.math.ln
import kotlin.math.roundToInt

/** Selects the paper's one-threshold or two-threshold dispatch rule. */
enum class G2dVariant {
    ONE_THRESHOLD,
    TWO_THRESHOLD,
    AGENTIC,
}

/**
 * Route names follow the paper. In ONE_THRESHOLD, Route B is the verifier;
 * in TWO_THRESHOLD, Route B is generator-only and Route C is the verifier.
 * [G2dExpert] removes that otherwise ambiguous route-B meaning from traces.
 */
enum class G2dRoute {
    A,
    B,
    C,
}

enum class G2dExpert {
    CLIP,
    STANDALONE_GENERATOR,
    CANDIDATE_VERIFIER,
}

enum class G2dProjectionMethod {
    CLIP_TOP_ONE,
    DIRECT_ID,
    EXACT_LABEL,
    NORMALIZED_LABEL,
    FUZZY_LABEL,
    FALLBACK_TO_CLIP_TOP_ONE,
}

/**
 * A member of the complete CLIP ranking supplied to [G2dEngine.infer].
 *
 * [probability] may be an unnormalised non-negative weight. The engine
 * normalises all supplied weights before routing and entropy calculation.
 * Callers should therefore provide the complete class or retrieval corpus
 * distribution, rather than an already-truncated top-K list.
 */
data class G2dClipCandidate<ID>(
    val id: ID,
    val probability: Double,
    val label: String = id.toString(),
)

/** The candidate prior exposed to the VLM verifier. */
data class G2dVerifierCandidate<ID>(
    val id: ID,
    val label: String,
    val rank: Int,
    val probability: Double?,
)

data class G2dVerifierInput<ID>(
    val candidates: List<G2dVerifierCandidate<ID>>,
    val noProb: Boolean,
) {
    init {
        require(candidates.isNotEmpty()) { "Verifier candidates must not be empty." }
        require(candidates.map { it.id }.distinct().size == candidates.size) {
            "Verifier candidate IDs must be unique."
        }
        require(candidates.all { (it.probability == null) == noProb }) {
            "No-Prob verifier inputs must omit every probability."
        }
    }
}

/**
 * Generators may return a typed ID (preferred) or direct-label text. Text is
 * deterministically projected to the selected label space before it can leave
 * the engine.
 */
data class G2dGeneratedOutput<ID>(
    val id: ID? = null,
    val rawText: String? = null,
) {
    init {
        require(id != null || !rawText.isNullOrBlank()) {
            "Generated output must contain an ID or non-blank direct-label text."
        }
    }

    companion object {
        fun <ID> fromId(id: ID): G2dGeneratedOutput<ID> = G2dGeneratedOutput(id = id)

        fun <ID> fromText(text: String): G2dGeneratedOutput<ID> =
            G2dGeneratedOutput(rawText = text)
    }
}

fun interface G2dStandaloneGenerator<ID> {
    fun predict(): G2dGeneratedOutput<ID>
}

fun interface G2dCandidateVerifier<ID> {
    fun predict(input: G2dVerifierInput<ID>): G2dGeneratedOutput<ID>
}

data class G2dExperts<ID>(
    val standaloneGenerator: G2dStandaloneGenerator<ID>? = null,
    val candidateVerifier: G2dCandidateVerifier<ID>? = null,
)

/**
 * The paper fixes ONE_THRESHOLD high confidence at 0.70. TWO_THRESHOLD values
 * are dataset/use-case dependent, so callers should tune [lowThreshold] and
 * [highThreshold] from cached predictions for production deployments.
 */
data class G2dConfig(
    val variant: G2dVariant = G2dVariant.ONE_THRESHOLD,
    val highThreshold: Double = 0.70,
    val lowThreshold: Double = 0.0,
    val minCandidates: Int = 3,
    val maxCandidates: Int = 10,
    val lowEntropy: Double = 0.5,
    val highEntropy: Double = 2.0,
    val noProb: Boolean = false,
    val fuzzyMatchThreshold: Double = 0.85,
) {
    init {
        require(highThreshold in 0.0..1.0) { "High threshold must be in [0, 1]." }
        require(lowThreshold in 0.0..1.0) { "Low threshold must be in [0, 1]." }
        require(lowThreshold <= highThreshold) {
            "Low threshold must not exceed high threshold."
        }
        require(minCandidates > 0) { "Minimum candidate count must be positive." }
        require(maxCandidates >= minCandidates) {
            "Maximum candidate count must be at least the minimum."
        }
        require(lowEntropy >= 0.0) { "Low entropy boundary must be non-negative." }
        require(highEntropy > lowEntropy) {
            "High entropy boundary must exceed the low boundary."
        }
        require(fuzzyMatchThreshold in 0.0..1.0) {
            "Fuzzy-match threshold must be in [0, 1]."
        }
    }

    companion object {
        fun oneThreshold(
            highThreshold: Double = 0.70,
            noProb: Boolean = false,
        ): G2dConfig = G2dConfig(
            variant = G2dVariant.ONE_THRESHOLD,
            highThreshold = highThreshold,
            lowThreshold = 0.0,
            noProb = noProb,
        )

        fun twoThreshold(
            lowThreshold: Double,
            highThreshold: Double,
            noProb: Boolean = false,
        ): G2dConfig = G2dConfig(
            variant = G2dVariant.TWO_THRESHOLD,
            highThreshold = highThreshold,
            lowThreshold = lowThreshold,
            noProb = noProb,
        )
    }
}

data class G2dCandidateTrace<ID>(
    val id: ID,
    val label: String,
    val rank: Int,
    val probability: Double,
)

data class G2dInferenceTrace<ID>(
    val variant: G2dVariant,
    val route: G2dRoute,
    val expert: G2dExpert,
    val corpusSize: Int,
    val highThreshold: Double,
    val lowThreshold: Double?,
    val topConfidence: Double,
    val selectedLabel: String,
    val selectedConfidence: Double,
    val entropy: Double,
    val adaptiveK: Int,
    val candidates: List<G2dCandidateTrace<ID>>,
    val noProb: Boolean,
    val rawOutput: String?,
    val projectionMethod: G2dProjectionMethod,
    val projectionSimilarity: Double?,
    val routingLatencyMs: Double,
    val expertLatencyMs: Double,
    val totalLatencyMs: Double,
)

data class G2dInferenceResult<ID>(
    val prediction: ID,
    val trace: G2dInferenceTrace<ID>,
)

fun interface G2dNanoClock {
    fun nanoTime(): Long
}

/**
 * Pure Kotlin implementation of the G2D dispatch layer. It contains no image,
 * Android, CLIP, or VLM runtime dependency, so the same API can route class
 * labels, photo IDs, document IDs, or any other stable candidate identifier.
 * Model callbacks are synchronous; Android callers should invoke [infer] from
 * their existing worker/background executor.
 */
class G2dEngine<ID>(
    val config: G2dConfig = G2dConfig(),
    private val clock: G2dNanoClock = G2dNanoClock(System::nanoTime),
) {
    fun infer(
        clipRanking: List<G2dClipCandidate<ID>>,
        experts: G2dExperts<ID> = G2dExperts(),
    ): G2dInferenceResult<ID> = inferInternal(
        clipRanking = clipRanking,
        experts = experts,
        forcedExpert = null,
        noProb = config.noProb,
        traceVariant = config.variant,
    )

    /** Executes one branch selected by an external agent after registry validation. */
    fun inferWithExpert(
        clipRanking: List<G2dClipCandidate<ID>>,
        expert: G2dExpert,
        experts: G2dExperts<ID> = G2dExperts(),
        noProb: Boolean = config.noProb,
    ): G2dInferenceResult<ID> = inferInternal(
        clipRanking = clipRanking,
        experts = experts,
        forcedExpert = expert,
        noProb = noProb,
        traceVariant = G2dVariant.AGENTIC,
    )

    private fun inferInternal(
        clipRanking: List<G2dClipCandidate<ID>>,
        experts: G2dExperts<ID>,
        forcedExpert: G2dExpert?,
        noProb: Boolean,
        traceVariant: G2dVariant,
    ): G2dInferenceResult<ID> {
        val totalStarted = clock.nanoTime()
        val ranking = normaliseAndRank(clipRanking)
        val entropy = entropy(ranking.map { it.probability })
        val adaptiveK = adaptiveCandidateCount(entropy, ranking.size)
        val shortlist = ranking.take(adaptiveK)
        val topConfidence = ranking.first().probability
        val decision = forcedExpert?.let(::decisionForExpert) ?: decideRoute(topConfidence)
        val routingFinished = clock.nanoTime()

        val expertStarted = clock.nanoTime()
        val resolved = when (decision.expert) {
            G2dExpert.CLIP -> ProjectedOutput(
                candidate = ranking.first(),
                method = G2dProjectionMethod.CLIP_TOP_ONE,
                similarity = null,
                rawOutput = null,
            )

            G2dExpert.STANDALONE_GENERATOR -> {
                val generator = requireNotNull(experts.standaloneGenerator) {
                    "Route B selected, but no standalone generator was supplied."
                }
                projectOutput(generator.predict(), ranking)
            }

            G2dExpert.CANDIDATE_VERIFIER -> {
                val verifier = requireNotNull(experts.candidateVerifier) {
                    "Verifier route selected, but no candidate verifier was supplied."
                }
                val verifierInput = G2dVerifierInput(
                    candidates = shortlist.mapIndexed { index, candidate ->
                        G2dVerifierCandidate(
                            id = candidate.id,
                            label = candidate.label,
                            rank = index + 1,
                            probability = candidate.probability.takeUnless { noProb },
                        )
                    },
                    noProb = noProb,
                )
                projectOutput(verifier.predict(verifierInput), shortlist)
            }
        }
        val expertFinished = clock.nanoTime()
        val selectedConfidence = ranking
            .firstOrNull { it.id == resolved.candidate.id }
            ?.probability
            ?: 0.0
        val totalFinished = clock.nanoTime()

        return G2dInferenceResult(
            prediction = resolved.candidate.id,
            trace = G2dInferenceTrace(
                variant = traceVariant,
                route = decision.route,
                expert = decision.expert,
                corpusSize = ranking.size,
                highThreshold = config.highThreshold,
                lowThreshold = config.lowThreshold.takeIf {
                    traceVariant == G2dVariant.TWO_THRESHOLD
                },
                topConfidence = topConfidence,
                selectedLabel = resolved.candidate.label,
                selectedConfidence = selectedConfidence,
                entropy = entropy,
                adaptiveK = adaptiveK,
                candidates = shortlist.mapIndexed { index, candidate ->
                    G2dCandidateTrace(
                        id = candidate.id,
                        label = candidate.label,
                        rank = index + 1,
                        probability = candidate.probability,
                    )
                },
                noProb = noProb,
                rawOutput = resolved.rawOutput,
                projectionMethod = resolved.method,
                projectionSimilarity = resolved.similarity,
                routingLatencyMs = elapsedMillis(totalStarted, routingFinished),
                expertLatencyMs = elapsedMillis(expertStarted, expertFinished),
                totalLatencyMs = elapsedMillis(totalStarted, totalFinished),
            ),
        )
    }

    /** Equation (3), rounded to the nearest usable integer and corpus-clamped. */
    fun adaptiveCandidateCount(entropy: Double, availableCandidates: Int): Int {
        require(entropy.isFinite() && entropy >= 0.0) {
            "Entropy must be finite and non-negative."
        }
        require(availableCandidates > 0) { "At least one candidate is required." }

        val desired = when {
            entropy <= config.lowEntropy -> config.minCandidates
            entropy >= config.highEntropy -> config.maxCandidates
            else -> {
                val ratio = (entropy - config.lowEntropy) /
                    (config.highEntropy - config.lowEntropy)
                (config.minCandidates +
                    (config.maxCandidates - config.minCandidates) * ratio).roundToInt()
            }
        }
        return desired.coerceIn(1, availableCandidates)
    }

    private fun decideRoute(topConfidence: Double): RouteDecision = when (config.variant) {
        G2dVariant.ONE_THRESHOLD -> {
            if (topConfidence >= config.highThreshold) {
                RouteDecision(G2dRoute.A, G2dExpert.CLIP)
            } else {
                // The paper names the verifier branch Route B in G2D(1theta).
                RouteDecision(G2dRoute.B, G2dExpert.CANDIDATE_VERIFIER)
            }
        }

        G2dVariant.TWO_THRESHOLD -> when {
            // High is checked first, matching Algorithm 1 when thresholds touch.
            topConfidence >= config.highThreshold ->
                RouteDecision(G2dRoute.A, G2dExpert.CLIP)
            topConfidence <= config.lowThreshold ->
                RouteDecision(G2dRoute.B, G2dExpert.STANDALONE_GENERATOR)
            else -> RouteDecision(G2dRoute.C, G2dExpert.CANDIDATE_VERIFIER)
        }

        G2dVariant.AGENTIC -> error("Agentic routing must call inferWithExpert after tool validation.")
    }

    private fun decisionForExpert(expert: G2dExpert): RouteDecision = when (expert) {
        G2dExpert.CLIP -> RouteDecision(G2dRoute.A, expert)
        G2dExpert.STANDALONE_GENERATOR -> RouteDecision(G2dRoute.B, expert)
        G2dExpert.CANDIDATE_VERIFIER -> RouteDecision(G2dRoute.C, expert)
    }

    private fun normaliseAndRank(
        candidates: List<G2dClipCandidate<ID>>,
    ): List<RankedCandidate<ID>> {
        require(candidates.isNotEmpty()) { "CLIP ranking must not be empty." }
        require(candidates.all { it.id != null }) { "Candidate IDs must not be null." }
        require(candidates.map { it.id }.distinct().size == candidates.size) {
            "Candidate IDs must be unique."
        }
        require(candidates.all { it.label.isNotBlank() }) {
            "Candidate labels must not be blank."
        }
        require(candidates.all { it.probability.isFinite() && it.probability >= 0.0 }) {
            "Candidate probabilities must be finite and non-negative."
        }
        val normalisedLabels = candidates.map { normaliseLabel(it.label) }
        require(normalisedLabels.distinct().size == normalisedLabels.size) {
            "Candidate labels must remain unique after normalization."
        }

        val total = candidates.sumOf { it.probability }
        val denominator = total.takeIf { it > 0.0 }
        val uniform = 1.0 / candidates.size
        return candidates
            .mapIndexed { inputOrder, candidate ->
                RankedCandidate(
                    id = candidate.id,
                    label = candidate.label,
                    probability = denominator?.let { candidate.probability / it } ?: uniform,
                    inputOrder = inputOrder,
                )
            }
            .sortedWith(
                compareByDescending<RankedCandidate<ID>> { it.probability }
                    .thenBy { it.inputOrder }
            )
    }

    private fun entropy(probabilities: List<Double>): Double = probabilities.sumOf { value ->
        if (value <= 0.0) 0.0 else -value * ln(value)
    }

    private fun projectOutput(
        output: G2dGeneratedOutput<ID>,
        allowed: List<RankedCandidate<ID>>,
    ): ProjectedOutput<ID> {
        output.id?.let { id ->
            allowed.firstOrNull { it.id == id }?.let { candidate ->
                return ProjectedOutput(
                    candidate = candidate,
                    method = G2dProjectionMethod.DIRECT_ID,
                    similarity = 1.0,
                    rawOutput = output.rawText,
                )
            }
        }

        val raw = output.rawText.orEmpty()
        val variants = labelOutputVariants(raw)
        variants.forEach { variant ->
            allowed.firstOrNull { it.label == variant }?.let { candidate ->
                return ProjectedOutput(
                    candidate = candidate,
                    method = G2dProjectionMethod.EXACT_LABEL,
                    similarity = 1.0,
                    rawOutput = output.rawText,
                )
            }
        }

        val normalisedVariants = variants.map(::normaliseLabel).filter(String::isNotBlank).distinct()
        normalisedVariants.forEach { normalisedOutput ->
            allowed.firstOrNull { normaliseLabel(it.label) == normalisedOutput }?.let { candidate ->
                return ProjectedOutput(
                    candidate = candidate,
                    method = G2dProjectionMethod.NORMALIZED_LABEL,
                    similarity = 1.0,
                    rawOutput = output.rawText,
                )
            }
        }

        val fuzzy = allowed
            .flatMap { candidate ->
                normalisedVariants.map { variant ->
                    candidate to sequenceSimilarity(variant, normaliseLabel(candidate.label))
                }
            }
            .maxByOrNull { it.second }
        if (fuzzy != null && fuzzy.second >= config.fuzzyMatchThreshold) {
            return ProjectedOutput(
                candidate = fuzzy.first,
                method = G2dProjectionMethod.FUZZY_LABEL,
                similarity = fuzzy.second,
                rawOutput = output.rawText,
            )
        }

        // Runtime token-trie decoding should normally prevent this path. The
        // deterministic fallback preserves the core invariant even when a
        // backend cannot expose token-level constrained decoding.
        return ProjectedOutput(
            candidate = allowed.first(),
            method = G2dProjectionMethod.FALLBACK_TO_CLIP_TOP_ONE,
            similarity = fuzzy?.second,
            rawOutput = output.rawText,
        )
    }

    private fun normaliseLabel(value: String): String = value
        .trim()
        .replace(NUMBERED_PREFIX, "")
        .lowercase()
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(DISALLOWED_PUNCTUATION, " ")
        .replace(MULTIPLE_SPACES, " ")
        .trim()

    private fun labelOutputVariants(raw: String): List<String> {
        val withoutThinking = raw.replace(THINK_BLOCK, " ").trim()
        val finalLine = withoutThinking.lineSequence().map(String::trim).filter(String::isNotBlank).lastOrNull()
        val unprefixed = finalLine?.replace(FINAL_ANSWER_PREFIX, "")?.trim()
        return listOfNotNull(raw.trim(), withoutThinking, finalLine, unprefixed)
            .filter(String::isNotBlank)
            .distinct()
    }

    /** Deterministic LCS ratio with the same 0..1 interpretation as SequenceMatcher. */
    private fun sequenceSimilarity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val previous = IntArray(right.length + 1)
        for (leftIndex in left.indices) {
            var diagonal = 0
            for (rightIndex in right.indices) {
                val old = previous[rightIndex + 1]
                previous[rightIndex + 1] = if (left[leftIndex] == right[rightIndex]) {
                    diagonal + 1
                } else {
                    maxOf(previous[rightIndex + 1], previous[rightIndex])
                }
                diagonal = old
            }
        }
        return 2.0 * previous[right.length] / (left.length + right.length)
    }

    private fun elapsedMillis(startNanos: Long, endNanos: Long): Double =
        (endNanos - startNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND

    private data class RouteDecision(
        val route: G2dRoute,
        val expert: G2dExpert,
    )

    private data class RankedCandidate<ID>(
        val id: ID,
        val label: String,
        val probability: Double,
        val inputOrder: Int,
    )

    private data class ProjectedOutput<ID>(
        val candidate: RankedCandidate<ID>,
        val method: G2dProjectionMethod,
        val similarity: Double?,
        val rawOutput: String?,
    )

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
        private val NUMBERED_PREFIX = Regex("""^(?:\(?\d+\)?[.):\]-]\s*|[-*•]\s*)""")
        private val DISALLOWED_PUNCTUATION = Regex("""[^\p{L}\p{N}/&'\s]""")
        private val MULTIPLE_SPACES = Regex("""\s+""")
        private val THINK_BLOCK = Regex("""(?is)<think>.*?</think>""")
        private val FINAL_ANSWER_PREFIX = Regex(
            """(?i)^(?:final\s+answer|answer|category|label)\s*[:：-]\s*""",
        )
    }
}
