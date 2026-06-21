package ai.mobilecore.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File

class RecommendationScoringConfigSource(private val context: Context) {
    private val externalConfigFile: File
        get() = File(context.getExternalFilesDir("config") ?: context.filesDir, CONFIG_FILE_NAME)

    fun configFor(modeName: String?): RecommendationScoringConfig {
        val configJson = readConfigJson()
        val configuredDefault = configJson
            ?.optJSONObject("defaults")
            ?.optString("preference")
            ?.takeIf { it.isNotBlank() }

        val effectiveMode = modeName?.takeIf { it.isNotBlank() } ?: configuredDefault
        val fallback = RecommendationScoringConfig.fromModeName(effectiveMode)
        val profileKey = profileKeyFor(fallback.mode)
        val profile = configJson
            ?.optJSONObject("profiles")
            ?.optJSONObject(profileKey)
            ?: return fallback

        return fallback.copy(
            fitWeight = profile.optClampedDouble("fit_weight", fallback.fitWeight, 0.0, 1.0),
            speedWeight = profile.optClampedDouble("speed_weight", fallback.speedWeight, 0.0, 1.0),
            sizeWeight = profile.optClampedDouble("size_weight", fallback.sizeWeight, 0.0, 1.0),
            measuredSpeedWeight = profile.optClampedDouble(
                "measured_speed_weight",
                fallback.measuredSpeedWeight,
                0.0,
                1.0
            ),
            baseTokensPerSecond = profile.optClampedDouble("base_tps", fallback.baseTokensPerSecond, 0.1, 100.0),
            targetTokensPerSecond = profile.optClampedDouble("target_tps", fallback.targetTokensPerSecond, 0.1, 100.0),
            memoryPressurePenalty = profile.optClampedDouble("memory_penalty", fallback.memoryPressurePenalty, 0.0, 100.0)
        )
    }

    private fun readConfigJson(): JSONObject? {
        val raw = runCatching {
            externalConfigFile.parentFile?.mkdirs()
            if (externalConfigFile.isFile) {
                externalConfigFile.readText()
            } else {
                context.assets.open(CONFIG_FILE_NAME).bufferedReader().use { it.readText() }
            }
        }.getOrNull()
        return raw?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun profileKeyFor(mode: RecommendationPreferenceMode): String {
        return when (mode) {
            RecommendationPreferenceMode.SPEED -> "speed_first"
            RecommendationPreferenceMode.STABILITY -> "stability_first"
            RecommendationPreferenceMode.SMALL_MODEL -> "small_model_first"
        }
    }

    private fun JSONObject.optClampedDouble(
        name: String,
        fallback: Double,
        min: Double,
        max: Double
    ): Double {
        val value = optDouble(name, fallback)
        return if (value.isFinite()) {
            value.coerceIn(min, max)
        } else {
            fallback
        }
    }

    companion object {
        const val CONFIG_FILE_NAME = "recommendation_scoring.json"
    }
}
