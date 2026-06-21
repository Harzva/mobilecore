package ai.mobilecore.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File

class ModelBenchmarkStore(context: Context) {
    private val benchmarkFile = File(context.filesDir, "model_benchmarks.json")

    @Synchronized
    fun record(modelId: String, result: ChatResult): ModelBenchmark {
        val normalizedId = modelId.ifBlank { result.model }
        val existing = snapshot()[normalizedId]
        val samples = (existing?.samples ?: 0) + 1
        val averageTps = rollingAverage(
            previousAverage = existing?.averageDecodeTokensPerSecond ?: 0.0,
            previousSamples = existing?.samples ?: 0,
            next = result.decodeTokensPerSecond
        )
        val averageFirstToken = rollingAverage(
            previousAverage = (existing?.averageFirstTokenMs ?: 0).toDouble(),
            previousSamples = existing?.samples ?: 0,
            next = result.firstTokenMs.toDouble()
        ).toInt()

        val benchmark = ModelBenchmark(
            modelId = normalizedId,
            samples = samples,
            lastDecodeTokensPerSecond = result.decodeTokensPerSecond,
            averageDecodeTokensPerSecond = averageTps,
            lastFirstTokenMs = result.firstTokenMs,
            averageFirstTokenMs = averageFirstToken,
            lastPromptEvalMs = result.promptEvalMs,
            lastDecodeMs = result.decodeMs,
            lastTotalMs = result.totalMs,
            memoryPeakMb = maxOf(result.memoryPeakMb, existing?.memoryPeakMb ?: 0),
            updatedAtMs = System.currentTimeMillis()
        )

        val updated = snapshot().toMutableMap()
        updated[normalizedId] = benchmark
        write(updated)
        return benchmark
    }

    @Synchronized
    fun snapshot(): Map<String, ModelBenchmark> {
        if (!benchmarkFile.isFile) return emptyMap()
        val root = runCatching { JSONObject(benchmarkFile.readText()) }.getOrNull() ?: return emptyMap()
        val items = root.optJSONObject("models") ?: return emptyMap()
        return items.keys().asSequence().mapNotNull { key ->
            val value = items.optJSONObject(key) ?: return@mapNotNull null
            key to ModelBenchmark(
                modelId = value.optString("model_id", key),
                samples = value.optInt("samples", 0),
                lastDecodeTokensPerSecond = value.optDouble("last_decode_tokens_per_second", 0.0),
                averageDecodeTokensPerSecond = value.optDouble("average_decode_tokens_per_second", 0.0),
                lastFirstTokenMs = value.optInt("last_first_token_ms", 0),
                averageFirstTokenMs = value.optInt("average_first_token_ms", 0),
                lastPromptEvalMs = value.optInt("last_prompt_eval_ms", 0),
                lastDecodeMs = value.optInt("last_decode_ms", 0),
                lastTotalMs = value.optInt("last_total_ms", 0),
                memoryPeakMb = value.optInt("memory_peak_mb", 0),
                updatedAtMs = value.optLong("updated_at_ms", 0L)
            )
        }.toMap()
    }

    private fun write(values: Map<String, ModelBenchmark>) {
        val root = JSONObject()
        root.put("models", JSONObject().apply {
            values.forEach { (key, benchmark) ->
                put(key, JSONObject().apply {
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
                })
            }
        })
        benchmarkFile.writeText(root.toString(2))
    }

    private fun rollingAverage(previousAverage: Double, previousSamples: Int, next: Double): Double {
        if (next <= 0.0) return previousAverage
        return if (previousSamples <= 0) {
            next
        } else {
            (previousAverage * previousSamples + next) / (previousSamples + 1)
        }
    }
}
