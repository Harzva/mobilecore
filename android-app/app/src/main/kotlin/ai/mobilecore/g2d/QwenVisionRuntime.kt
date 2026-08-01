package ai.mobilecore.g2d

import ai.mobilecore.runtime.RuntimeBridge
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.Locale

data class QwenVisionResponse(
    val text: String,
    val totalMs: Double,
    val promptEvalMs: Double,
    val decodeMs: Double,
)

/** Real local image inference through llama.cpp/libmtmd and Qwen3.5-0.8B. */
class QwenVisionRuntime(
    modelFile: File,
    projectorFile: File,
    private val labels: List<String>,
    contextLength: Int = 2048,
    threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
) : Closeable {
    val modelId: String

    init {
        require(modelFile.isFile && modelFile.length() > 0L) { "Qwen GGUF is missing: $modelFile" }
        require(projectorFile.isFile && projectorFile.length() > 0L) {
            "Qwen vision projector is missing: $projectorFile"
        }
        val load = JSONObject(RuntimeBridge.loadModel(modelFile.absolutePath, contextLength, threads))
        require(load.optBoolean("ok")) { load.optString("message", "Unable to load Qwen GGUF.") }
        modelId = load.optString("modelId", modelFile.nameWithoutExtension)
        val projector = JSONObject(
            RuntimeBridge.loadVisionProjector(
                projectorPath = projectorFile.absolutePath,
                threads = threads,
                // Qwen uses one merged visual token per 28x28 pixels. The
                // paper's Oxford-Pets budget is 448x448 => at most 256 tokens.
                imageMaxTokens = 256,
            ),
        )
        require(projector.optBoolean("ok")) {
            projector.optString("message", "Unable to load Qwen vision projector.")
        }
    }

    fun standalone(imageFile: File): G2dGeneratedOutput<Int> = generate(
        imageFile,
        buildString {
            appendLine("Pet-breed classification.")
            appendLine("Choose one label from the full dataset list.")
            append("Categories: ")
            append(labels.joinToString(", ", transform = ::displayLabel))
            appendLine(".")
            append("Reply with only the category name.")
        },
    )

    fun standaloneResponse(imageFile: File): QwenVisionResponse = rawWithMetrics(
        imageFile,
        buildString {
            appendLine("Pet-breed classification.")
            appendLine("Choose one label from the full dataset list.")
            append("Categories: ")
            append(labels.joinToString(", ", transform = ::displayLabel))
            appendLine(".")
            append("Reply with only the category name.")
        },
    )

    fun verify(
        imageFile: File,
        input: G2dVerifierInput<Int>,
    ): G2dGeneratedOutput<Int> = generate(
        imageFile,
        buildString {
            appendLine("Pet-breed classification.")
            append("Shortlist: ")
            append(
                input.candidates.joinToString(", ") { candidate ->
                    if (candidate.probability == null) {
                        displayLabel(candidate.label)
                    } else {
                        "${displayLabel(candidate.label)} (${
                            "%.1f".format(Locale.US, candidate.probability * 100.0)
                        }%)"
                    }
                },
            )
            appendLine(".")
            appendLine("Choose only from this shortlist.")
            append("Reply with only the category name.")
        },
    )

    fun verifyResponse(
        imageFile: File,
        input: G2dVerifierInput<Int>,
    ): QwenVisionResponse = rawWithMetrics(
        imageFile,
        buildVerifierPrompt(input),
    )

    fun raw(imageFile: File, prompt: String): String {
        return rawWithMetrics(imageFile, prompt).text
    }

    fun rawWithMetrics(imageFile: File, prompt: String): QwenVisionResponse {
        require(imageFile.isFile) { "Image is missing: $imageFile" }
        val response = JSONObject(
            RuntimeBridge.visionChat(
                modelId = modelId,
                imagePath = imageFile.absolutePath,
                prompt = prompt,
                maxTokens = 30,
            ),
        )
        require(response.optBoolean("ok")) {
            response.optString("message", "Qwen vision inference failed.")
        }
        return QwenVisionResponse(
            text = response.getString("message").trim(),
            totalMs = response.optDouble("totalMs", 0.0),
            promptEvalMs = response.optDouble("promptEvalMs", 0.0),
            decodeMs = response.optDouble("decodeMs", 0.0),
        )
    }

    private fun generate(imageFile: File, prompt: String): G2dGeneratedOutput<Int> =
        G2dGeneratedOutput.fromText(raw(imageFile, prompt))

    private fun buildVerifierPrompt(input: G2dVerifierInput<Int>): String = buildString {
        appendLine("Pet-breed classification.")
        append("Shortlist: ")
        append(
            input.candidates.joinToString(", ") { candidate ->
                if (candidate.probability == null) {
                    displayLabel(candidate.label)
                } else {
                    "${displayLabel(candidate.label)} (${
                        "%.1f".format(Locale.US, candidate.probability * 100.0)
                    }%)"
                }
            },
        )
        appendLine(".")
        appendLine("Choose only from this shortlist.")
        append("Reply with only the category name.")
    }

    override fun close() {
        RuntimeBridge.unload()
    }

    private fun displayLabel(value: String): String = value.replace('_', ' ')
}
