package ai.mobilecore.gallery.search

import android.content.ContentResolver
import java.io.Closeable
import java.io.File

sealed interface AndroidGallerySearchHostResult {
    data class Ready(val host: AndroidGallerySearchHost) : AndroidGallerySearchHostResult
    data class Blocked(val failure: GallerySearchFailure) : AndroidGallerySearchHostResult
}

sealed interface GalleryPersistedIndexStatus {
    data object Missing : GalleryPersistedIndexStatus
    data class Ready(
        val indexedCount: Int,
        val updatedAtMs: Long,
    ) : GalleryPersistedIndexStatus
    data object Invalidated : GalleryPersistedIndexStatus
    data object Corrupt : GalleryPersistedIndexStatus
}

/**
 * Direct host entry point. Pass an app-private [indexDirectory]. All methods are synchronous and
 * must be called from a worker executor; UI updates should be posted back to the main thread.
 */
class AndroidGallerySearchHost private constructor(
    private val runtime: OnnxClipGalleryRuntime,
    private val coordinator: GallerySearchCoordinator,
    private val store: GalleryIndexStore,
) : Closeable {
    val descriptor: GalleryClipRuntimeDescriptor get() = runtime.descriptor

    fun buildOrUpdateIndex(
        selection: GalleryPhotoSelection,
        cancellation: GalleryCancellation = GalleryCancellation.NONE,
        onProgress: (GalleryIndexProgress) -> Unit = {},
    ): GalleryIndexOutcome = coordinator.buildOrUpdateIndex(selection, cancellation, onProgress)

    fun search(query: String, topK: Int = 20): GalleryQueryOutcome =
        coordinator.search(query, topK)

    fun clearIndex() = coordinator.clearIndex()

    fun persistedIndexStatus(): GalleryPersistedIndexStatus =
        when (val loaded = store.load(runtime.descriptor.modelDigest)) {
            GalleryIndexLoadResult.Missing -> GalleryPersistedIndexStatus.Missing
            is GalleryIndexLoadResult.Ready -> GalleryPersistedIndexStatus.Ready(
                indexedCount = loaded.snapshot.entries.size,
                updatedAtMs = loaded.snapshot.updatedAtMs,
            )
            is GalleryIndexLoadResult.Invalidated -> GalleryPersistedIndexStatus.Invalidated
            is GalleryIndexLoadResult.Corrupt -> GalleryPersistedIndexStatus.Corrupt
        }

    override fun close() = runtime.close()

    companion object {
        fun hasPersistedIndex(indexDirectory: File): Boolean =
            File(indexDirectory, INDEX_FILE_NAME).isFile

        fun clearPersistedIndex(indexDirectory: File) {
            BinaryGalleryIndexStore(File(indexDirectory, INDEX_FILE_NAME)).clear()
        }

        fun open(
            contentResolver: ContentResolver,
            modelsDirectory: File,
            tokenizerDirectory: File,
            indexDirectory: File,
        ): AndroidGallerySearchHostResult {
            val artifacts = GalleryClipArtifactSet.discover(modelsDirectory, tokenizerDirectory)
            return when (val opened = OnnxClipGalleryRuntime.open(artifacts)) {
                is GalleryRuntimeOpenResult.Blocked -> AndroidGallerySearchHostResult.Blocked(opened.failure)
                is GalleryRuntimeOpenResult.Ready -> {
                    val store = BinaryGalleryIndexStore(File(indexDirectory, INDEX_FILE_NAME))
                    val coordinator = GallerySearchCoordinator(
                        discovery = AndroidGalleryPhotoDiscovery(contentResolver),
                        mediaReader = ContentResolverGalleryMediaReader(contentResolver),
                        runtime = opened.runtime,
                        store = store,
                    )
                    AndroidGallerySearchHostResult.Ready(
                        AndroidGallerySearchHost(opened.runtime, coordinator, store),
                    )
                }
            }
        }

        private const val INDEX_FILE_NAME = "clip-gallery-v1.mcgi"
    }
}
