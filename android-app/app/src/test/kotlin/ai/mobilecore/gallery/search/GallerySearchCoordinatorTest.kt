package ai.mobilecore.gallery.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class GallerySearchCoordinatorTest {
    @Test
    fun `incremental run reuses unchanged items and removes deleted items`() {
        val store = MemoryStore()
        var photos = listOf(photo("one", 1), photo("two", 1))
        val runtime = FakeRuntime()
        val coordinator = GallerySearchCoordinator(
            GalleryPhotoDiscovery { photos },
            GalleryMediaReader { ByteArrayInputStream(byteArrayOf(1)) },
            runtime,
            store,
            nowMs = { 10L },
            checkpointEvery = 10,
        )
        val first = coordinator.buildOrUpdateIndex(selection(photos)) as GalleryIndexOutcome.Completed
        assertEquals(2, first.stats.encodedCount)
        assertEquals(2, runtime.imageCalls)

        photos = listOf(photo("one", 1), photo("three", 1))
        val second = coordinator.buildOrUpdateIndex(selection(photos)) as GalleryIndexOutcome.Completed

        assertEquals(1, second.stats.encodedCount)
        assertEquals(1, second.stats.reusedCount)
        assertEquals(1, second.stats.removedCount)
        assertEquals(setOf("one", "three"), second.snapshot.entries.map { it.photo.mediaId }.toSet())
    }

    @Test
    fun `cancellation checkpoints partial work and resumes it`() {
        val photos = (1..5).map { photo("p$it", 1) }
        val token = GalleryCancellationToken()
        val runtime = FakeRuntime(onImageCall = { calls -> if (calls == 2) token.cancel() })
        val store = MemoryStore()
        val coordinator = GallerySearchCoordinator(
            GalleryPhotoDiscovery { photos },
            GalleryMediaReader { ByteArrayInputStream(byteArrayOf(1)) },
            runtime,
            store,
            nowMs = { 20L },
            checkpointEvery = 5,
        )

        val cancelled = coordinator.buildOrUpdateIndex(selection(photos), token)

        assertTrue(cancelled is GalleryIndexOutcome.Cancelled)
        assertEquals(2, (cancelled as GalleryIndexOutcome.Cancelled).snapshot.entries.size)

        val resumed = coordinator.buildOrUpdateIndex(selection(photos)) as GalleryIndexOutcome.Completed
        assertEquals(2, resumed.stats.reusedCount)
        assertEquals(3, resumed.stats.encodedCount)
        assertEquals(5, resumed.snapshot.entries.size)
    }

    @Test
    fun `text query is searched in memory and never persisted`() {
        val photos = listOf(photo("red", 1), photo("blue", 1))
        val store = MemoryStore()
        val coordinator = GallerySearchCoordinator(
            GalleryPhotoDiscovery { photos },
            GalleryMediaReader { ByteArrayInputStream(byteArrayOf(1)) },
            FakeRuntime(),
            store,
            nowMs = { 30L },
        )
        coordinator.buildOrUpdateIndex(selection(photos))

        val result = coordinator.search("red", 1) as GalleryQueryOutcome.Results

        assertEquals("red", result.hits.single().photo.mediaId)
        assertFalse(store.savedQueries)
    }

    @Test
    fun `changed model digest rebuilds all vectors and reports invalidation`() {
        val photos = listOf(photo("one", 1), photo("two", 1))
        val store = MemoryStore().apply {
            snapshot = GalleryVectorIndexSnapshot(
                modelDigest = "f".repeat(64),
                dimension = 3,
                updatedAtMs = 1L,
                entries = emptyList(),
            )
        }
        val coordinator = GallerySearchCoordinator(
            GalleryPhotoDiscovery { photos },
            GalleryMediaReader { ByteArrayInputStream(byteArrayOf(1)) },
            FakeRuntime(),
            store,
            nowMs = { 40L },
        )

        val result = coordinator.buildOrUpdateIndex(selection(photos)) as GalleryIndexOutcome.Completed

        assertTrue(result.stats.invalidatedPreviousModel)
        assertEquals(2, result.stats.encodedCount)
        assertEquals("e".repeat(64), result.snapshot.modelDigest)
    }

    @Test
    fun `one corrupt photo is skipped without blocking the remaining gallery`() {
        val photos = listOf(photo("bad", 1), photo("good", 1))
        val store = MemoryStore()
        val coordinator = GallerySearchCoordinator(
            GalleryPhotoDiscovery { photos },
            GalleryMediaReader { ByteArrayInputStream(byteArrayOf(1)) },
            FakeRuntime(recoverableFailureMediaId = "bad"),
            store,
            nowMs = { 50L },
        )

        val result = coordinator.buildOrUpdateIndex(selection(photos)) as GalleryIndexOutcome.Completed

        assertEquals(1, result.stats.encodedCount)
        assertEquals(1, result.stats.skippedCount)
        assertEquals(listOf("good"), result.snapshot.entries.map { it.photo.mediaId })
    }

    private fun photo(id: String, version: Long) = GalleryPhoto(
        id,
        "content://photos/$id",
        "$id.jpg",
        version,
        version,
        "image/jpeg",
    )

    private fun selection(photos: List<GalleryPhoto>) =
        GalleryPhotoSelection.GrantedContentUris(photos.mapTo(linkedSetOf()) { it.contentUri })

    private class FakeRuntime(
        private val onImageCall: (Int) -> Unit = {},
        private val recoverableFailureMediaId: String? = null,
    ) : GalleryClipRuntime {
        override val descriptor = GalleryClipRuntimeDescriptor(
            "fake",
            "e".repeat(64),
            3,
            "fake-image",
            "fake-text",
            "fake-tokenizer",
        )
        var imageCalls = 0

        override fun embedImage(
            photo: GalleryPhoto,
            mediaReader: GalleryMediaReader,
        ): NormalizedEmbedding {
            mediaReader.open(photo).close()
            imageCalls += 1
            onImageCall(imageCalls)
            if (photo.mediaId == recoverableFailureMediaId) {
                throw GallerySearchException(
                    GallerySearchFailure(
                        GallerySearchFailureCode.IMAGE_DECODE_FAILED,
                        "The controlled test photo is corrupt.",
                        retryable = true,
                    ),
                )
            }
            return when (photo.mediaId) {
                "red" -> NormalizedEmbedding.from(floatArrayOf(1f, 0f, 0f))
                "blue" -> NormalizedEmbedding.from(floatArrayOf(0f, 1f, 0f))
                else -> NormalizedEmbedding.from(floatArrayOf(1f, 1f, 1f))
            }
        }

        override fun embedText(query: String): NormalizedEmbedding = when (query) {
            "red" -> NormalizedEmbedding.from(floatArrayOf(1f, 0f, 0f))
            else -> NormalizedEmbedding.from(floatArrayOf(0f, 1f, 0f))
        }
    }

    private class MemoryStore : GalleryIndexStore {
        var snapshot: GalleryVectorIndexSnapshot? = null
        var savedQueries = false

        override fun load(expectedModelDigest: String): GalleryIndexLoadResult {
            val current = snapshot ?: return GalleryIndexLoadResult.Missing
            return if (current.modelDigest == expectedModelDigest) GalleryIndexLoadResult.Ready(current)
            else GalleryIndexLoadResult.Invalidated(current.modelDigest, expectedModelDigest)
        }

        override fun save(snapshot: GalleryVectorIndexSnapshot) {
            this.snapshot = snapshot
        }

        override fun clear() {
            snapshot = null
        }
    }
}
