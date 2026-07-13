package ai.mobilecore.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkAggregatorTest {
    @Test
    fun standardUsesMedianPeakP95AndRetention() {
        val samples = listOf(
            sample(0, decodeTps = 20.0, firstTokenMs = 1000, memoryPeakMb = 600, thermalPeak = ThermalStatus.NONE),
            sample(1, decodeTps = 18.0, firstTokenMs = 1200, memoryPeakMb = 720, thermalPeak = ThermalStatus.LIGHT),
            sample(2, decodeTps = 16.0, firstTokenMs = 3000, memoryPeakMb = 680, thermalPeak = ThermalStatus.MODERATE)
        )

        val summary = BenchmarkAggregator.aggregate(
            BenchmarkSpecV2.forProfile(BenchmarkProfile.STANDARD, threads = 6),
            samples
        )

        assertTrue(summary.valid)
        assertEquals(18.0, summary.medianDecodeTokensPerSecond, 0.0001)
        assertEquals(1200L, summary.medianFirstTokenMs)
        assertEquals(3000L, summary.p95FirstTokenMs)
        assertEquals(720L, summary.memoryPeakMb)
        assertEquals(ThermalStatus.MODERATE, summary.thermalPeak)
        assertEquals(0.8, summary.throughputRetention, 0.0001)
        assertEquals(3, summary.completedRuns)
    }

    @Test
    fun missingMeasuredRunInvalidatesSummary() {
        val summary = BenchmarkAggregator.aggregate(
            BenchmarkSpecV2.forProfile(BenchmarkProfile.STANDARD, threads = 6),
            listOf(sample(0), sample(1))
        )

        assertFalse(summary.valid)
        assertEquals(BenchmarkFailureKind.METRICS_INCOMPLETE, summary.failureKind)
    }

    @Test
    fun chargingChangeInvalidatesSummary() {
        val samples = listOf(
            sample(0),
            sample(1, chargingEnd = true),
            sample(2)
        )

        val summary = BenchmarkAggregator.aggregate(
            BenchmarkSpecV2.forProfile(BenchmarkProfile.STANDARD, threads = 6),
            samples
        )

        assertFalse(summary.valid)
        assertEquals(BenchmarkFailureKind.CHARGING_STATE_CHANGED, summary.failureKind)
    }

    @Test
    fun explicitRuntimeFailureOverridesMissingMetrics() {
        val summary = BenchmarkAggregator.invalid(
            BenchmarkSpecV2.forProfile(BenchmarkProfile.QUICK, threads = 4),
            BenchmarkFailureKind.TIMEOUT
        )

        assertFalse(summary.valid)
        assertEquals(BenchmarkFailureKind.TIMEOUT, summary.failureKind)
        assertEquals(0, summary.completedRuns)
    }

    private fun sample(
        index: Int,
        decodeTps: Double = 20.0,
        firstTokenMs: Long = 1000,
        memoryPeakMb: Long = 600,
        thermalPeak: ThermalStatus = ThermalStatus.NONE,
        chargingEnd: Boolean = false
    ) = BenchmarkRunSample(
        runIndex = index,
        promptTokens = 128,
        generatedTokens = 128,
        loadTimeMs = 1200,
        promptEvalMs = 600,
        firstTokenMs = firstTokenMs,
        decodeMs = 6400,
        totalMs = 7000,
        prefillTokensPerSecond = 213.0,
        decodeTokensPerSecond = decodeTps,
        memoryPeakMb = memoryPeakMb,
        availableMemoryBeforeMb = 4096,
        batteryPercentStart = 80,
        batteryPercentEnd = 79,
        batteryTemperatureStartCelsius = 32.0,
        batteryTemperaturePeakCelsius = 35.0,
        batteryTemperatureEndCelsius = 34.0,
        thermalStart = ThermalStatus.NONE,
        thermalPeak = thermalPeak,
        thermalEnd = thermalPeak,
        chargingStart = false,
        chargingEnd = chargingEnd,
        completed = true,
        failureKind = null
    )
}
