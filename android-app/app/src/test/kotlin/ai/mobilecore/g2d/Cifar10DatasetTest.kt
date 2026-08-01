package ai.mobilecore.g2d

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class Cifar10DatasetTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reader decodes official planar channel order`() {
        val file = temporaryFolder.newFile("tiny.bin")
        writeRecords(file, labels = listOf(3)) { channel, pixel ->
            when (channel) {
                0 -> if (pixel == 0) 0x11 else 0
                1 -> if (pixel == 0) 0x22 else 0
                else -> if (pixel == 0) 0x33 else 0
            }
        }

        Cifar10Dataset.open(file, requireOfficialTestBatch = false).use { dataset ->
            val sample = dataset.read(0)
            assertEquals("cat", sample.label)
            assertEquals(0xFF112233.toInt(), sample.argbPixels().first())
            assertEquals(1, dataset.sampleCount)
        }
    }

    @Test
    fun `stratified plans contain equal examples from every class`() {
        val file = temporaryFolder.newFile("balanced.bin")
        writeRecords(file, labels = (0 until 10).flatMap { label -> List(3) { label } }) { _, _ -> 0 }

        Cifar10Dataset.open(file, requireOfficialTestBatch = false).use { dataset ->
            assertArrayEquals(IntArray(10) { 3 }, dataset.labelHistogram())
            val indices = dataset.stratifiedIndices(perClass = 2)
            val selectedLabels = indices.map(dataset::read).groupingBy(Cifar10Sample::labelIndex).eachCount()
            assertEquals(20, indices.size)
            assertEquals((0 until 10).associateWith { 2 }, selectedLabels)
        }
    }

    @Test
    fun `invalid record length is rejected before evaluation`() {
        val file = temporaryFolder.newFile("broken.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertThrows(IllegalArgumentException::class.java) {
            Cifar10Dataset.open(file, requireOfficialTestBatch = false)
        }
    }

    @Test
    fun `paper catalog keeps DTD as exact smallest protocol`() {
        val smallest = G2dPaperDatasetCatalog.smallest

        assertEquals("DTD", smallest.displayName)
        assertEquals(47, smallest.classCount)
        assertEquals(1_692, smallest.paperTestSize)
    }

    private fun writeRecords(
        file: File,
        labels: List<Int>,
        pixel: (channel: Int, pixel: Int) -> Int,
    ) {
        FileOutputStream(file).use { output ->
            labels.forEach { label ->
                output.write(label)
                repeat(3) { channel ->
                    repeat(Cifar10Dataset.CHANNEL_BYTES) { pixelIndex ->
                        output.write(pixel(channel, pixelIndex))
                    }
                }
            }
        }
    }
}
