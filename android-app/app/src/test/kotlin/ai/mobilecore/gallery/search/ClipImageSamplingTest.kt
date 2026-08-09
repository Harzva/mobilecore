package ai.mobilecore.gallery.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClipImageSamplingTest {
    @Test
    fun `48 megapixel photo is bounded before bitmap allocation`() {
        val plan = ClipImageSampling.plan(width = 8_000, height = 6_000, targetSize = 224)

        assertEquals(16, plan.inSampleSize)
        assertEquals(1_000, plan.left)
        assertEquals(0, plan.top)
        assertEquals(6_000, plan.sourceCropWidth)
        assertEquals(375L * 375L, plan.plannedDecodedPixels)
        assertTrue(plan.plannedDecodedPixels <= ClipImageSampling.MAX_DECODED_PIXELS)
        assertTrue(plan.plannedDecodedBytes <= ClipImageSampling.MAX_DECODED_BYTES)
    }

    @Test
    fun `extreme aspect ratio decodes only centered square under byte budget`() {
        val plan = ClipImageSampling.plan(width = 100, height = 5_000_000, targetSize = 224)

        assertEquals(0, plan.left)
        assertEquals(2_499_950, plan.top)
        assertEquals(100, plan.sourceCropWidth)
        assertEquals(100, plan.sourceCropHeight)
        assertEquals(1, plan.inSampleSize)
        assertEquals(10_000L, plan.plannedDecodedPixels)
        assertEquals(40_000L, plan.plannedDecodedBytes)
    }

    @Test
    fun `legacy thumbnail plan never decodes a full narrow jpeg`() {
        val plan = ClipImageSampling.plan(width = 512, height = 65_535, targetSize = 512)

        assertEquals(0, plan.left)
        assertEquals(32_511, plan.top)
        assertEquals(512L * 512L, plan.plannedDecodedPixels)
        assertEquals(512L * 512L * 4L, plan.plannedDecodedBytes)
        assertTrue(plan.plannedDecodedPixels <= ClipImageSampling.MAX_DECODED_PIXELS)
        assertTrue(plan.plannedDecodedBytes <= ClipImageSampling.MAX_DECODED_BYTES)
    }

    @Test
    fun `small photos are not accidentally upsampled during decode`() {
        assertEquals(1, ClipImageSampling.inSampleSize(320, 240, 224))
    }

    @Test
    fun `allocation failure is mapped to typed image decode failure`() {
        try {
            GalleryImageDecodeSafety.guard<Unit> { throw OutOfMemoryError("simulated") }
            fail("Expected typed image decode failure")
        } catch (error: GallerySearchException) {
            assertEquals(GallerySearchFailureCode.IMAGE_DECODE_FAILED, error.failure.code)
            assertTrue(error.cause is OutOfMemoryError)
        }
    }

    @Test
    fun `all EXIF orientations have deterministic rotate and mirror transforms`() {
        assertEquals(ClipExifTransform.IDENTITY, ClipExifOrientation.transformFor(1))
        assertEquals(ClipExifTransform(0, true), ClipExifOrientation.transformFor(2))
        assertEquals(ClipExifTransform(180, false), ClipExifOrientation.transformFor(3))
        assertEquals(ClipExifTransform(180, true), ClipExifOrientation.transformFor(4))
        assertEquals(ClipExifTransform(90, true), ClipExifOrientation.transformFor(5))
        assertEquals(ClipExifTransform(90, false), ClipExifOrientation.transformFor(6))
        assertEquals(ClipExifTransform(270, true), ClipExifOrientation.transformFor(7))
        assertEquals(ClipExifTransform(270, false), ClipExifOrientation.transformFor(8))
    }
}
