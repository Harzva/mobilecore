package ai.mobilecore.gallery.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

class GalleryIndexStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `round trip preserves vectors but deliberately omits display names and queries`() {
        val file = temporary.newFolder("private").resolve("gallery.mcgi")
        val store = BinaryGalleryIndexStore(file)
        val snapshot = GalleryVectorIndexSnapshot(
            modelDigest = "b".repeat(64),
            dimension = 2,
            updatedAtMs = 99L,
            entries = listOf(
                GalleryIndexedPhoto(
                    GalleryPhoto(
                        "media:7",
                        "content://media/external/images/7",
                        "private-family-name.jpg",
                        10L,
                        20L,
                        "image/jpeg",
                    ),
                    NormalizedEmbedding.from(floatArrayOf(3f, 4f)),
                ),
            ),
        )

        store.save(snapshot)
        val loaded = store.load("b".repeat(64)) as GalleryIndexLoadResult.Ready

        assertEquals(1, loaded.snapshot.entries.size)
        assertEquals("", loaded.snapshot.entries.single().photo.displayName)
        assertEquals(1.0, loaded.snapshot.entries.single().embedding.cosine(snapshot.entries.single().embedding), 1e-6)
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("private-family-name"))
        assertFalse(bytes.contains("海边穿红衣服的人"))
    }

    @Test
    fun `model digest change invalidates rather than silently reusing vectors`() {
        val store = BinaryGalleryIndexStore(temporary.newFile("gallery.mcgi"))
        store.save(
            GalleryVectorIndexSnapshot(
                "c".repeat(64),
                2,
                1L,
                emptyList(),
            ),
        )

        val loaded = store.load("d".repeat(64))

        assertTrue(loaded is GalleryIndexLoadResult.Invalidated)
    }

    @Test
    fun `hostile count and dimension are rejected before vector allocation`() {
        val file = temporary.newFile("overflow.mcgi")
        writeHeader(file, dimension = Int.MAX_VALUE, count = Int.MAX_VALUE)

        val loaded = BinaryGalleryIndexStore(file).load("e".repeat(64))

        assertTrue(loaded is GalleryIndexLoadResult.Corrupt)
    }

    @Test
    fun `declared vector memory over audited budget is rejected before allocation`() {
        val file = temporary.newFile("memory-budget.mcgi")
        writeHeader(file, dimension = 4_096, count = 20_000)

        val loaded = BinaryGalleryIndexStore(file).load("e".repeat(64))

        assertTrue(loaded is GalleryIndexLoadResult.Corrupt)
    }

    @Test
    fun `declared vectors must fit inside physical file before allocation`() {
        val file = temporary.newFile("truncated-vectors.mcgi")
        writeHeader(file, dimension = 512, count = 20_000)

        val loaded = BinaryGalleryIndexStore(file).load("e".repeat(64))

        assertTrue(loaded is GalleryIndexLoadResult.Corrupt)
    }

    @Test
    fun `oversized physical index is rejected before parsing`() {
        val file = temporary.newFile("oversized.mcgi")
        RandomAccessFile(file, "rw").use { it.setLength(128L * 1024L * 1024L + 1L) }

        val loaded = BinaryGalleryIndexStore(file).load("e".repeat(64))

        assertTrue(loaded is GalleryIndexLoadResult.Corrupt)
    }

    @Test
    fun `file derived metadata heap estimate is rejected before entry allocation`() {
        val file = temporary.newFile("metadata-heap.mcgi")
        writeHeader(file, dimension = 512, count = 1)
        RandomAccessFile(file, "rw").use { it.setLength(80L * 1024L * 1024L) }

        val loaded = BinaryGalleryIndexStore(file).load("e".repeat(64))

        assertTrue(loaded is GalleryIndexLoadResult.Corrupt)
    }

    private fun writeHeader(file: File, dimension: Int, count: Int) {
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.writeInt(0x4D434749)
            output.writeInt(1)
            output.writeUTF("e".repeat(64))
            output.writeInt(dimension)
            output.writeLong(1L)
            output.writeInt(count)
        }
    }
}
