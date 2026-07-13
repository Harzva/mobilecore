package ai.mobilecore.benchmark

data class BenchmarkPreflightSnapshot(
    val batteryPercent: Int,
    val charging: Boolean,
    val thermalStatus: ThermalStatus,
    val freeStorageMb: Long,
    val modelSizeMb: Long,
    val modelHashMatches: Boolean,
    val promptHashMatches: Boolean,
    val apiHealthy: Boolean,
    val benchmarkRunning: Boolean
)

enum class BenchmarkPreflightReason {
    BATTERY_TOO_LOW,
    DEVICE_CHARGING,
    THERMAL_TOO_HIGH,
    STORAGE_TOO_LOW,
    MODEL_INVALID,
    PROMPT_INVALID,
    RUNTIME_UNAVAILABLE,
    BENCHMARK_ALREADY_RUNNING
}

sealed interface BenchmarkPreflightResult {
    val snapshot: BenchmarkPreflightSnapshot

    data class Ready(override val snapshot: BenchmarkPreflightSnapshot) : BenchmarkPreflightResult

    data class Blocked(
        override val snapshot: BenchmarkPreflightSnapshot,
        val reasons: List<BenchmarkPreflightReason>
    ) : BenchmarkPreflightResult
}

object BenchmarkPreflight {
    const val MINIMUM_BATTERY_PERCENT = 30
    const val STORAGE_RESERVE_MB = 512L

    fun evaluate(snapshot: BenchmarkPreflightSnapshot): BenchmarkPreflightResult {
        val reasons = buildList {
            if (snapshot.batteryPercent < MINIMUM_BATTERY_PERCENT) {
                add(BenchmarkPreflightReason.BATTERY_TOO_LOW)
            }
            if (snapshot.charging) add(BenchmarkPreflightReason.DEVICE_CHARGING)
            if (snapshot.thermalStatus.ordinal > ThermalStatus.LIGHT.ordinal) {
                add(BenchmarkPreflightReason.THERMAL_TOO_HIGH)
            }
            if (snapshot.freeStorageMb < snapshot.modelSizeMb + STORAGE_RESERVE_MB) {
                add(BenchmarkPreflightReason.STORAGE_TOO_LOW)
            }
            if (!snapshot.modelHashMatches) add(BenchmarkPreflightReason.MODEL_INVALID)
            if (!snapshot.promptHashMatches) add(BenchmarkPreflightReason.PROMPT_INVALID)
            if (!snapshot.apiHealthy) add(BenchmarkPreflightReason.RUNTIME_UNAVAILABLE)
            if (snapshot.benchmarkRunning) add(BenchmarkPreflightReason.BENCHMARK_ALREADY_RUNNING)
        }
        return if (reasons.isEmpty()) {
            BenchmarkPreflightResult.Ready(snapshot)
        } else {
            BenchmarkPreflightResult.Blocked(snapshot, reasons)
        }
    }
}
