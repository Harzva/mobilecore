package ai.mobilecore.runtime

import kotlin.math.roundToInt

data class BenchmarkSpec(
    val id: String,
    val version: Int,
    val prompt: String,
    val maxTokens: Int,
    val temperature: Float,
    val contextLength: Int,
    val threads: Int
) {
    companion object {
        fun v1(threads: Int) = BenchmarkSpec(
            id = "mobilecore-benchmark-v1",
            version = 1,
            prompt = "Only output this exact sentence: MobileCore runs GGUF language models locally on your phone.",
            maxTokens = 24,
            temperature = 0.2f,
            contextLength = 2048,
            threads = threads.coerceAtLeast(1)
        )
    }
}

data class BenchmarkResult(
    val spec: BenchmarkSpec,
    val deviceName: String,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val coreCount: Int,
    val modelId: String,
    val modelPath: String,
    val quantization: String,
    val modelSizeBytes: Long,
    val backend: String,
    val loadTimeMs: Long,
    val firstTokenMs: Long,
    val totalMs: Long,
    val decodeTokensPerSecond: Double,
    val memoryPeakMb: Long,
    val completed: Boolean
)

data class BenchmarkScore(
    val total: Int,
    val speed: Int,
    val response: Int,
    val memory: Int,
    val stability: Int
)

object BenchmarkScorer {
    fun score(result: BenchmarkResult): BenchmarkScore {
        val speed = scoreHigherIsBetter(result.decodeTokensPerSecond, excellent = 12.0, cap = 400)
        val response = scoreLowerIsBetter(result.firstTokenMs.toDouble(), excellent = 900.0, poor = 6500.0, cap = 230)
        val memoryRatio = result.memoryPeakMb.toDouble() / result.availableRamMb.coerceAtLeast(512L).toDouble()
        val memory = scoreLowerIsBetter(memoryRatio, excellent = 0.12, poor = 0.85, cap = 220)
        val stability = if (result.completed) {
            (150 - quantizationStabilityPenalty(result.quantization)).coerceIn(0, 150)
        } else {
            0
        }
        val total = speed + response + memory + stability
        return BenchmarkScore(
            total = total.coerceIn(0, 1000),
            speed = speed,
            response = response,
            memory = memory,
            stability = stability
        )
    }

    fun normalizeStoredScore(score: BenchmarkScore, quantization: String): BenchmarkScore {
        val stability = score.stability
            .coerceAtMost((150 - quantizationStabilityPenalty(quantization)).coerceIn(0, 150))
        return BenchmarkScore(
            total = (score.speed + score.response + score.memory + stability).coerceIn(0, 1000),
            speed = score.speed,
            response = score.response,
            memory = score.memory,
            stability = stability
        )
    }

    private fun scoreHigherIsBetter(value: Double, excellent: Double, cap: Int): Int {
        if (value <= 0.0) return 0
        return (value / excellent * cap).roundToInt().coerceIn(0, cap)
    }

    private fun scoreLowerIsBetter(value: Double, excellent: Double, poor: Double, cap: Int): Int {
        if (value <= 0.0) return 0
        val normalized = ((poor - value) / (poor - excellent)).coerceIn(0.0, 1.0)
        return (normalized * cap).roundToInt().coerceIn(0, cap)
    }

    private fun quantizationStabilityPenalty(quantization: String): Int {
        val normalized = quantization.uppercase()
        return when {
            normalized.startsWith("IQ1") || normalized.startsWith("Q1") || normalized.contains("IQ1") -> 110
            normalized.startsWith("IQ2") || normalized.startsWith("Q2") -> 35
            normalized.startsWith("Q3") -> 12
            else -> 0
        }
    }
}
