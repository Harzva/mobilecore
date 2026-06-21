package ai.mobilecore.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import kotlin.math.max
import kotlin.math.min

class DeviceProbe(private val context: Context) {

    fun probe(): DeviceProfile {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val filesDir = context.filesDir
        val internalStats = StatFs(filesDir.absolutePath)
        val internalFreeMb = internalStats.availableBytes / (1024 * 1024)

        val externalDir = context.getExternalFilesDir("models")
        val externalStats = externalDir?.let { StatFs(it.absolutePath) }
        val externalFreeMb = externalStats?.availableBytes?.div(1024 * 1024) ?: 0

        return DeviceProfile(
            device = Build.DEVICE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            totalRamMb = memoryInfo.totalMem / (1024 * 1024),
            availableRamMb = max(32L, memoryInfo.availMem / (1024 * 1024)),
            lowRamDevice = activityManager.isLowRamDevice,
            coreCount = Runtime.getRuntime().availableProcessors(),
            backend = "llama.cpp",
            internalStorageFreeMb = internalFreeMb,
            externalStorageFreeMb = externalFreeMb
        )
    }

    fun recommendModels(
        models: List<RuntimeModel>,
        metrics: RuntimeMetrics,
        maxItems: Int = 3,
        scoring: RecommendationScoringConfig = RecommendationScoringConfig.stabilityFirst(),
        benchmarks: Map<String, ModelBenchmark> = emptyMap()
    ): List<DeviceRecommendation> {
        val profile = probe()
        val recommendations = mutableListOf<DeviceRecommendation>()

        for (model in models) {
            val reasons = ArrayList<String>()
            val sizeMb = max(1L, model.sizeBytes / (1024 * 1024))
            val benchmark = benchmarkFor(model, benchmarks)
            val estimatedMemoryMb = estimateRequiredMemoryMb(model, sizeMb)
            val memoryForFitMb = max(estimatedMemoryMb, benchmark?.memoryPeakMb ?: 0)

            val fit = when {
                memoryForFitMb <= profile.availableRamMb * 0.70 -> RecommendationFit.PERFECT
                memoryForFitMb <= profile.availableRamMb * 0.90 -> RecommendationFit.GOOD
                memoryForFitMb <= profile.availableRamMb * 1.05 -> RecommendationFit.MARGINAL
                else -> RecommendationFit.TOO_TIGHT
            }

            if (fit == RecommendationFit.TOO_TIGHT && profile.availableRamMb > 0 && model.sizeBytes <= 0) {
                continue
            }

            val memoryPressure = memoryForFitMb.toDouble() / max(1L, profile.availableRamMb)
            val fitScore = when (fit) {
                RecommendationFit.PERFECT -> 100.0
                RecommendationFit.GOOD -> 84.0
                RecommendationFit.MARGINAL -> 62.0
                RecommendationFit.TOO_TIGHT -> 24.0
            }

            val expectedTps = estimateExpectedTps(profile, memoryForFitMb, metrics, scoring, benchmark)
            val speedScore = (expectedTps / scoring.targetTokensPerSecond * 100.0).coerceIn(0.0, 100.0)
            val sizeScore = estimateSizeScore(profile, memoryForFitMb)
            val measuredScore = estimateRecentSpeedScore(metrics, scoring, benchmark)
            val weightedScore = fitScore * scoring.fitWeight +
                speedScore * scoring.speedWeight +
                sizeScore * scoring.sizeWeight +
                measuredScore * scoring.measuredSpeedWeight
            val score = max(0.0, weightedScore - memoryPressure * scoring.memoryPressurePenalty)

            reasons.add("估算占用 ${estimatedMemoryMb}MB（文件 ${sizeMb}MB）")
            reasons.add("适配等级: ${fit.name.lowercase()}")
            reasons.add("偏好: ${scoring.mode.apiName}")
            if (benchmark != null) {
                reasons.add("历史均速 ${"%.2f".format(benchmark.averageDecodeTokensPerSecond)} tok/s")
                if (benchmark.memoryPeakMb > 0) reasons.add("历史峰值 ${benchmark.memoryPeakMb}MB")
            }
            if (model.parameterLabel != null) {
                reasons.add("${model.architecture}/${model.parameterLabel}/${model.quantization}")
            }
            if (model.sizeBytes == 0L) {
                reasons.add("当前文件占用未知：建议先实际下载 GGUF")
            }
            if (model.loaded || metrics.activeModel.equals(model.id, ignoreCase = true)) {
                reasons.add("已加载模型：无需切换")
            }

            recommendations.add(
                DeviceRecommendation(
                    modelId = model.id,
                    path = model.path,
                    sizeBytes = model.sizeBytes,
                    estimatedMemoryMb = estimatedMemoryMb,
                    fit = fit,
                    score = score,
                    expectedTokensPerSecond = expectedTps,
                    reasons = reasons,
                    loaded = model.loaded || metrics.activeModel.equals(model.id, ignoreCase = true),
                    benchmark = benchmark
                )
            )
        }

        return recommendations
            .filter { it.fit != RecommendationFit.TOO_TIGHT || profile.lowRamDevice.not() }
            .sortedByDescending { it.score }
            .take(maxItems)
    }

