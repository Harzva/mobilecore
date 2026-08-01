package ai.mobilecore.g2d

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QwenVisionMediaCacheTest {
    @Test
    fun repeatedImageReusesEmbeddingAndKvPrefixWithoutChangingOutput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = requireNotNull(instrumentation.targetContext.getExternalFilesDir("g2d"))
        val model = File(root, "models/Qwen3.5-0.8B-Q4_K_M.gguf")
        val projector = File(root, "models/mmproj-Qwen3.5-0.8B-BF16.gguf")
        val image = File(root, "images/Abyssinian_201.jpg")
        val verifierInput = G2dVerifierInput(
            candidates = listOf(
                G2dVerifierCandidate(0, "Abyssinian", 1, 0.70),
                G2dVerifierCandidate(11, "Bengal", 2, 0.20),
                G2dVerifierCandidate(27, "Russian_Blue", 3, 0.10),
            ),
            noProb = false,
        )
        lateinit var cachedVerifier: QwenVisionResponse
        QwenVisionRuntime(model, projector, OxfordPetsDataset.CLASS_NAMES).use { runtime ->
            val first = runtime.standaloneResponse(image)
            val cached = runtime.standaloneResponse(image)
            assertEquals(first.text, cached.text)
            assertTrue(
                "Cached inference must be faster: first=${first.totalMs}, cached=${cached.totalMs}",
                cached.totalMs < first.totalMs,
            )
            cachedVerifier = runtime.verifyResponse(image, verifierInput)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("g2d_cache_output", cached.text)
                putDouble("g2d_cache_first_ms", first.totalMs)
                putDouble("g2d_cache_hit_ms", cached.totalMs)
                putDouble("g2d_kv_verifier_hit_ms", cachedVerifier.totalMs)
            })
        }
        QwenVisionRuntime(model, projector, OxfordPetsDataset.CLASS_NAMES).use { coldRuntime ->
            val coldVerifier = coldRuntime.verifyResponse(image, verifierInput)
            assertEquals(coldVerifier.text, cachedVerifier.text)
            assertTrue(
                "KV-prefix verifier must be faster: cold=${coldVerifier.totalMs}, hit=${cachedVerifier.totalMs}",
                cachedVerifier.totalMs < coldVerifier.totalMs,
            )
            instrumentation.sendStatus(0, Bundle().apply {
                putString("g2d_kv_verifier_output", cachedVerifier.text)
                putDouble("g2d_kv_verifier_cold_ms", coldVerifier.totalMs)
            })
        }
    }
}
