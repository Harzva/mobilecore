package ai.mobilecore.g2d

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

data class Cifar10Sample(
    val index: Int,
    val labelIndex: Int,
    val label: String,
    /** Official planar byte layout: 1024 R, 1024 G, then 1024 B bytes. */
    val planarRgb: ByteArray,
) {
    init {
        require(index >= 0) { "Sample index must be non-negative." }
        require(labelIndex in Cifar10Dataset.LABELS.indices) { "CIFAR-10 label must be in 0..9." }
        require(planarRgb.size == Cifar10Dataset.PIXEL_BYTES) { "CIFAR-10 image must contain 3072 bytes." }
        require(label == Cifar10Dataset.LABELS[labelIndex]) { "Label text must match the official index." }
    }

    fun argbPixels(): IntArray = IntArray(Cifar10Dataset.WIDTH * Cifar10Dataset.HEIGHT) { pixel ->
        val red = planarRgb[pixel].toInt() and 0xFF
        val green = planarRgb[pixel + Cifar10Dataset.CHANNEL_BYTES].toInt() and 0xFF
        val blue = planarRgb[pixel + Cifar10Dataset.CHANNEL_BYTES * 2].toInt() and 0xFF
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
}

/** Random-access reader for the University of Toronto CIFAR-10 binary test batch. */
class Cifar10Dataset private constructor(
    private val source: RandomAccessFile,
    val sampleCount: Int,
) : Closeable {
    fun read(index: Int): Cifar10Sample {
        require(index in 0 until sampleCount) { "CIFAR-10 index $index is out of range." }
        source.seek(index.toLong() * RECORD_BYTES)
        val labelIndex = source.readUnsignedByte()
        require(labelIndex in LABELS.indices) { "Invalid CIFAR-10 label $labelIndex at record $index." }
        val pixels = ByteArray(PIXEL_BYTES)
        source.readFully(pixels)
        return Cifar10Sample(index, labelIndex, LABELS[labelIndex], pixels)
    }

    fun labelHistogram(): IntArray {
        val counts = IntArray(LABELS.size)
        repeat(sampleCount) { index -> counts[read(index).labelIndex]++ }
        return counts
    }

    /** Stable first-N-per-class plan; labels are used only to construct the evaluation subset. */
    fun stratifiedIndices(perClass: Int): List<Int> {
        require(perClass > 0) { "Per-class sample count must be positive." }
        val selected = Array(LABELS.size) { mutableListOf<Int>() }
        for (index in 0 until sampleCount) {
            val label = read(index).labelIndex
            if (selected[label].size < perClass) selected[label].add(index)
            if (selected.all { it.size == perClass }) break
        }
        require(selected.all { it.size == perClass }) {
            "Dataset does not contain $perClass samples for every CIFAR-10 class."
        }
        return selected.flatMap { it }.sorted()
    }

    override fun close() = source.close()

    companion object {
        const val WIDTH = 32
        const val HEIGHT = 32
        const val CHANNEL_BYTES = WIDTH * HEIGHT
        const val PIXEL_BYTES = CHANNEL_BYTES * 3
        const val RECORD_BYTES = PIXEL_BYTES + 1
        const val OFFICIAL_TEST_SAMPLES = 10_000
        const val OFFICIAL_TEST_BYTES = OFFICIAL_TEST_SAMPLES.toLong() * RECORD_BYTES
        const val OFFICIAL_BINARY_ARCHIVE_MD5 = "c32a1d4ab5d03f1284b67883e8d87530"
        const val OFFICIAL_BINARY_URL = "https://www.cs.toronto.edu/~kriz/cifar-10-binary.tar.gz"

        val LABELS = listOf(
            "airplane",
            "automobile",
            "bird",
            "cat",
            "deer",
            "dog",
            "frog",
            "horse",
            "ship",
            "truck",
        )

        fun open(file: File, requireOfficialTestBatch: Boolean = true): Cifar10Dataset {
            require(file.isFile) { "CIFAR-10 batch does not exist: ${file.absolutePath}" }
            require(file.length() % RECORD_BYTES == 0L) {
                "CIFAR-10 binary length must be a multiple of $RECORD_BYTES bytes."
            }
            if (requireOfficialTestBatch) {
                require(file.name == "test_batch.bin") { "Expected the official test_batch.bin file." }
                require(file.length() == OFFICIAL_TEST_BYTES) {
                    "Official test_batch.bin must be exactly $OFFICIAL_TEST_BYTES bytes."
                }
            }
            val count = (file.length() / RECORD_BYTES).toInt()
            require(count > 0) { "CIFAR-10 batch must contain at least one record." }
            return Cifar10Dataset(RandomAccessFile(file, "r"), count)
        }
    }
}

enum class Cifar10RunScale(val perClass: Int, val displayName: String) {
    SMOKE(perClass = 10, displayName = "100 张 smoke test"),
    PILOT(perClass = 100, displayName = "1,000 张 pilot test"),
    FULL(perClass = 1_000, displayName = "10,000 张完整测试"),
}

data class G2dDatasetSpec(
    val id: String,
    val displayName: String,
    val classCount: Int,
    val paperTestSize: Int,
)

/** Exact test sizes from the paper's Full Main Benchmark Table. */
object G2dPaperDatasetCatalog {
    val datasets = listOf(
        G2dDatasetSpec("dtd", "DTD", 47, 1_692),
        G2dDatasetSpec("oxford_pets", "Oxford-Pets", 37, 3_669),
        G2dDatasetSpec("cub200", "CUB200", 200, 5_794),
        G2dDatasetSpec("eurosat", "EuroSAT", 10, 8_100),
        G2dDatasetSpec("imagenet_v2", "ImageNetV2", 1_000, 10_000),
        G2dDatasetSpec("food101", "Food-101", 101, 30_300),
        G2dDatasetSpec("places365", "Places365", 365, 36_500),
        G2dDatasetSpec("imagenet", "ImageNet", 1_000, 50_000),
    )

    val smallest: G2dDatasetSpec get() = datasets.minBy(G2dDatasetSpec::paperTestSize)
}
