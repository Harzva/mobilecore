package ai.mobilecore.gallery.search

import java.io.Closeable
import java.io.InputStream
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Stable failure codes which the Android host can map to explicit UI states. */
enum class GallerySearchFailureCode {
    ACCESS_DENIED,
    UNSUPPORTED_URI,
    MEDIA_NOT_FOUND,
    IMAGE_DECODE_FAILED,
    IMAGE_ENCODER_UNAVAILABLE,
    TEXT_ENCODER_UNAVAILABLE,
    TEXT_TOKENIZER_UNAVAILABLE,
    MODEL_ABI_MISMATCH,
    MODEL_LOAD_FAILED,
    MODEL_DIGEST_MISMATCH,
    INVALID_EMBEDDING,
    EMBEDDING_DIMENSION_MISMATCH,
    INDEX_MISSING,
    INDEX_CORRUPT,
    IO_FAILED,
    CANCELLED,
}

data class GallerySearchFailure(
    val code: GallerySearchFailureCode,
    val message: String,
    val retryable: Boolean,
)

class GallerySearchException(
    val failure: GallerySearchFailure,
    cause: Throwable? = null,
) : Exception(failure.message, cause)

data class GalleryPhoto(
    val mediaId: String,
    val contentUri: String,
    val displayName: String,
    val modifiedAtMs: Long,
    val sizeBytes: Long,
    val mimeType: String = "image/*",
) {
    init {
        require(mediaId.isNotBlank() && mediaId.length <= 512)
        require(contentUri.isNotBlank() && contentUri.length <= 4_096)
        require(displayName.length <= 1_024)
        require(modifiedAtMs >= 0L)
        require(sizeBytes >= 0L)
        require(mimeType.startsWith("image/"))
    }

    internal fun sameContentAs(other: GalleryPhoto): Boolean =
        mediaId == other.mediaId &&
            contentUri == other.contentUri &&
            modifiedAtMs == other.modifiedAtMs &&
            sizeBytes == other.sizeBytes
}

sealed interface GalleryPhotoSelection {
    /** Enumerates MediaStore.Images after the host has obtained the platform permission. */
    data class MediaStoreImages(
        val maximumCount: Int = 20_000,
        val modifiedAfterMs: Long? = null,
    ) : GalleryPhotoSelection {
        init {
            require(maximumCount in 1..100_000)
            require(modifiedAfterMs == null || modifiedAfterMs >= 0L)
        }
    }

    /** Only URIs explicitly granted by Photo Picker / SAF are opened. */
    data class GrantedContentUris(val uris: Set<String>) : GalleryPhotoSelection {
        init {
            require(uris.isNotEmpty() && uris.size <= 20_000)
            require(uris.all { it.startsWith("content://") && it.length <= 4_096 }) {
                "Gallery grants must be bounded content:// URIs."
            }
        }
    }
}

fun interface GalleryPhotoDiscovery {
    @Throws(GallerySearchException::class)
    fun discover(selection: GalleryPhotoSelection): List<GalleryPhoto>
}

/** The caller owns and closes the returned stream. Implementations must reject network/file URIs. */
fun interface GalleryMediaReader {
    @Throws(GallerySearchException::class)
    fun open(photo: GalleryPhoto): InputStream
}

fun interface GalleryCancellation {
    fun isCancelled(): Boolean

    companion object {
        val NONE = GalleryCancellation { false }
    }
}

class GalleryCancellationToken : GalleryCancellation {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    override fun isCancelled(): Boolean = cancelled.get()
}

/** Immutable, finite, unit-length vector. Normalizing once makes search a dot product. */
class NormalizedEmbedding private constructor(private val values: FloatArray) {
    val dimension: Int get() = values.size

    fun cosine(other: NormalizedEmbedding): Double {
        require(dimension == other.dimension) {
            "Embedding dimensions differ: $dimension vs ${other.dimension}."
        }
        var dot = 0.0
        for (index in values.indices) dot += values[index] * other.values[index]
        return dot.coerceIn(-1.0, 1.0)
    }

    fun copyValues(): FloatArray = values.copyOf()

    companion object {
        fun from(raw: FloatArray): NormalizedEmbedding {
            if (raw.isEmpty() || raw.any { !it.isFinite() }) {
                throw GallerySearchException(
                    GallerySearchFailure(
                        GallerySearchFailureCode.INVALID_EMBEDDING,
                        "CLIP returned an empty or non-finite embedding.",
                        retryable = false,
                    ),
                )
            }
            var squaredNorm = 0.0
            raw.forEach { squaredNorm += it.toDouble() * it.toDouble() }
            val norm = sqrt(squaredNorm)
            if (!norm.isFinite() || norm < 1e-12) {
                throw GallerySearchException(
                    GallerySearchFailure(
                        GallerySearchFailureCode.INVALID_EMBEDDING,
                        "CLIP returned a zero-norm embedding.",
                        retryable = false,
                    ),
                )
            }
            return NormalizedEmbedding(FloatArray(raw.size) { raw[it] / norm.toFloat() })
        }
    }
}

