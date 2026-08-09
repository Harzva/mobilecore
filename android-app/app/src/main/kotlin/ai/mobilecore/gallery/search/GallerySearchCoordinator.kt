package ai.mobilecore.gallery.search

data class GalleryIndexProgress(
    val processedCount: Int,
    val totalCount: Int,
    val encodedCount: Int,
    val reusedCount: Int,
    val skippedCount: Int = 0,
    /** Opaque local identifier only; no URI or filename is exposed. */
    val currentMediaId: String? = null,
)

data class GalleryIndexStats(
    val discoveredCount: Int,
    val encodedCount: Int,
    val reusedCount: Int,
    val removedCount: Int,
    val invalidatedPreviousModel: Boolean,
    val skippedCount: Int = 0,
)

sealed interface GalleryIndexOutcome {
    data class Completed(
        val snapshot: GalleryVectorIndexSnapshot,
        val stats: GalleryIndexStats,
    ) : GalleryIndexOutcome

    /** Partial work is checkpointed and can be resumed without re-encoding completed photos. */
    data class Cancelled(
        val snapshot: GalleryVectorIndexSnapshot,
        val stats: GalleryIndexStats,
        val failure: GallerySearchFailure,
    ) : GalleryIndexOutcome

    data class Failed(
        val failure: GallerySearchFailure,
        val partialSnapshot: GalleryVectorIndexSnapshot? = null,
    ) : GalleryIndexOutcome
}

sealed interface GalleryQueryOutcome {
    data class Results(val hits: List<GallerySearchHit>) : GalleryQueryOutcome
    data class Blocked(val failure: GallerySearchFailure) : GalleryQueryOutcome
}

/**
 * Synchronous orchestration boundary intended to run on a host-owned worker executor.
 * It never logs or persists image bytes, filenames or queries.
 */
