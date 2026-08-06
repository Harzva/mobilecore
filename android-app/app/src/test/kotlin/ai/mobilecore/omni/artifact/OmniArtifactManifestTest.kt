package ai.mobilecore.omni.artifact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniArtifactManifestTest {
    @Test
    fun pinnedManifestContainsExactVerifiedPair() {
        val manifest = Qwen25Omni3bArtifacts.manifest

        assertTrue(manifest.validationErrors().isEmpty())
        assertEquals(3_642_962_976L, manifest.totalArtifactBytes)
        assertEquals("ggml-org", manifest.conversionPublisher)
        assertEquals("qwen-research", manifest.licenseId)
        assertEquals(
            "e1af89a6815737a5db132eee23a94a8ee58553e0",
            Qwen25Omni3bArtifacts.LLAMA_CPP_REVISION
        )
        assertEquals(
            "4b0bd358c1e9ec55dd3055ef6d71c958c821533d85916a10cfa89c4552a86e29",
            manifest.artifact(OmniArtifactRole.MAIN)?.sha256
        )
        assertEquals(
            "4e6c816cd33f7298d07cb780c136a396631e50e62f6501660271f8c6e302e565",
            manifest.artifact(OmniArtifactRole.MMPROJ)?.sha256
        )
    }

    @Test
    fun incompletePairIsNotInstallable() {
        val manifest = Qwen25Omni3bArtifacts.manifest.copy(
            artifacts = listOf(Qwen25Omni3bArtifacts.manifest.artifacts.first())
        )

        assertTrue("artifact_pair_required" in manifest.validationErrors())
        assertTrue("mmproj_artifact_required" in manifest.validationErrors())
    }
}
