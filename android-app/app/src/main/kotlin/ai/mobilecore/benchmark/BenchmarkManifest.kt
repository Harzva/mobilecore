package ai.mobilecore.benchmark

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONObject

data class BenchmarkModelManifest(
    val id: String,
    val source: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String
)

data class BenchmarkPromptManifest(
    val assetId: String,
    val assetPath: String,
    val sizeBytes: Long,
    val sha256: String
)

data class BenchmarkManifest(
    val specId: String,
    val specVersion: Int,
    val scoreAlgorithmId: String,
    val platformPopulation: String,
    val runtimeVersion: String,
    val llamaCppRevision: String,
    val model: BenchmarkModelManifest,
    val prompt: BenchmarkPromptManifest
)

object BenchmarkManifestParser {
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")

    fun parse(json: String): BenchmarkManifest {
        val root = JSONObject(json)
        val modelJson = root.getJSONObject("model")
        val promptJson = root.getJSONObject("prompt")
        val manifest = BenchmarkManifest(
            specId = root.getString("spec_id"),
            specVersion = root.getInt("spec_version"),
            scoreAlgorithmId = root.getString("score_algorithm_id"),
            platformPopulation = root.getString("platform_population"),
            runtimeVersion = root.getString("runtime_version"),
            llamaCppRevision = root.getString("llama_cpp_revision"),
            model = BenchmarkModelManifest(
                id = modelJson.getString("id"),
                source = modelJson.getString("source"),
                fileName = modelJson.getString("file_name"),
                sizeBytes = modelJson.getLong("size_bytes"),
                sha256 = modelJson.getString("sha256"),
                license = modelJson.getString("license")
            ),
            prompt = BenchmarkPromptManifest(
                assetId = promptJson.getString("asset_id"),
                assetPath = promptJson.getString("asset_path"),
                sizeBytes = promptJson.getLong("size_bytes"),
                sha256 = promptJson.getString("sha256")
            )
        )

        require(manifest.specId == BenchmarkSpecV2.SPEC_ID) { "Unexpected benchmark spec" }
        require(manifest.specVersion == 2) { "Unsupported benchmark spec version" }
        require(manifest.scoreAlgorithmId == BenchmarkSpecV2.SCORE_ALGORITHM_ID) {
            "Unexpected score algorithm"
        }
        require(manifest.platformPopulation.isNotBlank()) { "Missing platform population" }
        require(manifest.model.sizeBytes > 0L) { "Invalid model size" }
        require(manifest.prompt.sizeBytes > 0L) { "Invalid prompt size" }
        require(sha256Pattern.matches(manifest.model.sha256)) { "Invalid model digest" }
        require(sha256Pattern.matches(manifest.prompt.sha256)) { "Invalid prompt digest" }
        return manifest
    }
}

object BenchmarkDigestVerifier {
    fun sha256(file: File): String = file.inputStream().use(::sha256)

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(file: File, expectedSha256: String): Boolean =
        file.isFile && sha256(file).equals(expectedSha256, ignoreCase = true)
}

class BenchmarkManifestRepository(private val context: Context) {
    fun load(): BenchmarkManifest {
        val bytes = context.assets.open(MANIFEST_ASSET_PATH).use { it.readBytes() }
        val actualDigest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(actualDigest == EXPECTED_MANIFEST_SHA256) { "Benchmark manifest integrity check failed" }
        return BenchmarkManifestParser.parse(bytes.toString(Charsets.UTF_8))
    }

    fun loadPrompt(manifest: BenchmarkManifest): String {
        val bytes = context.assets.open(manifest.prompt.assetPath).use { it.readBytes() }
        check(bytes.size.toLong() == manifest.prompt.sizeBytes) { "Benchmark prompt size mismatch" }
        val actualDigest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(actualDigest == manifest.prompt.sha256) { "Benchmark prompt integrity check failed" }
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        const val MANIFEST_ASSET_PATH = "benchmark/tuima-benchmark-manifest-v2.json"
        const val EXPECTED_MANIFEST_SHA256 =
            "45e1ce81a9154dbf6e369e60f75f94a60399a9c334dea47af5ffb15c540d709e"
    }
}
