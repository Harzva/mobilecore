package ai.mobilecore.g2d

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OxfordPetsDatasetTest {
    @Test
    fun `split parser keeps official labels species and image paths`() {
        val split = temporarySplit(
            "Abyssinian_201 1 1 1\n" +
                "american_bulldog_10 2 2 1\n",
        )
        val images = Files.createTempDirectory("oxford-pets-images-").toFile()

        val dataset = OxfordPetsDataset.openSplit(split, imagesRoot = images)

        assertEquals(2, dataset.sampleCount)
        assertEquals("Abyssinian", dataset.samples[0].className)
        assertEquals(OxfordPetsSpecies.CAT, dataset.samples[0].species)
        assertEquals(1, dataset.samples[1].classIndex)
        assertEquals(OxfordPetsSpecies.DOG, dataset.samples[1].species)
        assertEquals(File(images, "american_bulldog_10.jpg"), dataset.samples[1].imageFile)
    }

    @Test
    fun `stratified selection preserves split order inside every class`() {
        val split = temporarySplit(
            "Abyssinian_1 1 1 1\n" +
                "american_bulldog_1 2 2 1\n" +
                "Abyssinian_2 1 1 1\n" +
                "american_bulldog_2 2 2 1\n",
        )
        val dataset = OxfordPetsDataset.openSplit(split)

        assertEquals(
            listOf("Abyssinian_1", "american_bulldog_1"),
            dataset.stratifiedSamples(1).map(OxfordPetsSample::imageId),
        )
    }

    @Test
    fun `invalid class mapping and missing required image are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            OxfordPetsDataset.openSplit(temporarySplit("american_bulldog_1 1 2 1\n"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OxfordPetsDataset.openSplit(
                temporarySplit("Abyssinian_1 1 1 1\n"),
                imagesRoot = Files.createTempDirectory("oxford-pets-missing-").toFile(),
                requireImages = true,
            )
        }
    }

    @Test
    fun `official run scales are fixed and do not imply a random resplit`() {
        assertEquals(37, OxfordPetsRunScale.SMOKE.expectedSamples)
        assertEquals(370, OxfordPetsRunScale.PILOT.expectedSamples)
        assertEquals(3_669, OxfordPetsRunScale.FULL.expectedSamples)
        assertEquals(null, OxfordPetsRunScale.FULL.perClass)
    }

    private fun temporarySplit(contents: String): File =
        Files.createTempFile("oxford-pets-", ".txt").toFile().apply { writeText(contents) }
}
