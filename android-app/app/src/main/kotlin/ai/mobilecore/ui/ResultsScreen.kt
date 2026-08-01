package ai.mobilecore.ui

import org.json.JSONArray
import org.json.JSONObject

data class ResultDimension(
    val key: String,
    val label: String,
    val value: Int,
    val maximum: Int
) {
    val ratio: Double
        get() = value.toDouble() / maximum.coerceAtLeast(1).toDouble()
}

data class BenchmarkResultSnapshot(
    val runId: String,
    val createdAtMs: Long,
    val headlineScore: Int,
    val canonicalScore: Int,
    val profile: String,
    val valid: Boolean,
    val leaderboardEligible: Boolean,
    val manifestSha256: String,
    val comparisonKey: String,
    val dimensions: List<ResultDimension>,
    val decodeTokensPerSecond: Double,
    val firstTokenMs: Long,
    val memoryPeakMb: Long,
    val temperaturePeakCelsius: Double?,
    val batteryDeltaPercent: Int,
    val deviceName: String,
    val computeBackend: String,
    val runtimeName: String,
    val gpuLayers: Int,
    val threads: Int
) {
    val backendLabel: String
        get() = when (computeBackend.lowercase()) {
            "cpu" -> "CPU"
            "gpu" -> "GPU"
            "npu" -> "NPU"
            else -> computeBackend.uppercase()
        }

    val executionLabel: String
        get() = "$backendLabel · $runtimeName · $threads 线程"
}

data class ResultInsight(
    val rating: String,
    val summary: String,
    val strongest: List<String>,
    val bottleneck: String,
    val recommendation: String,
    val modeHint: String
)

data class ResultComparison(
    val current: BenchmarkResultSnapshot,
    val previous: BenchmarkResultSnapshot,
    val canonicalDelta: Int,
    val canonicalPercentDelta: Double?,
    val speedPercentDelta: Double?,
    val temperatureDeltaCelsius: Double?,
    val memoryDeltaMb: Long,
    val firstTokenPercentDelta: Double?
)

object ResultsScreenPresenter {
    fun parse(report: JSONObject): BenchmarkResultSnapshot? {
        val score = report.optJSONObject("score") ?: return null
        val dimensions = score.optJSONObject("dimensions") ?: return null
        val summary = report.optJSONObject("summary") ?: JSONObject()
        val spec = report.optJSONObject("spec") ?: JSONObject()
        val device = report.optJSONObject("device") ?: JSONObject()
        val execution = report.optJSONObject("execution") ?: JSONObject()
        val profile = spec.optString("profile", "quick")
        val valid = report.optBoolean("valid", false)
        return BenchmarkResultSnapshot(
            runId = report.optString("run_id"),
            createdAtMs = report.optLong("created_at_ms"),
            headlineScore = score.optInt("headline"),
            canonicalScore = score.optInt("canonical"),
            profile = profile,
            valid = valid,
            leaderboardEligible = valid && profile == "standard",
            manifestSha256 = report.optString("manifest_sha256"),
            comparisonKey = comparisonKey(report, spec, device),
            dimensions = listOf(
                ResultDimension("inference", "推理", dimensions.optInt("inference"), 350),
                ResultDimension("responsiveness", "响应", dimensions.optInt("responsiveness"), 150),
                ResultDimension("memory", "内存", dimensions.optInt("memory"), 150),
                ResultDimension("sustained_performance", "持续", dimensions.optInt("sustained_performance"), 200),
                ResultDimension("stability", "稳定", dimensions.optInt("stability"), 150)
            ),
            decodeTokensPerSecond = summary.optDouble("median_decode_tokens_per_second", 0.0),
            firstTokenMs = summary.optLong("median_first_token_ms", 0L),
            memoryPeakMb = summary.optLong("memory_peak_mb", 0L),
            temperaturePeakCelsius = summary.optNullableDouble("battery_temperature_peak_celsius"),
            batteryDeltaPercent = summary.optInt("battery_delta_percent", 0),
            deviceName = listOf(device.optString("manufacturer"), device.optString("model"))
                .filter { it.isNotBlank() }
                .joinToString(" "),
            computeBackend = execution.optString("compute_backend", "cpu").ifBlank { "cpu" },
            runtimeName = execution.optString("runtime", "llama.cpp").ifBlank { "llama.cpp" },
            gpuLayers = execution.optInt("gpu_layers", 0),
            threads = spec.optInt("threads", 1).coerceAtLeast(1)
        )
    }

