package ai.mobilecore.playground

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class AtomicGgufImportTest {
    private val directory = Files.createTempDirectory("mobilecore-gguf-import").toFile()

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `ordinary import atomically installs valid GGUF without leaving temp`() {
        val payload = "GGUFpayload".encodeToByteArray()
        val destination = File(directory, "user-model.gguf")
        val temporary = File(directory, ".user-model.gguf.test.import")

        AtomicGgufImport.copy(ByteArrayInputStream(payload), temporary, destination)

        assertArrayEquals(payload, destination.readBytes())
        assertFalse(temporary.exists())
    }

    @Test
    fun `ordinary import never overwrites existing destination`() {
        val destination = File(directory, "user-model.gguf").apply { writeText("existing") }
        val temporary = File(directory, ".user-model.gguf.test.import")
        var failed = false

        try {
            AtomicGgufImport.copy(
                ByteArrayInputStream("GGUFreplacement".encodeToByteArray()),
                temporary,
                destination,
            )
        } catch (_: IllegalArgumentException) {
            failed = true
        }

        assertTrue(failed)
        assertArrayEquals("existing".encodeToByteArray(), destination.readBytes())
        assertFalse(temporary.exists())
    }

    @Test
    fun `managed catalog filename is case insensitive`() {
        val catalog = PlaygroundCatalogParser.parse(
            File("src/main/assets/mobile-model-playground/catalog-v1.json").readText(),
        )
        val managed = catalog.entries.first { it.distribution.downloadable }.requiredArtifactNames.first()

        assertTrue(PlaygroundManagedArtifactPolicy.isManagedFileName(catalog, managed.uppercase()))
        assertFalse(PlaygroundManagedArtifactPolicy.isManagedFileName(catalog, "user-model.gguf"))
    }
}