data class GalleryClipRuntimeDescriptor(
    val modelId: String,
    /** Digest covers image encoder, text encoder and tokenizer artifacts. */
    val modelDigest: String,
    val embeddingDimension: Int,
    val imageEncoderName: String,
    val textEncoderName: String,
    val tokenizerName: String,
    /** True only when the complete artifact-set digest matches an audited identity. */
    val identityVerified: Boolean = false,
) {
    init {
        require(modelId.isNotBlank())
        require(modelDigest.matches(Regex("[a-f0-9]{64}")))
        require(embeddingDimension > 0)
    }
}

interface GalleryClipRuntime : Closeable {
    val descriptor: GalleryClipRuntimeDescriptor

    @Throws(GallerySearchException::class)
    fun embedImage(photo: GalleryPhoto, mediaReader: GalleryMediaReader): NormalizedEmbedding

    @Throws(GallerySearchException::class)
    fun embedText(query: String): NormalizedEmbedding

    override fun close() = Unit
}

data class GalleryIndexedPhoto(
    val photo: GalleryPhoto,
    val embedding: NormalizedEmbedding,
)

data class GalleryVectorIndexSnapshot(
    val modelDigest: String,
    val dimension: Int,
    val updatedAtMs: Long,
    val entries: List<GalleryIndexedPhoto>,
) {
    init {
        require(modelDigest.matches(Regex("[a-f0-9]{64}")))
        require(dimension > 0)
        require(updatedAtMs >= 0L)
        require(entries.map { it.photo.mediaId }.distinct().size == entries.size)
        require(entries.all { it.embedding.dimension == dimension })
    }
}

data class GallerySearchHit(
    val photo: GalleryPhoto,
    val similarity: Double,
)

/** Exact cosine index. It is intentionally simple and deterministic for the initial local scale. */
class GalleryVectorIndex private constructor(
    val modelDigest: String,
    val dimension: Int,
    private val entries: LinkedHashMap<String, GalleryIndexedPhoto>,
) {
    val size: Int get() = entries.size

    fun find(mediaId: String): GalleryIndexedPhoto? = entries[mediaId]

    fun upsert(entry: GalleryIndexedPhoto) {
        require(entry.embedding.dimension == dimension) {
            "Embedding dimension ${entry.embedding.dimension} does not match index dimension $dimension."
        }
        entries[entry.photo.mediaId] = entry
    }

    fun remove(mediaId: String): Boolean = entries.remove(mediaId) != null

    fun retainOnly(mediaIds: Set<String>): Int {
        val before = entries.size
        entries.keys.retainAll(mediaIds)
        return before - entries.size
    }

    fun search(query: NormalizedEmbedding, topK: Int): List<GallerySearchHit> {
        if (query.dimension != dimension) {
            throw GallerySearchException(
                GallerySearchFailure(
                    GallerySearchFailureCode.EMBEDDING_DIMENSION_MISMATCH,
                    "Text and image encoder dimensions do not match the persisted index.",
                    retryable = false,
                ),
            )
        }
        require(topK in 1..1_000)
        val queue = PriorityQueue<GallerySearchHit>(compareBy { it.similarity })
        entries.values.forEach { entry ->
            val hit = GallerySearchHit(entry.photo, query.cosine(entry.embedding))
            if (queue.size < topK) {
                queue += hit
            } else if (hit.similarity > requireNotNull(queue.peek()).similarity) {
                queue.poll()
                queue += hit
            }
        }
        return buildList(queue.size) {
            while (queue.isNotEmpty()) add(requireNotNull(queue.poll()))
        }.asReversed()
    }

    fun snapshot(updatedAtMs: Long): GalleryVectorIndexSnapshot = GalleryVectorIndexSnapshot(
        modelDigest = modelDigest,
        dimension = dimension,
        updatedAtMs = updatedAtMs,
        entries = entries.values.toList(),
    )

    companion object {
        fun empty(descriptor: GalleryClipRuntimeDescriptor): GalleryVectorIndex = GalleryVectorIndex(
            descriptor.modelDigest,
            descriptor.embeddingDimension,
            linkedMapOf(),
        )

        fun from(snapshot: GalleryVectorIndexSnapshot): GalleryVectorIndex = GalleryVectorIndex(
            snapshot.modelDigest,
            snapshot.dimension,
            LinkedHashMap(snapshot.entries.associateBy { it.photo.mediaId }),
        )
    }
}
