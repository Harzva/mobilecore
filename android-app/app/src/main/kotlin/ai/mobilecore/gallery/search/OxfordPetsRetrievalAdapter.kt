package ai.mobilecore.gallery.search

import ai.mobilecore.g2d.OxfordPetsDataset
import ai.mobilecore.g2d.OxfordPetsRunScale
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil

data class OxfordPetsRetrievalQuery(
    val classIndex: Int,
    val text: String,
    val relevantMediaIds: Set<String>,
)

data class OxfordPetsRetrievalPlan(
    val scale: OxfordPetsRunScale,
    val photos: List<GalleryPhoto>,
    val queries: List<OxfordPetsRetrievalQuery>,
    val selectionDigest: String,
    internal val filesByMediaId: Map<String, File>,
) {
    val selection: GalleryPhotoSelection.GrantedContentUris =
        GalleryPhotoSelection.GrantedContentUris(photos.mapTo(linkedSetOf()) { it.contentUri })

    val discovery = GalleryPhotoDiscovery { photos }

    val mediaReader = GalleryMediaReader { photo ->
        val file = filesByMediaId[photo.mediaId]
            ?: throw GallerySearchException(
                GallerySearchFailure(
                    GallerySearchFailureCode.MEDIA_NOT_FOUND,
                    "An Oxford-Pets benchmark image is missing from the closed protocol.",
                    retryable = false,
                ),
            )
        file.inputStream().buffered()
    }
}

data class OxfordPetsRetrievalMetrics(
    val queryCount: Int,
    val recallAt1: Double,
    val recallAt5: Double,
    /** Reciprocal rank over at most the first 100 returned candidates. */
    val meanReciprocalRankAt100: Double,
)

data class OxfordPetsRetrievalQualityReport(
    val passed: Boolean,
    val failureCodes: List<String>,
    val randomRecallAt1Baseline: Double,
    val requiredRecallAt1Exclusive: Double,
    val top1DistinctCount: Int,
    val requiredTop1DistinctCount: Int,
    val top1MaxShare: Double,
    val allowedTop1MaxShare: Double,
)

/**
 * Small sanity gate for device evidence. It is intentionally not a paper-quality acceptance
 * threshold: it rejects random-level retrieval and obvious fixed-output/mode-collapse runs.
 */
object OxfordPetsRetrievalQualityGate {
    const val RANDOM_BASELINE_MULTIPLIER = 2.0
    const val MIN_TOP1_DISTINCT_RATIO = 0.25
    const val MAX_TOP1_SHARE = 0.25

    fun evaluate(
        plan: OxfordPetsRetrievalPlan,
        rankedMediaIds: Map<Int, List<String>>,
        metrics: OxfordPetsRetrievalMetrics,
    ): OxfordPetsRetrievalQualityReport {
        require(rankedMediaIds.keys == plan.queries.map { it.classIndex }.toSet())
        val top1 = plan.queries.mapNotNull { query ->
            rankedMediaIds.getValue(query.classIndex).firstOrNull()
        }
        val randomBaseline = plan.queries
            .map { it.relevantMediaIds.size.toDouble() / plan.photos.size.toDouble() }
            .average()
        val requiredRecall = randomBaseline * RANDOM_BASELINE_MULTIPLIER
        val requiredDistinct = ceil(plan.queries.size * MIN_TOP1_DISTINCT_RATIO)
            .toInt()
            .coerceAtLeast(2)
        val distinctCount = top1.distinct().size
        val maxShare = top1.groupingBy { it }.eachCount().maxOfOrNull { it.value }
            ?.toDouble()
            ?.div(plan.queries.size)
            ?: 1.0
        val failures = buildList {
            if (top1.size != plan.queries.size) add("empty_ranking")
            if (metrics.recallAt1 <= requiredRecall) add("recall_at_1_not_above_random_gate")
            if (distinctCount < requiredDistinct) add("top1_diversity_too_low")
            if (maxShare > MAX_TOP1_SHARE) add("top1_mode_collapse")
        }
        return OxfordPetsRetrievalQualityReport(
            passed = failures.isEmpty(),
            failureCodes = failures,
            randomRecallAt1Baseline = randomBaseline,
            requiredRecallAt1Exclusive = requiredRecall,
            top1DistinctCount = distinctCount,
            requiredTop1DistinctCount = requiredDistinct,
            top1MaxShare = maxShare,
            allowedTop1MaxShare = MAX_TOP1_SHARE,
        )
    }
}

