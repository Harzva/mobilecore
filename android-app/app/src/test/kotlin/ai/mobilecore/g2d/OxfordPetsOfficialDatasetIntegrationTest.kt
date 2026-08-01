package ai.mobilecore.g2d

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class OxfordPetsOfficialDatasetIntegrationTest {
    @Test
    fun `official test split has paper sample count and balanced pilot`() {
        val path = System.getProperty("oxfordPets.testSplit").orEmpty()
        assumeTrue("Pass -PoxfordPetsTestSplit=/path/to/annotations/test.txt", path.isNotBlank())
        val imagesPath = System.getProperty("oxfordPets.images").orEmpty()
        val images = imagesPath.takeIf(String::isNotBlank)?.let(::File)

        val dataset = OxfordPetsDataset.openOfficialTest(
            annotationsRoot = File(path),
            imagesRoot = images,
            requireImages = images != null,
        )

        assertEquals(3_669, dataset.sampleCount)
        assertEquals(37, dataset.classHistogram().size)
        assertEquals(37, dataset.samplesFor(OxfordPetsRunScale.SMOKE).size)
        assertEquals(370, dataset.samplesFor(OxfordPetsRunScale.PILOT).size)
        assertEquals(3_669, dataset.samplesFor(OxfordPetsRunScale.FULL).size)
    }
}
