package ai.mobilecore.gallery.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryClipProvenanceTest {
    @Test
    fun `exact audited artifact set receives verified upstream identity`() {
        val identity = GalleryClipProvenance.resolve(
            GalleryClipProvenance.AUDITED_ONNX_COMMUNITY_DIGEST,
        )

        assertEquals("onnx-community/clip-vit-base-patch16-ONNX", identity.modelId)
        assertTrue(identity.verified)
    }

    @Test
    fun `ABI-compatible unknown artifact set remains explicitly user imported`() {
        val identity = GalleryClipProvenance.resolve("f".repeat(64))

        assertEquals("user-imported/clip-compatible", identity.modelId)
        assertFalse(identity.verified)
    }
}
