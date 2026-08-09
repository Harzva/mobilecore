package ai.mobilecore.gallery.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeoutException

class GalleryRetrievalRoutingTest {
    @Test
    fun `reranker cannot introduce a media id outside clip candidates`() {
        val engine = AgenticGalleryRetrieval(
            router = GalleryRetrievalRouter {
                GalleryRetrievalRouteDecision(GalleryRetrievalTool.CANDIDATE_VERIFIER, "ambiguous")
            },
            reranker = GalleryCandidateReranker { _, _ ->
                listOf(GalleryRerankCandidate("hallucinated-media-id", 1.0))
            },
        )

        val result = engine.route("cat", candidates(), topK = 2)

        assertTrue(result.trace.fallbackUsed)
        assertEquals("candidate_verifier_escaped_closed_set", result.trace.fallbackReason)
        assertEquals(GalleryRetrievalTool.CLIP_DIRECT, result.trace.executedTool)
        assertEquals(listOf("a", "b"), result.hits.map { it.hit.photo.mediaId })
        assertTrue(result.hits.none { it.verifiedByReranker })
    }

    @Test
    fun `only a successful closed-set rerank is marked verified`() {
        val engine = AgenticGalleryRetrieval(
            router = GalleryRetrievalRouter {
                GalleryRetrievalRouteDecision(GalleryRetrievalTool.CANDIDATE_VERIFIER, "small margin")
            },
            reranker = GalleryCandidateReranker { _, _ ->
                listOf(GalleryRerankCandidate("b", 0.9), GalleryRerankCandidate("a", 0.1))
            },
        )

        val result = engine.route("cat", candidates(), topK = 2)

        assertFalse(result.trace.fallbackUsed)
        assertEquals(GalleryRetrievalTool.CANDIDATE_VERIFIER, result.trace.executedTool)
        assertEquals(listOf("b", "a"), result.hits.map { it.hit.photo.mediaId })
        assertTrue(result.hits.all { it.verifiedByReranker })
    }

    @Test
    fun `candidates omitted by a partial reranker are appended as unverified clip results`() {
        val engine = AgenticGalleryRetrieval(
            router = GalleryRetrievalRouter {
                GalleryRetrievalRouteDecision(GalleryRetrievalTool.CANDIDATE_VERIFIER, "verify one")
            },
            reranker = GalleryCandidateReranker { _, _ ->
                listOf(GalleryRerankCandidate("b", 0.9))
            },
        )

        val result = engine.route("cat", candidates(), topK = 2)

        assertEquals(listOf("b", "a"), result.hits.map { it.hit.photo.mediaId })
        assertEquals(listOf(true, false), result.hits.map { it.verifiedByReranker })
    }

    @Test
    fun `empty verifier output falls back to clip and marks nothing verified`() {
        val engine = AgenticGalleryRetrieval(
            router = GalleryRetrievalRouter {
                GalleryRetrievalRouteDecision(GalleryRetrievalTool.CANDIDATE_VERIFIER, "verify")
            },
            reranker = GalleryCandidateReranker { _, _ -> emptyList() },
        )

        val result = engine.route("cat", candidates(), topK = 2)

        assertTrue(result.trace.fallbackUsed)
        assertEquals("candidate_verifier_empty", result.trace.fallbackReason)
        assertTrue(result.hits.none { it.verifiedByReranker })
    }

    @Test
    fun `router exception fails closed to sorted clip candidates`() {
        val engine = AgenticGalleryRetrieval(
            router = GalleryRetrievalRouter { error("agent router crashed") },
            reranker = GalleryCandidateReranker { _, _ ->
                error("reranker must not execute after a router failure")
            },
        )

        val result = engine.route("cat", candidates().reversed(), topK = 2)

        assertTrue(result.trace.fallbackUsed)
        assertEquals("router_failed", result.trace.fallbackReason)
        assertNull(result.trace.requestedTool)
        assertEquals(GalleryRetrievalTool.CLIP_DIRECT, result.trace.executedTool)
        assertEquals(listOf("a", "b"), result.hits.map { it.hit.photo.mediaId })
        assertTrue(result.hits.none { it.verifiedByReranker })
    }

    @Test
    fun `router timeout fails closed without invoking verifier`() {
        val engine = AgenticGalleryRetrieval(
            router = GalleryRetrievalRouter { throw TimeoutException("deadline exceeded") },
            reranker = GalleryCandidateReranker { _, _ ->
                error("reranker must not execute after a router timeout")
            },
        )

        val result = engine.route("cat", candidates(), topK = 1)

        assertTrue(result.trace.fallbackUsed)
        assertEquals("router_timeout", result.trace.fallbackReason)
        assertNull(result.trace.requestedTool)
        assertEquals(GalleryRetrievalTool.CLIP_DIRECT, result.trace.executedTool)
        assertEquals(listOf("a"), result.hits.map { it.hit.photo.mediaId })
    }

    private fun candidates() = listOf(hit("a", 0.8), hit("b", 0.7))

    private fun hit(id: String, score: Double) = GallerySearchHit(
        GalleryPhoto(id, "content://photos/$id", id, 1L, 1L, "image/jpeg"),
        score,
    )
}