    fun insight(snapshot: BenchmarkResultSnapshot): ResultInsight {
        val ordered = snapshot.dimensions.sortedWith(compareByDescending<ResultDimension> { it.ratio }.thenBy { it.label })
        val strongest = ordered.take(2).map { it.label }
        val weakest = ordered.last()
        val strongestDimension = ordered.first()
        val rating = when {
            snapshot.canonicalScore >= 850 -> "优秀"
            snapshot.canonicalScore >= 700 -> "良好"
            snapshot.canonicalScore >= 500 -> "一般"
            else -> "受限"
        }
        val strengthDescription = when {
            strongestDimension.ratio >= 0.90 -> "非常突出"
            strongestDimension.ratio >= 0.75 -> "表现良好"
            else -> "相对稳定"
        }
        val recommendation = when {
            snapshot.canonicalScore >= 850 && snapshot.dimensions.first { it.key == "memory" }.ratio >= 0.65 ->
                "适合 0.5B–1.5B 本地语言模型，可用于日常对话、摘要和轻量写作。"
            snapshot.canonicalScore >= 700 ->
                "适合 0.5B–1B 本地语言模型，可用于短对话、摘要和离线问答。"
            snapshot.canonicalScore >= 500 ->
                "建议使用 0.3B–0.6B 小模型，优先选择短上下文任务。"
            else ->
                "建议使用 0.3B 以下超轻模型，适合简单分类与短文本任务。"
        }
        val modeHint = when (snapshot.profile) {
            "standard" -> "标准模式 · ${if (snapshot.leaderboardEligible) "具备榜单资格" else "本次结果无榜单资格"}"
            "stress" -> "压力模式 · 用于观察持续性能，不参与榜单"
            else -> "快速模式 · 仅供预览，不参与榜单"
        }
        return ResultInsight(
            rating = rating,
            summary = "${strongestDimension.label}$strengthDescription，${weakest.label}是当前主要瓶颈。",
            strongest = strongest,
            bottleneck = weakest.label,
            recommendation = recommendation,
            modeHint = modeHint
        )
    }

    fun previousComparable(current: BenchmarkResultSnapshot, reports: JSONArray): BenchmarkResultSnapshot? {
        var foundCurrent = false
        for (index in 0 until reports.length()) {
            val candidate = reports.optJSONObject(index)?.let(::parse) ?: continue
            if (!foundCurrent) {
                if (candidate.runId == current.runId) foundCurrent = true
                continue
            }
            if (candidate.valid && candidate.comparisonKey == current.comparisonKey) return candidate
        }
        return null
    }

    fun comparableByRunId(
        current: BenchmarkResultSnapshot,
        reports: JSONArray,
        runId: String?
    ): BenchmarkResultSnapshot? {
        if (runId.isNullOrBlank() || runId == current.runId) return null
        for (index in 0 until reports.length()) {
            val candidate = reports.optJSONObject(index)?.let(::parse) ?: continue
            if (candidate.runId == runId && candidate.valid && candidate.comparisonKey == current.comparisonKey) {
                return candidate
            }
        }
        return null
    }

    fun compare(current: BenchmarkResultSnapshot, previous: BenchmarkResultSnapshot?): ResultComparison? {
        if (previous == null || previous.comparisonKey != current.comparisonKey) return null
        return ResultComparison(
            current = current,
            previous = previous,
            canonicalDelta = current.canonicalScore - previous.canonicalScore,
            canonicalPercentDelta = percentDelta(current.canonicalScore.toDouble(), previous.canonicalScore.toDouble()),
            speedPercentDelta = percentDelta(current.decodeTokensPerSecond, previous.decodeTokensPerSecond),
            temperatureDeltaCelsius = if (current.temperaturePeakCelsius != null && previous.temperaturePeakCelsius != null) {
                current.temperaturePeakCelsius - previous.temperaturePeakCelsius
            } else null,
            memoryDeltaMb = current.memoryPeakMb - previous.memoryPeakMb,
            firstTokenPercentDelta = percentDelta(current.firstTokenMs.toDouble(), previous.firstTokenMs.toDouble())
        )
    }

    private fun comparisonKey(report: JSONObject, spec: JSONObject, device: JSONObject): String {
        return listOf(
            report.optString("manifest_sha256"),
            device.optString("manufacturer"),
            device.optString("model"),
            device.optString("device"),
            spec.optString("id"),
            spec.optInt("version").toString(),
            spec.optString("score_algorithm_id"),
            spec.optString("profile"),
            spec.optString("prompt_asset_id"),
            spec.optInt("context_length").toString(),
            spec.optInt("threads").toString(),
            spec.optDouble("temperature").toString(),
            report.optJSONObject("execution")?.optString("compute_backend", "cpu") ?: "cpu",
            report.optJSONObject("execution")?.optString("runtime", "llama.cpp") ?: "llama.cpp",
            (report.optJSONObject("execution")?.optInt("gpu_layers", 0) ?: 0).toString()
        ).joinToString("|")
    }

    private fun percentDelta(current: Double, previous: Double): Double? {
        if (previous <= 0.0) return null
        return (current - previous) / previous * 100.0
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key).takeUnless { it.isNaN() }
    }
}
