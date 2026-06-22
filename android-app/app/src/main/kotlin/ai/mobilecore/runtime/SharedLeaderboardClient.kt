package ai.mobilecore.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class SharedLeaderboardConfig(
    val url: String,
    val anonKey: String,
    val table: String
) {
    val isConfigured: Boolean
        get() = url.startsWith("https://") && anonKey.isNotBlank() && table.isNotBlank()
}

class SharedLeaderboardConfigSource(private val context: Context) {
    fun load(): SharedLeaderboardConfig {
        val candidates = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, "mobilecore_supabase.json") },
            File(context.filesDir, "mobilecore_supabase.json")
        )

        candidates.firstOrNull { it.isFile }?.let { file ->
            runCatching { return parse(JSONObject(file.readText())) }
        }

        runCatching {
            context.assets.open("supabase_leaderboard.json").bufferedReader().use { reader ->
                return parse(JSONObject(reader.readText()))
            }
        }

        return SharedLeaderboardConfig(url = "", anonKey = "", table = "mobilecore_leaderboard")
    }

    private fun parse(json: JSONObject): SharedLeaderboardConfig {
        return SharedLeaderboardConfig(
            url = json.optString("url").trimEnd('/'),
            anonKey = json.optString("anon_key"),
            table = json.optString("table", "mobilecore_leaderboard").ifBlank { "mobilecore_leaderboard" }
        )
    }
}

class SharedLeaderboardClient(private val config: SharedLeaderboardConfig) {
    fun fetch(limit: Int): JSONObject {
        if (!config.isConfigured) return notConfigured()
        return runCatching {
            val safeLimit = limit.coerceIn(1, 50)
            val response = request(
                method = "GET",
                path = "/rest/v1/${config.table}?select=*&order=score_total.desc&limit=$safeLimit",
                body = null,
                prefer = null
            )
            val data = JSONArray(response.body)
            JSONObject().apply {
                put("object", "leaderboard")
                put("scope", "shared")
                put("status", "ok")
                put("count", data.length())
                put(
                    "data",
                    JSONArray().apply {
                        for (index in 0 until data.length()) {
                            put(remoteEntryToJson(index + 1, data.optJSONObject(index) ?: JSONObject()))
                        }
                    }
                )
            }
        }.getOrElse { error ->
            unavailable(error)
        }
    }

    fun upload(entries: List<BenchmarkLeaderboardEntry>): JSONObject {
        if (!config.isConfigured) return notConfigured()
        if (entries.isEmpty()) {
            return JSONObject().apply {
                put("object", "leaderboard.sync")
                put("scope", "shared")
                put("status", "empty")
                put("uploaded", 0)
            }
        }

        return runCatching {
            val body = JSONArray().apply {
                entries.forEach { entry -> put(entry.toSupabaseJson()) }
            }.toString()
            val response = request(
                method = "POST",
                path = "/rest/v1/${config.table}?on_conflict=run_id",
                body = body,
                prefer = "resolution=merge-duplicates,return=representation"
            )
            val uploaded = runCatching { JSONArray(response.body).length() }.getOrDefault(entries.size)
            JSONObject().apply {
                put("object", "leaderboard.sync")
                put("scope", "shared")
                put("status", "ok")
                put("uploaded", uploaded)
            }
        }.getOrElse { error ->
            unavailable(error).apply {
                put("object", "leaderboard.sync")
                put("uploaded", 0)
            }
        }
    }

    private fun request(method: String, path: String, body: String?, prefer: String?): SupabaseResponse {
        val connection = (URL("${config.url}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3500
            readTimeout = 6500
            setRequestProperty("apikey", config.anonKey)
            setRequestProperty("Authorization", "Bearer ${config.anonKey}")
            setRequestProperty("Content-Type", "application/json")
            if (prefer != null) setRequestProperty("Prefer", prefer)
            if (body != null) doOutput = true
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.readText().orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("Supabase HTTP $status: ${responseBody.take(180)}")
            }
            return SupabaseResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun BenchmarkLeaderboardEntry.toSupabaseJson(): JSONObject {
        return JSONObject().apply {
            put("run_id", runId)
            put("spec_id", specId)
            put("created_at_ms", createdAtMs)
            put("device_name", deviceName)
            put("model_id", modelId)
            put("quantization", quantization)
            put("model_size_bytes", modelSizeBytes)
            put("score_total", score.total)
            put("score_speed", score.speed)
            put("score_response", score.response)
            put("score_memory", score.memory)
            put("score_stability", score.stability)
            put("load_time_ms", loadTimeMs)
            put("decode_tokens_per_second", decodeTokensPerSecond)
            put("first_token_ms", firstTokenMs)
            put("total_ms", totalMs)
            put("memory_peak_mb", memoryPeakMb)
        }
    }

    private fun remoteEntryToJson(rank: Int, item: JSONObject): JSONObject {
        return JSONObject().apply {
            put("rank", rank)
            put("run_id", item.optString("run_id"))
            put("spec_id", item.optString("spec_id", "mobilecore-benchmark-v1"))
            put("created_at_ms", item.optLong("created_at_ms"))
            put("device_name", item.optString("device_name"))
            put("model_id", item.optString("model_id"))
            put("quantization", item.optString("quantization", "unknown"))
            put("model_size_bytes", item.optLong("model_size_bytes"))
            put(
                "score",
                JSONObject().apply {
                    put("total", item.optInt("score_total"))
                    put("speed", item.optInt("score_speed"))
                    put("response", item.optInt("score_response"))
                    put("memory", item.optInt("score_memory"))
                    put("stability", item.optInt("score_stability"))
                }
            )
            put("load_time_ms", item.optLong("load_time_ms"))
            put("decode_tokens_per_second", item.optDouble("decode_tokens_per_second"))
            put("first_token_ms", item.optLong("first_token_ms"))
            put("total_ms", item.optLong("total_ms"))
            put("memory_peak_mb", item.optLong("memory_peak_mb"))
        }
    }

    private fun notConfigured(): JSONObject {
        return JSONObject().apply {
            put("object", "leaderboard")
            put("scope", "shared")
            put("status", "not_configured")
            put("message", "Add mobilecore_supabase.json with url, anon_key, and table to enable shared leaderboard.")
            put("data", JSONArray())
        }
    }

    private fun unavailable(error: Throwable): JSONObject {
        return JSONObject().apply {
            put("object", "leaderboard")
            put("scope", "shared")
            put("status", "unavailable")
            put("message", error.message ?: error.javaClass.simpleName)
            put("data", JSONArray())
        }
    }

    private data class SupabaseResponse(val status: Int, val body: String)
}
