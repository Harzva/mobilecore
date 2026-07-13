package ai.mobilecore.benchmark

import kotlin.math.roundToInt

object BenchmarkScoreEngine {
    fun score(summary: BenchmarkSummary): BenchmarkScoreV2? {
        if (!summary.valid) return null

        val inference = (
            higherIsBetter(summary.medianDecodeTokensPerSecond, excellent = 30.0, cap = 240) +
                higherIsBetter(summary.medianPrefillTokensPerSecond, excellent = 300.0, cap = 110)
            ).coerceIn(0, 350)
        val responsiveness = (
            lowerIsBetter(summary.medianFirstTokenMs.toDouble(), excellent = 700.0, poor = 8_000.0, cap = 100) +
                lowerIsBetter(summary.medianLoadTimeMs.toDouble(), excellent = 1_000.0, poor = 15_000.0, cap = 50)
            ).coerceIn(0, 150)
        val memoryPressure = summary.memoryPeakMb.toDouble() / summary.availableMemoryBeforeMb.coerceAtLeast(1L)
        val memory = lowerIsBetter(memoryPressure, excellent = 0.15, poor = 0.85, cap = 150)
        val sustained = (
            boundedRange(summary.throughputRetention, poor = 0.50, excellent = 0.95, cap = 140) +
                thermalScore(summary.thermalPeak)
            ).coerceIn(0, 200)
        val stability = 150
        val canonical = (inference + responsiveness + memory + sustained + stability).coerceIn(0, 1000)

        return BenchmarkScoreV2(
            canonicalScore = canonical,
            headlineScore = canonical * 1000,
            inference = inference,
            responsiveness = responsiveness,
            memory = memory,
            sustainedPerformance = sustained,
            stability = stability,
            algorithmId = summary.spec.scoreAlgorithmId
        )
    }

    private fun higherIsBetter(value: Double, excellent: Double, cap: Int): Int {
        if (value <= 0.0) return 0
        return (value / excellent * cap).roundToInt().coerceIn(0, cap)
    }

    private fun lowerIsBetter(value: Double, excellent: Double, poor: Double, cap: Int): Int {
        if (value <= excellent) return cap
        if (value >= poor) return 0
        return ((poor - value) / (poor - excellent) * cap).roundToInt().coerceIn(0, cap)
    }

    private fun boundedRange(value: Double, poor: Double, excellent: Double, cap: Int): Int {
        if (value <= poor) return 0
        if (value >= excellent) return cap
        return ((value - poor) / (excellent - poor) * cap).roundToInt().coerceIn(0, cap)
    }

    private fun thermalScore(status: ThermalStatus): Int = when (status) {
        ThermalStatus.NONE -> 60
        ThermalStatus.LIGHT -> 52
        ThermalStatus.MODERATE -> 38
        ThermalStatus.SEVERE -> 18
        ThermalStatus.CRITICAL,
        ThermalStatus.EMERGENCY,
        ThermalStatus.SHUTDOWN -> 0
    }
}