/** Reuses the exact official-order 37/370 selection; it never invents a random split. */
object OxfordPetsRetrievalAdapter {
    const val MRR_CUTOFF = 100

    fun create(
        dataset: OxfordPetsDataset,
        scale: OxfordPetsRunScale,
    ): OxfordPetsRetrievalPlan {
        require(scale == OxfordPetsRunScale.SMOKE || scale == OxfordPetsRunScale.PILOT) {
            "Gallery retrieval iteration is intentionally gated to Oxford-Pets 37/370."
        }
        val samples = dataset.samplesFor(scale)
        require(samples.size == scale.expectedSamples)
        require(samples.all { it.imageFile?.isFile == true }) {
            "Oxford-Pets retrieval requires the controlled local image root."
        }
        val photos = samples.map { sample ->
            val file = requireNotNull(sample.imageFile)
            GalleryPhoto(
                mediaId = "oxford-pets:${sample.imageId}",
                contentUri = "content://ai.mobilecore.benchmark/oxford-pets/${sample.imageId}",
                displayName = sample.imageId,
                modifiedAtMs = file.lastModified().coerceAtLeast(0L),
                sizeBytes = file.length().coerceAtLeast(0L),
                mimeType = "image/jpeg",
            )
        }
        val mediaIdsByClass = samples.groupBy { it.classIndex }.mapValues { (_, classSamples) ->
            classSamples.mapTo(linkedSetOf()) { "oxford-pets:${it.imageId}" }
        }
        val queries = OxfordPetsDataset.CLASS_NAMES.mapIndexed { classIndex, className ->
            OxfordPetsRetrievalQuery(
                classIndex = classIndex,
                text = "a photo of a ${className.replace('_', ' ')}",
                relevantMediaIds = requireNotNull(mediaIdsByClass[classIndex]),
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(samples.joinToString("\n") { it.imageId }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return OxfordPetsRetrievalPlan(
            scale = scale,
            photos = photos,
            queries = queries,
            selectionDigest = digest,
            filesByMediaId = photos.indices.associate { index ->
                photos[index].mediaId to requireNotNull(samples[index].imageFile)
            },
        )
    }

    fun evaluate(
        plan: OxfordPetsRetrievalPlan,
        rankedMediaIds: Map<Int, List<String>>,
    ): OxfordPetsRetrievalMetrics {
        require(rankedMediaIds.keys == plan.queries.map { it.classIndex }.toSet()) {
            "Every closed Oxford-Pets text query must have one ranking."
        }
        val reciprocalRanks = plan.queries.map { query ->
            val ranking = requireNotNull(rankedMediaIds[query.classIndex])
            require(ranking.distinct().size == ranking.size)
            require(ranking.all { candidate -> plan.photos.any { it.mediaId == candidate } }) {
                "Retrieval rankings may contain only IDs from the closed Oxford-Pets gallery."
            }
            val firstRelevant = ranking
                .take(MRR_CUTOFF)
                .indexOfFirst(query.relevantMediaIds::contains)
            if (firstRelevant < 0) 0.0 else 1.0 / (firstRelevant + 1)
        }
        val recall1 = plan.queries.indices.count { index -> reciprocalRanks[index] >= 1.0 }
        val recall5 = plan.queries.indices.count { index ->
            val query = plan.queries[index]
            rankedMediaIds.getValue(query.classIndex).take(5).any(query.relevantMediaIds::contains)
        }
        return OxfordPetsRetrievalMetrics(
            queryCount = plan.queries.size,
            recallAt1 = recall1.toDouble() / plan.queries.size,
            recallAt5 = recall5.toDouble() / plan.queries.size,
            meanReciprocalRankAt100 = reciprocalRanks.average(),
        )
    }
}
