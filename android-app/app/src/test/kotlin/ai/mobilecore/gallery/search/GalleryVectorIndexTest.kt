package ai.mobilecore.gallery.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryVectorIndexTest {
    @Test
    fun `normalizes embeddings and returns exact top-k cosine order`() {
        val index = GalleryVectorIndex.empty(descriptor())
        index.upsert(entry("east", floatArrayOf(4f, 0f, 0f)))
        index.upsert(entry("north", floatArrayOf(0f, 3f, 0f)))
        index.upsert(entry("diagonal", floatArrayOf(1f, 1f, 0f)))

        val hits = index.search(NormalizedEmbedding.from(floatArrayOf(9f, 1f, 0f)), topK = 2)

        assertEquals(listOf("east", "diagonal"), hits.map { it.photo.mediaId })
        assertTrue(hits[0].similarity > hits[1].similarity)
    }

    @Test(expected = GallerySearchException::class)
    fun `rejects zero vectors instead of persisting invalid embeddings`() {
        NormalizedEmbedding.from(floatArrayOf(0f, 0f, 0f))
    }

    private fun descriptor() = GalleryClipRuntimeDescriptor(
        modelId = "fake-clip",
        modelDigest = "a".repeat(64),
        embeddingDimension = 3,
        imageEncoderName = "fake-image",
        textEncoderName = "fake-text",
        tokenizerName = "fake-tokenizer",
    )

    private fun entry(id: String, vector: FloatArray) = GalleryIndexedPhoto(
        GalleryPhoto(id, "content://photos/$id", id, 1L, 1L, "image/jpeg"),
        NormalizedEmbedding.from(vector),
    )
}