class GallerySearchCoordinator(
    private val discovery: GalleryPhotoDiscovery,
    private val mediaReader: GalleryMediaReader,
    private val runtime: GalleryClipRuntime,
    private val store: GalleryIndexStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val checkpointEvery: Int = 25,
) {
    init {
        require(checkpointEvery in 1..1_000)
    }

    /** Explicit recovery action for a typed INDEX_CORRUPT state. */
    fun clearIndex() = store.clear()

    fun buildOrUpdateIndex(
        selection: GalleryPhotoSelection,
        cancellation: GalleryCancellation = GalleryCancellation.NONE,
        onProgress: (GalleryIndexProgress) -> Unit = {},
    ): GalleryIndexOutcome {
        val photos = try {
            discovery.discover(selection)
        } catch (error: GallerySearchException) {
            return GalleryIndexOutcome.Failed(error.failure)
        } catch (error: Exception) {
            return GalleryIndexOutcome.Failed(
                GallerySearchFailure(
                    GallerySearchFailureCode.ACCESS_DENIED,
                    "Unable to enumerate the locally granted photo set.",
                    retryable = true,
                ),
            )
        }
        if (photos.map { it.mediaId }.distinct().size != photos.size) {
            return GalleryIndexOutcome.Failed(
                GallerySearchFailure(
                    GallerySearchFailureCode.INDEX_CORRUPT,
                    "Photo discovery returned duplicate media identifiers.",
                    retryable = true,
                ),
            )
        }

        val load = store.load(runtime.descriptor.modelDigest)
        val invalidated = load is GalleryIndexLoadResult.Invalidated
        val index = when (load) {
            is GalleryIndexLoadResult.Ready -> {
                if (load.snapshot.dimension != runtime.descriptor.embeddingDimension) {
                    return GalleryIndexOutcome.Failed(
                        GallerySearchFailure(
                            GallerySearchFailureCode.EMBEDDING_DIMENSION_MISMATCH,
                            "The persisted index dimension does not match the active CLIP runtime.",
                            retryable = true,
                        ),
                    )
                }
                GalleryVectorIndex.from(load.snapshot)
            }
            is GalleryIndexLoadResult.Corrupt -> return GalleryIndexOutcome.Failed(load.failure)
            is GalleryIndexLoadResult.Invalidated,
            GalleryIndexLoadResult.Missing,
            -> GalleryVectorIndex.empty(runtime.descriptor)
        }

        var encoded = 0
        var reused = 0
        var skipped = 0
        for ((position, photo) in photos.withIndex()) {
            if (cancellation.isCancelled()) {
                return cancelled(index, photos.size, encoded, reused, skipped, invalidated)
            }
            val existing = index.find(photo.mediaId)
            if (existing != null && existing.photo.sameContentAs(photo)) {
                reused += 1
                // Refresh ephemeral display metadata without re-encoding.
                index.upsert(existing.copy(photo = photo))
            } else {
                val embedding = try {
                    runtime.embedImage(photo, mediaReader)
                } catch (error: GallerySearchException) {
                    if (error.failure.code in RECOVERABLE_MEDIA_FAILURES) {
                        index.remove(photo.mediaId)
                        skipped += 1
                        null
                    } else {
                        return failedWithCheckpoint(index, error.failure)
                    }
                } catch (error: Exception) {
                    return failedWithCheckpoint(
                        index,
                        GallerySearchFailure(
                            GallerySearchFailureCode.IMAGE_DECODE_FAILED,
                            "A granted local image could not be encoded.",
                            retryable = true,
                        ),
                    )
                }
                if (embedding == null) {
                    val processed = position + 1
                    onProgress(
                        GalleryIndexProgress(
                            processed,
                            photos.size,
                            encoded,
                            reused,
                            skipped,
                            photo.mediaId,
                        ),
                    )
                    if (processed % checkpointEvery == 0) {
                        try {
                            store.save(index.snapshot(nowMs()))
                        } catch (error: GallerySearchException) {
                            return GalleryIndexOutcome.Failed(error.failure, index.snapshot(nowMs()))
                        }
                    }
                    continue
                }
                if (embedding.dimension != runtime.descriptor.embeddingDimension) {
                    return failedWithCheckpoint(
                        index,
                        GallerySearchFailure(
                            GallerySearchFailureCode.EMBEDDING_DIMENSION_MISMATCH,
                            "The CLIP image encoder returned an unexpected dimension.",
                            retryable = false,
                        ),
                    )
                }
                index.upsert(GalleryIndexedPhoto(photo, embedding))
                encoded += 1
            }
            val processed = position + 1
            onProgress(
                GalleryIndexProgress(processed, photos.size, encoded, reused, skipped, photo.mediaId),
            )
            if (processed % checkpointEvery == 0) {
                try {
                    store.save(index.snapshot(nowMs()))
                } catch (error: GallerySearchException) {
                    return GalleryIndexOutcome.Failed(error.failure, index.snapshot(nowMs()))
                }
            }
        }

        if (cancellation.isCancelled()) {
            return cancelled(index, photos.size, encoded, reused, skipped, invalidated)
        }
        val removed = index.retainOnly(photos.mapTo(linkedSetOf()) { it.mediaId })
        val snapshot = index.snapshot(nowMs())
        return try {
            store.save(snapshot)
            GalleryIndexOutcome.Completed(
                snapshot,
                GalleryIndexStats(photos.size, encoded, reused, removed, invalidated, skipped),
            )
        } catch (error: GallerySearchException) {
            GalleryIndexOutcome.Failed(error.failure, snapshot)
        }
    }

    fun search(query: String, topK: Int): GalleryQueryOutcome {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty() || normalizedQuery.length > MAX_QUERY_LENGTH) {
            return GalleryQueryOutcome.Blocked(
                GallerySearchFailure(
                    GallerySearchFailureCode.TEXT_ENCODER_UNAVAILABLE,
                    "Search text must contain 1 to $MAX_QUERY_LENGTH characters.",
                    retryable = false,
                ),
            )
        }
        if (topK !in 1..MAX_TOP_K) {
            return GalleryQueryOutcome.Blocked(
                GallerySearchFailure(
                    GallerySearchFailureCode.INDEX_CORRUPT,
                    "Top-K must be in 1..$MAX_TOP_K.",
                    retryable = false,
                ),
            )
        }
        val snapshot = when (val loaded = store.load(runtime.descriptor.modelDigest)) {
            GalleryIndexLoadResult.Missing -> return GalleryQueryOutcome.Blocked(
                GallerySearchFailure(
                    GallerySearchFailureCode.INDEX_MISSING,
                    "Build the private photo index before searching.",
                    retryable = true,
                ),
            )
            is GalleryIndexLoadResult.Invalidated -> return GalleryQueryOutcome.Blocked(
                GallerySearchFailure(
                    GallerySearchFailureCode.MODEL_DIGEST_MISMATCH,
                    "The CLIP model changed; rebuild the local photo index.",
                    retryable = true,
                ),
            )
            is GalleryIndexLoadResult.Corrupt -> return GalleryQueryOutcome.Blocked(loaded.failure)
            is GalleryIndexLoadResult.Ready -> loaded.snapshot
        }
        return try {
            val queryEmbedding = runtime.embedText(normalizedQuery)
            GalleryQueryOutcome.Results(GalleryVectorIndex.from(snapshot).search(queryEmbedding, topK))
        } catch (error: GallerySearchException) {
            GalleryQueryOutcome.Blocked(error.failure)
        } catch (error: Exception) {
            GalleryQueryOutcome.Blocked(
                GallerySearchFailure(
                    GallerySearchFailureCode.TEXT_ENCODER_UNAVAILABLE,
                    "The local CLIP text encoder could not process this query.",
                    retryable = true,
                ),
            )
        }
    }

    private fun cancelled(
        index: GalleryVectorIndex,
        discovered: Int,
        encoded: Int,
        reused: Int,
        skipped: Int,
        invalidated: Boolean,
    ): GalleryIndexOutcome {
        val snapshot = index.snapshot(nowMs())
        return try {
            store.save(snapshot)
            GalleryIndexOutcome.Cancelled(
                snapshot,
                GalleryIndexStats(discovered, encoded, reused, 0, invalidated, skipped),
                GallerySearchFailure(
                    GallerySearchFailureCode.CANCELLED,
                    "Photo indexing was cancelled; completed embeddings were checkpointed.",
                    retryable = true,
                ),
            )
        } catch (error: GallerySearchException) {
            GalleryIndexOutcome.Failed(error.failure, snapshot)
        }
    }

    private fun failedWithCheckpoint(
        index: GalleryVectorIndex,
        failure: GallerySearchFailure,
    ): GalleryIndexOutcome {
        val snapshot = index.snapshot(nowMs())
        return try {
            store.save(snapshot)
            GalleryIndexOutcome.Failed(failure, snapshot)
        } catch (storeError: GallerySearchException) {
            GalleryIndexOutcome.Failed(storeError.failure, snapshot)
        }
    }

    companion object {
        private const val MAX_QUERY_LENGTH = 512
        private const val MAX_TOP_K = 100
        private val RECOVERABLE_MEDIA_FAILURES = setOf(
            GallerySearchFailureCode.IMAGE_DECODE_FAILED,
            GallerySearchFailureCode.MEDIA_NOT_FOUND,
            GallerySearchFailureCode.UNSUPPORTED_URI,
        )
    }
}
