package ai.mobilecore.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileCoreHealthContractTest {
    @Test
    fun reportsEveryInputAndOutputModalityIndependently() {
        val snapshot = fixture()
        val json = snapshot.toJson()
        val capabilities = json.getJSONObject("capabilities")

        assertTrue(capabilities.getBoolean("text_input"))
        assertTrue(capabilities.getBoolean("image_input"))
        assertTrue(capabilities.getBoolean("audio_input"))
        assertFalse(capabilities.getBoolean("video_input"))
        assertTrue(capabilities.getBoolean("text_output"))
        assertFalse(capabilities.getBoolean("audio_output"))
        assertEquals("llama.cpp/libmtmd", json.getString("runtime"))
        assertEquals("cpu", json.getString("backend"))
    }

    @Test
    fun exposesExpectedDigestsWithoutClaimingUnverifiedFilesAreInstalled() {
        val json = fixture().toJson().getJSONObject("artifacts")

        assertEquals(64, json.getJSONObject("main").getString("digest").length)
        assertFalse(json.getJSONObject("main").getBoolean("present"))
        assertFalse(json.getJSONObject("main").getBoolean("verified"))
        assertFalse(json.getJSONObject("mmproj").getBoolean("verified"))
    }

    @Test
    fun givesTypedPreflightFailure() {
        val json = fixture().toJson().getJSONObject("preflight")

        assertFalse(json.getBoolean("ok"))
        assertEquals("insufficient_memory", json.getString("failure_code"))
    }

    private fun fixture() = MobileCoreHealthSnapshot(
        version = "test",
        activeModel = null,
        quantization = "Q4_K_M + Q8_0 mmproj",
        modelLoaded = false,
        runtime = "llama.cpp/libmtmd",
        backend = "cpu",
        llamaRevision = "e1af89a6815737a5db132eee23a94a8ee58553e0",
        capabilities = ModalityCapabilities(
            textInput = true,
            imageInput = true,
            audioInput = true,
            videoInput = false,
            textOutput = true,
            audioOutput = false,
        ),
        mainArtifact = ArtifactHealth(
            fileName = "Qwen2.5-Omni-3B-Q4_K_M.gguf",
            expectedSha256 = "4b0bd358c1e9ec55dd3055ef6d71c958c821533d85916a10cfa89c4552a86e29",
            expectedBytes = 2_104_931_648,
            present = false,
            verified = false,
        ),
        projectorArtifact = ArtifactHealth(
            fileName = "mmproj-Qwen2.5-Omni-3B-Q8_0.gguf",
            expectedSha256 = "4e6c816cd33f7298d07cb780c136a396631e50e62f6501660271f8c6e302e565",
            expectedBytes = 1_538_031_328,
            present = false,
            verified = false,
        ),
        preflight = ResourcePreflightHealth(
            availableMemoryBytes = 1_000,
            requiredMemoryBytes = 2_000,
            availableStorageBytes = 10_000,
            requiredStorageBytes = 5_000,
        ),
    )
}
