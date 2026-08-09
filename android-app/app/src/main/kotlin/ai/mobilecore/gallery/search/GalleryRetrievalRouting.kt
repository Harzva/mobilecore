package ai.mobilecore.gallery.search

import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

enum class GalleryRetrievalTool(val wireName: String) {
    CLIP_DIRECT("clip_direct"),
    CANDIDATE_VERIFIER("candidate_verifier"),
}

data class GalleryRetrievalRouteRequest(
    val candidateCount: Int,
    val topSimilarity: Double?,
    val scoreMargin: Double?,
)

data class GalleryRetrievalRouteDecision(
    val tool: GalleryRetrievalTool,
    val reason: String,
)

fun interface GalleryRetrievalRouter {
    fun route(request: GalleryRetrievalRouteRequest): GalleryRetrievalRouteDecision
}

data class GalleryRerankCandidate(val mediaId: String, val score: Double)

fun interface GalleryCandidateReranker {
    /** Must select only from candidate IDs; raw model output is validated by the engine. */
    fun rerank(query: String, candidates: List<GallerySearchHit>): List<GalleryRerankCandidate>
}

data class GalleryRetrievalTrace(
    /** Null when the router failed before returning a tool decision. */
    val requestedTool: GalleryRetrievalTool?,
    val executedTool: GalleryRetrievalTool,
    val fallbackUsed: Boolean,
    val fallbackReason: String? = null,
)

data class GalleryRoutedHit(
    val hit: GallerySearchHit,
    val verifiedByReranker: Boolean,
)

data class GalleryRoutedResult(
    val hits: List<GalleryRoutedHit>,
    val trace: GalleryRetrievalTrace,
)

/**
 * Retrieval-specific closed tool router. A VLM can reorder only the CLIP candidate set and can
 * never manufacture a MediaStore identifier. Only a successfully executed reranker is verified.
 */
class AgenticGalleryRetrieval(
    private val router: GalleryRetrievalRouter,
    private val reranker: GalleryCandidateReranker?,
) {
    fun route(query: String, candidates: List<GallerySearchHit>, topK: Int): GalleryRoutedResult {
        require(topK > 0)
        val sorted = candidates.sortedByDescending { it.similarity }
        val decision = try {
            router.route(
                GalleryRetrievalRouteRequest(
                    candidateCount = sorted.size,
                    topSimilarity = sorted.firstOrNull()?.similarity,
                    scoreMargin = if (sorted.size >= 2) sorted[0].similarity - sorted[1].similarity else null,
                ),
            )
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            return direct(
                sorted,
                topK,
                requested = null,
                fallback = true,
                reason = if (error.isRoutingTimeout()) "router_timeout" else "router_failed",
            )
        }
        if (decision.tool == GalleryRetrievalTool.CLIP_DIRECT) {
            return direct(sorted, topK, decision.tool, fallback = false, reason = null)
        }
        val verifier = reranker ?: return direct(
            sorted,
            topK,
            decision.tool,
            fallback = true,
            reason = "candidate_verifier_unavailable",
        )
        val reranked = try {
            verifier.rerank(query, sorted)
        } catch (_: Exception) {
            return direct(sorted, topK, decision.tool, fallback = true, reason = "candidate_verifier_failed")
        }
        val byId = sorted.associateBy { it.photo.mediaId }
        if (reranked.map { it.mediaId }.distinct().size != reranked.size ||
            reranked.any { it.mediaId !in byId || !it.score.isFinite() }
        ) {
            return direct(
                sorted,
                topK,
                decision.tool,
                fallback = true,
                reason = "candidate_verifier_escaped_closed_set",
            )
        }
        if (reranked.isEmpty()) {
            return direct(
                sorted,
                topK,
                decision.tool,
                fallback = true,
                reason = "candidate_verifier_empty",
            )
        }
        val orderedIds = reranked.sortedByDescending { it.score }.map { it.mediaId }
        val completedIds = orderedIds + sorted.map { it.photo.mediaId }.filterNot(orderedIds::contains)
        return GalleryRoutedResult(
            hits = completedIds.take(topK).map { mediaId ->
                GalleryRoutedHit(
                    hit = requireNotNull(byId[mediaId]),
                    verifiedByReranker = mediaId in orderedIds,
                )
            },
            trace = GalleryRetrievalTrace(
                requestedTool = decision.tool,
                executedTool = GalleryRetrievalTool.CANDIDATE_VERIFIER,
                fallbackUsed = false,
            ),
        )
    }

    private fun direct(
        candidates: List<GallerySearchHit>,
        topK: Int,
        requested: GalleryRetrievalTool?,
        fallback: Boolean,
        reason: String?,
    ) = GalleryRoutedResult(
        candidates.take(topK).map { GalleryRoutedHit(it, verifiedByReranker = false) },
        GalleryRetrievalTrace(requested, GalleryRetrievalTool.CLIP_DIRECT, fallback, reason),
    )

    private fun Exception.isRoutingTimeout(): Boolean =
        this is TimeoutException || this is SocketTimeoutException
}
