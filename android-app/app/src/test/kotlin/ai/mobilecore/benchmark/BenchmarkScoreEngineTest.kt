package ai.mobilecore.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkScoreEngineTest {
    @Test
    fun excellentValidRunCapsAtOneThousandAndOneMillionHeadline() {
        val score = BenchmarkScoreEngine.score(
            summary(
                decodeTps = 30.0,
                prefillTps = 300.0,
                firstTokenMs = 700,
                loadTimeMs = 1000,
                memoryPeakMb = 500,
                availableMemoryMb = 4096,
                retention = 0.95,
                thermal = ThermalStatus.NONE
            )
        )!!

        assertEquals(1000, score.canonicalScore)
        assertEquals(1_000_000, score.headlineScore)
        assertEquals(350, score.inference)
        assertEquals(150, score.responsiveness)
        assertEquals(150, score.memory)
        assertEquals(200, score.sustainedPerformance)
        assertEquals(150, score.stability)
    }

    @Test
    fun thermalAndRetentionReduceOnlySustainedDimension() {
        val cool = BenchmarkScoreEngine.score(summary())!!
        val hot = BenchmarkScoreEngine.score(
            summary(retention = 0.55, thermal = ThermalStatus.SEVERE)
        )!!

        assertEquals(cool.inference, hot.inference)
        assertEquals(cool.responsiveness, hot.responsiveness)
        assertEquals(cool.memory, hot.memory)
        assertEquals(cool.stability, hot.stability)
        assertTrue(hot.sustainedPerformance < cool.sustainedPerformance)
    }

    @Test
    fun invalidSummaryHasNoOfficialScore() {
        assertNull(
            BenchmarkScoreEngine.score(
                summary(valid = false, failureKind = BenchmarkFailureKind.TIMEOUT)
            )
        )
    }

    private fun summary(
        valid: Boolean = true,
        decodeTps: Double = 30.0,
        prefillTps: Double = 300.0,
        firstTokenMs: Long = 700,
        loadTimeMs: Long = 1000,
        memoryPeakMb: Long = 500,
        availableMemoryMb: Long = 4096,
        retention: Double = 0.95,
        thermal: ThermalStatus = ThermalStatus.NONE,
        failureKind: BenchmarkFailureKind? = null
    ) = BenchmarkSummary(
        spec = BenchmarkSpecV2.forProfile(BenchmarkProfile.STANDARD, 6),
        valid = valid,
        failureKind = failureKind,
        measuredRuns = 3,
        completedRuns = if (valid) 3 else 2,
        medianLoadTimeMs = loadTimeMs,
        medianPromptEvalMs = 600,
        medianFirstTokenMs = firstTokenMs,
        p95FirstTokenMs = firstTokenMs,
        medianPrefillTokensPerSecond = prefillTps,
        medianDecodeTokensPerSecond = decodeTps,
        memoryPeakMb = memoryPeakMb,
        availableMemoryBeforeMb = availableMemoryMb,
        batteryDeltaPercent = 1,
        batteryTemperaturePeakCelsius = 35.0,
        thermalPeak = thermal,
        throughputRetention = retention,
        samples = emptyList()
    )
}
