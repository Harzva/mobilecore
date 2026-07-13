package ai.mobilecore.benchmark

enum class BenchmarkProfile(
    val apiName: String,
    val warmupRuns: Int,
    val measuredRuns: Int,
    val outputTokens: Int,
    val cooldownMs: Long,
    val officialLeaderboardEligible: Boolean
) {
    QUICK("quick", 1, 1, 64, 0L, false),
    STANDARD("standard", 1, 3, 128, 15_000L, true),
    STRESS("stress", 1, 10, 128, 0L, false);

    companion object {
        fun fromApiName(value: String?): BenchmarkProfile = entries.firstOrNull {
            it.apiName == value?.trim()?.lowercase()
        } ?: QUICK
    }
}

data class BenchmarkSpecV2(
    val id: String,
    val version: Int,
    val scoreAlgorithmId: String,
    val profile: BenchmarkProfile,
    val promptAssetId: String,
    val contextLength: Int,
    val threads: Int,
    val temperature: Float,
    val timeoutMs: Long
) {
    companion object {
        const val SPEC_ID = "tuima-llm-benchmark-v2"
        const val SCORE_ALGORITHM_ID = "tuima-score-v2"

        fun forProfile(profile: BenchmarkProfile, threads: Int) = BenchmarkSpecV2(
            id = SPEC_ID,
            version = 2,
            scoreAlgorithmId = SCORE_ALGORITHM_ID,
            profile = profile,
            promptAssetId = "tuima-standard-prompt-v2",
            contextLength = 2048,
            threads = threads.coerceAtLeast(1),
            temperature = 0.2f,
            timeoutMs = when (profile) {
                BenchmarkProfile.QUICK -> 120_000L
                BenchmarkProfile.STANDARD -> 600_000L
                BenchmarkProfile.STRESS -> 1_500_000L
            }
        )
    }
}

enum class ThermalStatus {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN;

    companion object {
        fun fromAndroidStatus(value: Int): ThermalStatus = when (value) {
            1 -> LIGHT
            2 -> MODERATE
            3 -> SEVERE
            4 -> CRITICAL
            5 -> EMERGENCY
            6 -> SHUTDOWN
            else -> NONE
        }
    }
}

enum class BenchmarkFailureKind(val apiName: String) {
    PREFLIGHT_BLOCKED("preflight_blocked"),
    CANCELLED("cancelled"),
    MODEL_INVALID("model_invalid"),
    RUNTIME_UNAVAILABLE("runtime_unavailable"),
    TIMEOUT("timeout"),
    OOM("oom"),
    NATIVE_CRASH("native_crash"),
    SERVICE_RESTARTED("service_restarted"),
    METRICS_INCOMPLETE("metrics_incomplete"),
    CHARGING_STATE_CHANGED("charging_state_changed"),
    UPLOAD_FAILED("upload_failed")
}

data class BenchmarkRunSample(
    val runIndex: Int,
    val promptTokens: Int,
    val generatedTokens: Int,
    val loadTimeMs: Long,
    val promptEvalMs: Long,
    val firstTokenMs: Long,
    val decodeMs: Long,
    val totalMs: Long,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
    val memoryPeakMb: Long,
    val availableMemoryBeforeMb: Long,
    val batteryPercentStart: Int,
    val batteryPercentEnd: Int,
    val batteryTemperatureStartCelsius: Double?,
    val batteryTemperaturePeakCelsius: Double?,
    val batteryTemperatureEndCelsius: Double?,
    val thermalStart: ThermalStatus,
    val thermalPeak: ThermalStatus,
    val thermalEnd: ThermalStatus,
    val chargingStart: Boolean,
    val chargingEnd: Boolean,
    val completed: Boolean,
    val failureKind: BenchmarkFailureKind?
)

data class BenchmarkSummary(
    val spec: BenchmarkSpecV2,
    val valid: Boolean,
    val failureKind: BenchmarkFailureKind?,
    val measuredRuns: Int,
    val completedRuns: Int,
    val medianLoadTimeMs: Long,
    val medianPromptEvalMs: Long,
    val medianFirstTokenMs: Long,
    val p95FirstTokenMs: Long,
    val medianPrefillTokensPerSecond: Double,
    val medianDecodeTokensPerSecond: Double,
    val memoryPeakMb: Long,
    val availableMemoryBeforeMb: Long,
    val batteryDeltaPercent: Int,
    val batteryTemperaturePeakCelsius: Double?,
    val thermalPeak: ThermalStatus,
    val throughputRetention: Double,
    val samples: List<BenchmarkRunSample>
)

data class BenchmarkScoreV2(
    val canonicalScore: Int,
    val headlineScore: Int,
    val inference: Int,
    val responsiveness: Int,
    val memory: Int,
    val sustainedPerformance: Int,
    val stability: Int,
    val algorithmId: String
)
