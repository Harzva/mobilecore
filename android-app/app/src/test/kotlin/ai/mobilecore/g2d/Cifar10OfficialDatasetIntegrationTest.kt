package ai.mobilecore.g2d

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class Cifar10OfficialDatasetIntegrationTest {
    @Test
    fun `official test batch has ten thousand balanced samples`() {
        val path = System.getProperty("cifar10.testBatch").orEmpty()
        assumeTrue("Run with -Pcifar10TestBatch=/path/to/test_batch.bin", path.isNotBlank())
        val file = File(path)
        assumeTrue("Configured CIFAR-10 test batch does not exist", file.isFile)

        Cifar10Dataset.open(file).use { dataset ->
            assertEquals(Cifar10Dataset.OFFICIAL_TEST_SAMPLES, dataset.sampleCount)
            assertArrayEquals(IntArray(10) { 1_000 }, dataset.labelHistogram())
            assertEquals(100, dataset.stratifiedIndices(Cifar10RunScale.SMOKE.perClass).size)
            assertEquals(1_000, dataset.stratifiedIndices(Cifar10RunScale.PILOT.perClass).size)
        }
    }
}
