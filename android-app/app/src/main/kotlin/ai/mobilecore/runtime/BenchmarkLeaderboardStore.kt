package ai.mobilecore.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BenchmarkLeaderboardEntry(
    val runId: String,
    val specId: String,
    val createdAtMs: Long,
    val deviceName: String,
    val modelId: String,
    val quantization: String,
    val modelSizeBytes: Long,
    val score: BenchmarkScore,
    val loadTimeMs: Long,
    val decodeTokensPerSecond: Double,
    val firstTokenMs: Long,
    val totalMs: Long,
    val memoryPeakMb: Long
) {
    fun toJson(rank: Int? = null): JSONObject {
        return JSONObject().apply {
            if (rank != null) put("rank", rank)
            put("run_id", runId)
            put("spec_id", specId)
            put("created_at_ms", createdAtMs)
            put("device_name", deviceName)
            put("model_id", modelId)
            put("quantization", quantization)
            put("model_size_bytes", modelSizeBytes)
            put(
                "score",
                JSONObject().apply {
                    put("total", score.total)
                    put("speed", score.speed)
                    put("response", score.response)
                    put("memory", score.memory)
                    put("stability", score.stability)
                }
            )
            put("load_time_ms", loadTimeMs)
            put("decode_tokens_per_second", decodeTokensPerSecond)
            put("first_token_ms", firstTokenMs)
            put("total_ms", totalMs)
            put("memory_peak_mb", memoryPeakMb)
        }
    }
}

class BenchmarkLeaderboardStore(context: Context) {
    private val leaderboardFile = File(context.filesDir, "benchmark_leaderboard.json")

    @Synchronized
    fun record(result: BenchmarkResult, score: BenchmarkScore): Pair<BenchmarkLeaderboardEntry, Int> {
        val entry = BenchmarkLeaderboardEntry(
            runId = "run-${System.currentTimeMillis()}",
            specId = result.spec.id,
            createdAtMs = System.currentTimeMillis(),
            deviceName = result.deviceName,
            modelId = result.modelId,
            quantization = result.quantization,
            modelSizeBytes = result.modelSizeBytes,
            score = score,
            loadTimeMs = result.loadTimeMs,
            decodeTokensPerSecond = result.decodeTokensPerSecond,
            firstTokenMs = result.firstTokenMs,
            totalMs = result.totalMs,
            memoryPeakMb = result.memoryPeakMb
        )
        val updated = (entries() + entry)
            .sortedByDescending { it.score.total }
            .take(50)
        write(updated)
        val rank = updated.indexOfFirst { it.runId == entry.runId }.takeIf { it >= 0 }?.plus(1) ?: updated.size
        return entry to rank
    }

    @Synchronized
    fun entries(): List<BenchmarkLeaderboardEntry> {
        if (!leaderboardFile.isFile) return emptyList()
        val root = runCatching { JSONObject(leaderboardFile.readText()) }.getOrNull() ?: return emptyList()
        val runs = root.optJSONArray("runs") ?: return emptyList()
        val result = ArrayList<BenchmarkLeaderboardEntry>()
        for (index in 0 until runs.length()) {
            val item = runs.optJSONObject(index) ?: continue
            val scoreJson = item.optJSONObject("score") ?: JSONObject()
            val modelId = item.optString("model_id", "")
            val quantization = item.optString("quantization", "").ifBlank {
                inferQuantization(modelId)
            }.let { value ->
                if (value == "unknown") inferQuantization(modelId) else value
            }
            val storedScore = BenchmarkScore(
                total = scoreJson.optInt("total", 0),
                speed = scoreJson.optInt("speed", 0),
                response = scoreJson.optInt("response", 0),
                memory = scoreJson.optInt("memory", 0),
                stability = scoreJson.optInt("stability", 0)
            )
            result.add(
                BenchmarkLeaderboardEntry(
                    runId = item.optString("run_id", "run-$index"),
                    specId = item.optString("spec_id", "mobilecore-benchmark-v1"),
                    createdAtMs = item.optLong("created_at_ms", 0L),
                    deviceName = item.optString("device_name", ""),
                    modelId = modelId,
                    quantization = quantization,
                    modelSizeBytes = item.optLong("model_size_bytes", 0L),
                    score = BenchmarkScorer.normalizeStoredScore(storedScore, quantization),
                    loadTimeMs = item.optLong("load_time_ms", 0L),
                    decodeTokensPerSecond = item.optDouble("decode_tokens_per_second", 0.0),
                    firstTokenMs = item.optLong("first_token_ms", 0L),
                    totalMs = item.optLong("total_ms", 0L),
                    memoryPeakMb = item.optLong("memory_peak_mb", 0L)
                )
            )
        }
        return result.sortedByDescending { it.score.total }
    }

    private fun inferQuantization(modelId: String): String {
        return GgufMetadataReader.read(File("$modelId.gguf")).quantization
    }

    fun toJson(maxItems: Int = 10): JSONObject {
        val limited = entries().take(maxItems.coerceAtLeast(1))
        return JSONObject().apply {
            put("object", "leaderboard")
            put("scope", "local")
            put("spec_id", "mobilecore-benchmark-v1")
            put("count", limited.size)
            put(
                "data",
                JSONArray().apply {
                    limited.forEachIndexed { index, entry ->
                        put(entry.toJson(rank = index + 1))
                    }
                }
            )
        }
    }

    private fun write(entries: List<BenchmarkLeaderboardEntry>) {
        val root = JSONObject()
        root.put(
            "runs",
            JSONArray().apply {
                entries.forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("run_id", entry.runId)
                            put("spec_id", entry.specId)
                            put("created_at_ms", entry.createdAtMs)
                            put("device_name", entry.deviceName)
                            put("model_id", entry.modelId)
                            put("quantization", entry.quantization)
                            put("model_size_bytes", entry.modelSizeBytes)
                            put(
                                "score",
                                JSONObject().apply {
                                    put("total", entry.score.total)
                                    put("speed", entry.score.speed)
                                    put("response", entry.score.response)
                                    put("memory", entry.score.memory)
                                    put("stability", entry.score.stability)
                                }
                            )
                            put("load_time_ms", entry.loadTimeMs)
                            put("decode_tokens_per_second", entry.decodeTokensPerSecond)
                            put("first_token_ms", entry.firstTokenMs)
                            put("total_ms", entry.totalMs)
                            put("memory_peak_mb", entry.memoryPeakMb)
                        }
                    )
                }
            }
        )
        leaderboardFile.writeText(root.toString(2))
    }

}
