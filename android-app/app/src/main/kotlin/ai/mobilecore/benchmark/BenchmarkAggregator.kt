package ai.mobilecore.benchmark

import kotlin.math.ceil

object BenchmarkAggregator {
    fun invalid(
        spec: BenchmarkSpecV2,
        failureKind: BenchmarkFailureKind,
        samples: List<BenchmarkRunSample> = emptyList()
    ): BenchmarkSummary = aggregate(spec, samples).copy(
        valid = false,
        failureKind = failureKind
    )

    fun aggregate(
        spec: BenchmarkSpecV2,
        samples: List<BenchmarkRunSample>
    ): BenchmarkSummary {
        val ordered = samples.sortedBy { it.runIndex }
        val failure = failureKind(spec, ordered)
        val completed = ordered.count { it.completed && it.failureKind == null }

        return BenchmarkSummary(
            spec = spec,
            valid = failure == null,
            failureKind = failure,
            measuredRuns = ordered.size,
            completedRuns = completed,
            medianLoadTimeMs = medianLong(ordered.map { it.loadTimeMs }),
            medianPromptEvalMs = medianLong(ordered.map { it.promptEvalMs }),
            medianFirstTokenMs = medianLong(ordered.map { it.firstTokenMs }),
            p95FirstTokenMs = percentileLong(ordered.map { it.firstTokenMs }, 0.95),
            medianPrefillTokensPerSecond = medianDouble(ordered.map { it.prefillTokensPerSecond }),
            medianDecodeTokensPerSecond = medianDouble(ordered.map { it.decodeTokensPerSecond }),
            memoryPeakMb = ordered.maxOfOrNull { it.memoryPeakMb } ?: 0L,
            availableMemoryBeforeMb = ordered.minOfOrNull { it.availableMemoryBeforeMb } ?: 0L,
            batteryDeltaPercent = if (ordered.isEmpty()) 0 else {
                (ordered.first().batteryPercentStart - ordered.last().batteryPercentEnd).coerceAtLeast(0)
            },
            batteryTemperaturePeakCelsius = ordered.mapNotNull { it.batteryTemperaturePeakCelsius }.maxOrNull(),
            thermalPeak = ordered.maxByOrNull { it.thermalPeak.ordinal }?.thermalPeak ?: ThermalStatus.NONE,
            throughputRetention = retention(ordered),
            samples = ordered
        )
    }

    private fun failureKind(
        spec: BenchmarkSpecV2,
        samples: List<BenchmarkRunSample>
    ): BenchmarkFailureKind? {
        if (samples.size != spec.profile.measuredRuns) {
            return BenchmarkFailureKind.METRICS_INCOMPLETE
        }
        samples.firstOrNull { it.failureKind != null }?.failureKind?.let { return it }
        if (samples.any { !it.completed }) return BenchmarkFailureKind.METRICS_INCOMPLETE
        if (samples.any { it.chargingStart != it.chargingEnd }) {
            return BenchmarkFailureKind.CHARGING_STATE_CHANGED
        }
        if (samples.any {
                it.generatedTokens <= 0 ||
                    it.firstTokenMs <= 0 ||
                    it.totalMs <= 0 ||
                    it.decodeTokensPerSecond <= 0.0 ||
                    it.prefillTokensPerSecond <= 0.0 ||
                    it.availableMemoryBeforeMb <= 0
            }
        ) {
            return BenchmarkFailureKind.METRICS_INCOMPLETE
        }
        return null
    }

    private fun retention(samples: List<BenchmarkRunSample>): Double {
        val first = samples.firstOrNull()?.decodeTokensPerSecond ?: return 0.0
        val last = samples.lastOrNull()?.decodeTokensPerSecond ?: return 0.0
        if (first <= 0.0) return 0.0
        return (last / first).coerceIn(0.0, 1.5)
    }

    private fun medianLong(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2L
    }

    private fun medianDouble(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun percentileLong(values: List<Long>, percentile: Double): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val index = (ceil(percentile.coerceIn(0.0, 1.0) * sorted.size).toInt() - 1)
            .coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
