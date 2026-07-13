package ai.mobilecore.benchmark

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkManifestTest {
    @Test
    fun parsesFrozenRc2Manifest() {
        val manifest = BenchmarkManifestParser.parse(MANIFEST_JSON)

        assertEquals(BenchmarkSpecV2.SPEC_ID, manifest.specId)
        assertEquals(BenchmarkSpecV2.SCORE_ALGORITHM_ID, manifest.scoreAlgorithmId)
        assertEquals("android-arm64-v8a", manifest.platformPopulation)
        assertEquals("qwen2.5-0.5b-instruct-q4_k_m", manifest.model.id)
        assertEquals(491_400_032L, manifest.model.sizeBytes)
        assertEquals(64, manifest.model.sha256.length)
        assertEquals("tuima-standard-prompt-v2", manifest.prompt.assetId)
    }

    @Test
    fun sha256VerifierAcceptsExactBytesAndRejectsMutation() {
        val file = File.createTempFile("tuima-manifest-test", ".txt")
        file.writeText("tuima")

        assertTrue(
            BenchmarkDigestVerifier.matches(
                file,
                "4a55298000d20d63f500a896c2d4b0ba255f21972ea516ee6e41fb8fecfae0bf"
            )
        )
        file.appendText("-changed")
        assertFalse(
            BenchmarkDigestVerifier.matches(
                file,
                "4a55298000d20d63f500a896c2d4b0ba255f21972ea516ee6e41fb8fecfae0bf"
            )
        )
        file.delete()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongSpecIdentity() {
        BenchmarkManifestParser.parse(MANIFEST_JSON.replace(BenchmarkSpecV2.SPEC_ID, "other-spec"))
    }

    private companion object {
        val MANIFEST_JSON = """
            {
              "spec_id":"tuima-llm-benchmark-v2",
              "spec_version":2,
              "score_algorithm_id":"tuima-score-v2",
              "platform_population":"android-arm64-v8a",
              "runtime_version":"0.1.3-rc2",
              "llama_cpp_revision":"063d9c156e816ae3cf62db01f429a07a099afe97",
              "model":{
                "id":"qwen2.5-0.5b-instruct-q4_k_m",
                "source":"Qwen/Qwen2.5-0.5B-Instruct-GGUF",
                "file_name":"qwen2.5-0.5b-instruct-q4_k_m.gguf",
                "size_bytes":491400032,
                "sha256":"74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
                "license":"apache-2.0"
              },
              "prompt":{
                "asset_id":"tuima-standard-prompt-v2",
                "asset_path":"benchmark/tuima-standard-prompt-v2.txt",
                "size_bytes":362,
                "sha256":"bb2fa9325856ac679aaad7d83052d07f57524bcca545f63878a6bfdab8fd0993"
              }
            }
        """.trimIndent()
    }
}
