package ai.mobilecore.benchmark

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class BenchmarkDeviceIdentity(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val apiLevel: Int,
    val abi: String,
    val totalMemoryMb: Long,
    val coreCount: Int
)

data class BenchmarkReport(
    val runId: String,
    val createdAtMs: Long,
    val manifestSha256: String,
    val device: BenchmarkDeviceIdentity,
    val summary: BenchmarkSummary,
    val score: BenchmarkScoreV2?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", REPORT_SCHEMA)
        put("run_id", runId)
        put("created_at_ms", createdAtMs)
        put("manifest_sha256", manifestSha256)
        put("device", JSONObject().apply {
            put("manufacturer", device.manufacturer)
            put("model", device.model)
            put("device", device.device)
            put("android_release", device.androidRelease)
            put("api_level", device.apiLevel)
            put("abi", device.abi)
            put("total_memory_mb", device.totalMemoryMb)
            put("core_count", device.coreCount)
        })
        put("spec", summary.spec.toJson())
        put("valid", summary.valid)
        put("failure_kind", summary.failureKind?.apiName ?: JSONObject.NULL)
        put("summary", summary.toJson())
        put("score", score?.toJson() ?: JSONObject.NULL)
        put("samples", JSONArray().apply { summary.samples.forEach { put(it.toJson()) } })
    }

    companion object {
        const val REPORT_SCHEMA = "tuima-benchmark-report-v2"
    }
}

class BenchmarkReportStore(context: Context) {
    private val reportFile = File(context.filesDir, "tuima_benchmark_reports_v2.json")

    @Synchronized
    fun record(report: BenchmarkReport) {
        val existing = reports().toMutableList()
        existing.add(0, report.toJson())
        write(existing.take(MAX_REPORTS))
    }

    @Synchronized
    fun latest(): JSONObject? = reports().firstOrNull()

    @Synchronized
    fun toJson(limit: Int = 10): JSONObject {
        val reports = reports().take(limit.coerceIn(1, MAX_REPORTS))
        return JSONObject().apply {
            put("object", "tuima_benchmark_reports")
            put("schema", BenchmarkReport.REPORT_SCHEMA)
            put("count", reports.size)
            put("data", JSONArray().apply { reports.forEach(::put) })
        }
    }

    private fun reports(): List<JSONObject> {
        if (!reportFile.isFile) return emptyList()
        val root = runCatching { JSONObject(reportFile.readText()) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("reports") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::add)
            }
        }
    }

    private fun write(reports: List<JSONObject>) {
        val next = File(reportFile.parentFile, "${reportFile.name}.tmp")
        next.writeText(JSONObject().apply {
            put("schema", BenchmarkReport.REPORT_SCHEMA)
            put("reports", JSONArray().apply { reports.forEach(::put) })
        }.toString(2))
        check(next.renameTo(reportFile) || run {
            next.copyTo(reportFile, overwrite = true)
            next.delete()
        }) { "Unable to persist benchmark report" }
    }

    private companion object {
        const val MAX_REPORTS = 50
    }
}

private fun BenchmarkSpecV2.toJson() = JSONObject().apply {
    put("id", id)
    put("version", version)
    put("score_algorithm_id", scoreAlgorithmId)
    put("profile", profile.apiName)
    put("prompt_asset_id", promptAssetId)
    put("context_length", contextLength)
    put("threads", threads)
    put("temperature", temperature.toDouble())
    put("timeout_ms", timeoutMs)
}

private fun BenchmarkSummary.toJson() = JSONObject().apply {
    put("measured_runs", measuredRuns)
    put("completed_runs", completedRuns)
    put("median_load_time_ms", medianLoadTimeMs)
    put("median_prompt_eval_ms", medianPromptEvalMs)
    put("median_first_token_ms", medianFirstTokenMs)
    put("p95_first_token_ms", p95FirstTokenMs)
    put("median_prefill_tokens_per_second", medianPrefillTokensPerSecond)
    put("median_decode_tokens_per_second", medianDecodeTokensPerSecond)
    put("memory_peak_mb", memoryPeakMb)
    put("available_memory_before_mb", availableMemoryBeforeMb)
    put("battery_delta_percent", batteryDeltaPercent)
    put("battery_temperature_peak_celsius", batteryTemperaturePeakCelsius ?: JSONObject.NULL)
    put("thermal_peak", thermalPeak.name.lowercase())
    put("throughput_retention", throughputRetention)
}

private fun BenchmarkScoreV2.toJson() = JSONObject().apply {
    put("canonical", canonicalScore)
    put("headline", headlineScore)
    put("algorithm_id", algorithmId)
    put("dimensions", JSONObject().apply {
        put("inference", inference)
        put("responsiveness", responsiveness)
        put("memory", memory)
        put("sustained_performance", sustainedPerformance)
        put("stability", stability)
    })
}

private fun BenchmarkRunSample.toJson() = JSONObject().apply {
    put("run_index", runIndex)
    put("prompt_tokens", promptTokens)
    put("generated_tokens", generatedTokens)
    put("load_time_ms", loadTimeMs)
    put("prompt_eval_ms", promptEvalMs)
    put("first_token_ms", firstTokenMs)
    put("decode_ms", decodeMs)
    put("total_ms", totalMs)
    put("prefill_tokens_per_second", prefillTokensPerSecond)
    put("decode_tokens_per_second", decodeTokensPerSecond)
    put("memory_peak_mb", memoryPeakMb)
    put("available_memory_before_mb", availableMemoryBeforeMb)
    put("battery_percent_start", batteryPercentStart)
    put("battery_percent_end", batteryPercentEnd)
    put("battery_temperature_start_celsius", batteryTemperatureStartCelsius ?: JSONObject.NULL)
    put("battery_temperature_peak_celsius", batteryTemperaturePeakCelsius ?: JSONObject.NULL)
    put("battery_temperature_end_celsius", batteryTemperatureEndCelsius ?: JSONObject.NULL)
    put("thermal_start", thermalStart.name.lowercase())
    put("thermal_peak", thermalPeak.name.lowercase())
    put("thermal_end", thermalEnd.name.lowercase())
    put("charging_start", chargingStart)
    put("charging_end", chargingEnd)
    put("completed", completed)
    put("failure_kind", failureKind?.apiName ?: JSONObject.NULL)
}
