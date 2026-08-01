package ai.mobilecore.runtime

import org.json.JSONArray
import org.json.JSONObject

enum class SanityModality(val apiName: String) {
    TEXT("text"),
    IMAGE("image"),
    AUDIO("audio"),
}

data class MultimodalSanityObservation(
    val caseId: String,
    val modality: SanityModality,
    val output: String,
    val expectedTerms: Set<String> = emptySet(),
)

data class MultimodalSanityReport(
    val passed: Boolean,
    val caseCount: Int,
    val distinctOutputCount: Int,
    val failures: List<String>,
) {
    /** Deliberately excludes prompts and model outputs. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("passed", passed)
        put("case_count", caseCount)
        put("distinct_output_count", distinctOutputCount)
        put("failures", JSONArray(failures))
        put("raw_content_persisted", false)
    }
}

/**
 * Aggregate-only gate for catching successful loads that return blank, stubbed,
 * fixed, or obviously unrelated responses. Raw prompts and outputs must not be
 * written to benchmark evidence.
 */
object MultimodalQualitySanity {
    private val forbiddenMarkers = listOf(
        "mock response",
        "native stub response",
        "generated an empty response",
        "model is not loaded",
    )

    fun evaluate(observations: List<MultimodalSanityObservation>): MultimodalSanityReport {
        val failures = mutableListOf<String>()
        val normalized = observations.map { normalize(it.output) }

        observations.forEachIndexed { index, observation ->
            val output = normalized[index]
            if (output.isBlank()) {
                failures += "${observation.caseId}:blank_output"
            }
            if (forbiddenMarkers.any(output::contains)) {
                failures += "${observation.caseId}:fallback_output"
            }
            if (observation.expectedTerms.isNotEmpty() &&
                observation.expectedTerms.none { normalize(it) in output }
            ) {
                failures += "${observation.caseId}:semantic_mismatch"
            }
        }

        val distinct = normalized.filter(String::isNotBlank).toSet().size
        if (observations.size >= 3 && distinct * 2 < observations.size) {
            failures += "set:mode_collapse"
        }
        val coveredModalities = observations.map { it.modality }.toSet()
        if (!coveredModalities.containsAll(SanityModality.entries)) {
            failures += "set:modality_coverage_incomplete"
        }

        return MultimodalSanityReport(
            passed = failures.isEmpty(),
            caseCount = observations.size,
            distinctOutputCount = distinct,
            failures = failures,
        )
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")
}
