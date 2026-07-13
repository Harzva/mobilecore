package ai.mobilecore.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkPreflightTest {
    @Test
    fun healthySnapshotIsReady() {
        assertTrue(BenchmarkPreflight.evaluate(snapshot()) is BenchmarkPreflightResult.Ready)
    }

    @Test
    fun reportsEveryBlockingReasonTogether() {
        val result = BenchmarkPreflight.evaluate(
            snapshot().copy(
                batteryPercent = 20,
                charging = true,
                thermalStatus = ThermalStatus.MODERATE,
                freeStorageMb = 900,
                modelHashMatches = false,
                promptHashMatches = false,
                apiHealthy = false,
                benchmarkRunning = true
            )
        ) as BenchmarkPreflightResult.Blocked

        assertEquals(
            setOf(
                BenchmarkPreflightReason.BATTERY_TOO_LOW,
                BenchmarkPreflightReason.DEVICE_CHARGING,
                BenchmarkPreflightReason.THERMAL_TOO_HIGH,
                BenchmarkPreflightReason.STORAGE_TOO_LOW,
                BenchmarkPreflightReason.MODEL_INVALID,
                BenchmarkPreflightReason.PROMPT_INVALID,
                BenchmarkPreflightReason.RUNTIME_UNAVAILABLE,
                BenchmarkPreflightReason.BENCHMARK_ALREADY_RUNNING
            ),
            result.reasons.toSet()
        )
    }

    @Test
    fun storageReserveIsModelSizePlusFiveHundredTwelveMb() {
        val exactRequired = 469L + 512L
        val blocked = BenchmarkPreflight.evaluate(
            snapshot().copy(freeStorageMb = exactRequired - 1)
        ) as BenchmarkPreflightResult.Blocked
        assertEquals(listOf(BenchmarkPreflightReason.STORAGE_TOO_LOW), blocked.reasons)

        assertTrue(
            BenchmarkPreflight.evaluate(snapshot().copy(freeStorageMb = exactRequired))
                is BenchmarkPreflightResult.Ready
        )
    }

    private fun snapshot() = BenchmarkPreflightSnapshot(
        batteryPercent = 80,
        charging = false,
        thermalStatus = ThermalStatus.LIGHT,
        freeStorageMb = 2_000,
        modelSizeMb = 469,
        modelHashMatches = true,
        promptHashMatches = true,
        apiHealthy = true,
        benchmarkRunning = false
    )
}
