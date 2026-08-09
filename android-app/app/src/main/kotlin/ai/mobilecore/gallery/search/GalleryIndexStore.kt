package ai.mobilecore.gallery.search

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

sealed interface GalleryIndexLoadResult {
    data object Missing : GalleryIndexLoadResult
    data class Ready(val snapshot: GalleryVectorIndexSnapshot) : GalleryIndexLoadResult
    data class Invalidated(
        val storedModelDigest: String,
        val expectedModelDigest: String,
    ) : GalleryIndexLoadResult
    data class Corrupt(val failure: GallerySearchFailure) : GalleryIndexLoadResult
}

interface GalleryIndexStore {
    fun load(expectedModelDigest: String): GalleryIndexLoadResult
    @Throws(GallerySearchException::class)
    fun save(snapshot: GalleryVectorIndexSnapshot)
    fun clear()
}

/**
 * Compact app-private index format. It stores embeddings and minimum MediaStore identity metadata,
 * never image bytes, search queries, EXIF or generated captions. The host must put [file] below
 * Context.filesDir/noBackupFilesDir rather than shared storage.
 */
class BinaryGalleryIndexStore(private val file: File) : GalleryIndexStore {
    override fun load(expectedModelDigest: String): GalleryIndexLoadResult {
        require(expectedModelDigest.matches(Regex("[a-f0-9]{64}")))
        if (!file.isFile) return GalleryIndexLoadResult.Missing
        return try {
            val fileBytes = file.length()
            requireField(fileBytes in MIN_FILE_BYTES..MAX_FILE_BYTES, "Invalid gallery index byte size.")
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                requireField(input.readInt() == MAGIC, "Invalid gallery index magic.")
                requireField(input.readInt() == VERSION, "Unsupported gallery index version.")
                val digest = input.readBoundedUtf(64, "model digest")
                requireField(digest.matches(Regex("[a-f0-9]{64}")), "Invalid model digest.")
                if (digest != expectedModelDigest) {
                    return GalleryIndexLoadResult.Invalidated(digest, expectedModelDigest)
                }
                val dimension = input.readInt()
                val updatedAtMs = input.readLong()
                val count = input.readInt()
                requireField(dimension in 1..MAX_DIMENSION, "Invalid embedding dimension.")
                requireField(updatedAtMs >= 0L, "Invalid index timestamp.")
                requireField(count in 0..MAX_ENTRIES, "Invalid gallery index size.")
                validatedLoadMemory(dimension, count, fileBytes)
                val entries = ArrayList<GalleryIndexedPhoto>(count)
                repeat(count) {
                    val photo = GalleryPhoto(
                        mediaId = input.readBoundedUtf(512, "media ID"),
                        contentUri = input.readBoundedUtf(4_096, "content URI"),
                        // Names are deliberately not persisted. Hosts can resolve them for display.
                        displayName = "",
                        modifiedAtMs = input.readLong(),
                        sizeBytes = input.readLong(),
                        mimeType = input.readBoundedUtf(128, "MIME type"),
                    )
                    requireField(photo.contentUri.startsWith("content://"), "Unsafe persisted URI.")
                    val vector = FloatArray(dimension) { input.readFloat() }
                    entries += GalleryIndexedPhoto(photo, NormalizedEmbedding.from(vector))
                }
                requireField(input.read() == -1, "Trailing bytes in gallery index.")
                GalleryIndexLoadResult.Ready(
                    GalleryVectorIndexSnapshot(digest, dimension, updatedAtMs, entries),
                )
            }
        } catch (error: Exception) {
            GalleryIndexLoadResult.Corrupt(
                GallerySearchFailure(
                    GallerySearchFailureCode.INDEX_CORRUPT,
                    "The private gallery index is corrupt and must be rebuilt.",
                    retryable = true,
                ),
            )
        }
    }

    override fun save(snapshot: GalleryVectorIndexSnapshot) {
        var temporary: File? = null
        try {
            validatedVectorBytes(snapshot.dimension, snapshot.entries.size)
            file.parentFile?.mkdirs()
            val outputFile = File(file.parentFile, "${file.name}.tmp")
            temporary = outputFile
            DataOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeUTF(snapshot.modelDigest)
                output.writeInt(snapshot.dimension)
                output.writeLong(snapshot.updatedAtMs)
                output.writeInt(snapshot.entries.size)
                snapshot.entries.forEach { entry ->
                    output.writeUTF(entry.photo.mediaId)
                    output.writeUTF(entry.photo.contentUri)
                    output.writeLong(entry.photo.modifiedAtMs)
                    output.writeLong(entry.photo.sizeBytes)
                    output.writeUTF(entry.photo.mimeType)
                    entry.embedding.copyValues().forEach(output::writeFloat)
                }
                output.flush()
            }
            if (outputFile.length() !in MIN_FILE_BYTES..MAX_FILE_BYTES) {
                throw IOException("Gallery index exceeds the audited file budget.")
            }
            validatedLoadMemory(snapshot.dimension, snapshot.entries.size, outputFile.length())
            FileOutputStream(outputFile, true).use { it.fd.sync() }
            try {
                Files.move(
                    outputFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(outputFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        } catch (error: Exception) {
            temporary?.delete()
            throw GallerySearchException(
                GallerySearchFailure(
                    GallerySearchFailureCode.IO_FAILED,
                    "Unable to atomically persist the private gallery index.",
                    retryable = true,
                ),
                error,
            )
        }
    }

    override fun clear() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    private fun DataInputStream.readBoundedUtf(maxLength: Int, label: String): String =
        readUTF().also { requireField(it.length <= maxLength, "$label is too long.") }

    private fun requireField(condition: Boolean, message: String) {
        if (!condition) throw EOFException(message)
    }

    /** Validated before ArrayList/FloatArray allocation or any output file creation. */
    private fun validatedVectorBytes(dimension: Int, count: Int): Long {
        requireField(dimension in 1..MAX_DIMENSION, "Invalid embedding dimension.")
        requireField(count in 0..MAX_ENTRIES, "Invalid gallery index size.")
        val scalarCount = try {
            Math.multiplyExact(dimension.toLong(), count.toLong())
        } catch (error: ArithmeticException) {
            throw EOFException("Gallery vector count overflow.").also { it.initCause(error) }
        }
        val vectorBytes = try {
            Math.multiplyExact(scalarCount, Float.SIZE_BYTES.toLong())
        } catch (error: ArithmeticException) {
            throw EOFException("Gallery vector byte count overflow.").also { it.initCause(error) }
        }
        requireField(vectorBytes <= MAX_VECTOR_BYTES, "Gallery vectors exceed the memory budget.")
        return vectorBytes
    }

    private fun validatedLoadMemory(dimension: Int, count: Int, fileBytes: Long) {
        val vectorBytes = validatedVectorBytes(dimension, count)
        val payloadBytes = fileBytes - MIN_FILE_BYTES
        requireField(vectorBytes <= payloadBytes, "Declared vectors exceed the gallery index file size.")
        // Treat every non-vector on-disk byte as two heap bytes (UTF-16 worst-case allowance),
        // then add a conservative per-entry object/reference allowance before allocating anything.
        val estimatedHeapBytes = try {
            Math.addExact(
                vectorBytes,
                Math.addExact(
                    Math.multiplyExact(payloadBytes - vectorBytes, 2L),
                    Math.multiplyExact(count.toLong(), ESTIMATED_ENTRY_OVERHEAD_BYTES),
                ),
            )
        } catch (error: ArithmeticException) {
            throw EOFException("Gallery index heap estimate overflow.").also { it.initCause(error) }
        }
        requireField(estimatedHeapBytes <= MAX_INDEX_HEAP_BYTES, "Gallery index exceeds the heap budget.")
    }

    companion object {
        private const val MAGIC = 0x4D434749 // MCGI
        private const val VERSION = 1
        // OpenAI/OpenCLIP embeddings are normally 512-1,280 dimensions. 4,096 leaves headroom
        // without permitting a hostile header to request arbitrary FloatArray allocations.
        private const val MAX_DIMENSION = 4_096
        // The product's audited MediaStore selection is 20k; larger galleries need a paged index.
        private const val MAX_ENTRIES = 20_000
        private const val MIN_FILE_BYTES = 90L
        private const val MAX_VECTOR_BYTES = 64L * 1024L * 1024L
        private const val MAX_INDEX_HEAP_BYTES = 96L * 1024L * 1024L
        private const val ESTIMATED_ENTRY_OVERHEAD_BYTES = 256L
        private const val MAX_FILE_BYTES = 128L * 1024L * 1024L
    }
}