    private fun estimateRequiredMemoryMb(model: RuntimeModel, sizeMb: Long): Int {
        val quant = model.quantization.lowercase()
        val quantMultiplier = when {
            quant.contains("q2") -> 0.75
            quant.contains("q3") -> 0.86
            quant.contains("q4") -> 1.00
            quant.contains("q5") -> 1.14
            quant.contains("q6") -> 1.28
            quant.contains("q8") || quant.contains("f16") || quant.contains("bf16") -> 1.45
            else -> 1.05
        }
        val cacheOverheadMb = min(900L, max(128L, model.contextLength.toLong() * 2 / 1024))
        return (sizeMb * quantMultiplier + cacheOverheadMb).toInt()
    }

    private fun estimateExpectedTps(
        profile: DeviceProfile,
        requiredMb: Int,
        metrics: RuntimeMetrics,
        scoring: RecommendationScoringConfig,
        benchmark: ModelBenchmark?
    ): Double {
        if (benchmark != null && benchmark.averageDecodeTokensPerSecond > 0.0) {
            return benchmark.averageDecodeTokensPerSecond
        }

        val memoryScale = 1.0 / (1.0 + requiredMb.toDouble() / max(512.0, profile.availableRamMb.toDouble()))
        val cpuScale = 0.65 + profile.coreCount * 0.09
        val backendScale = if (profile.backend.contains("gpu", ignoreCase = true)) 1.3 else 0.95
        val recent = metrics.decodeTokensPerSecond.takeIf { it > 0 }?.coerceAtLeast(0.2) ?: 0.0

        return if (recent > 0) {
            recent * memoryScale * cpuScale * backendScale
        } else {
            scoring.baseTokensPerSecond * memoryScale * cpuScale * backendScale
        }
    }

    private fun estimateSizeScore(profile: DeviceProfile, requiredMb: Int): Double {
        val ratio = requiredMb / max(1.0, profile.availableRamMb.toDouble())
        return (100.0 - ratio * 100.0).coerceIn(0.0, 100.0)
    }

    private fun estimateRecentSpeedScore(
        metrics: RuntimeMetrics,
        scoring: RecommendationScoringConfig,
        benchmark: ModelBenchmark?
    ): Double {
        val measuredTps = benchmark?.averageDecodeTokensPerSecond?.takeIf { it > 0.0 }
            ?: metrics.decodeTokensPerSecond.takeIf { it > 0.0 }
            ?: 0.0
        return if (measuredTps > 0) {
            (measuredTps / scoring.targetTokensPerSecond * 100.0).coerceIn(0.0, 100.0)
        } else {
            0.0
        }
    }

    private fun benchmarkFor(model: RuntimeModel, benchmarks: Map<String, ModelBenchmark>): ModelBenchmark? {
        return benchmarks[model.id]
            ?: benchmarks[model.path.substringAfterLast('/').substringBeforeLast(".gguf")]
            ?: benchmarks[model.path]
    }
}
