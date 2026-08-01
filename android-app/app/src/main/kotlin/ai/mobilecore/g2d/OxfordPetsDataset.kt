package ai.mobilecore.g2d

import java.io.File

enum class OxfordPetsSpecies(val officialId: Int, val displayName: String) {
    CAT(1, "cat"),
    DOG(2, "dog");

    companion object {
        fun fromOfficialId(id: Int): OxfordPetsSpecies = entries.firstOrNull {
            it.officialId == id
        } ?: throw IllegalArgumentException("Oxford-Pets species ID must be 1 or 2, got $id.")
    }
}

data class OxfordPetsSample(
    val officialIndex: Int,
    val imageId: String,
    val classIndex: Int,
    val className: String,
    val species: OxfordPetsSpecies,
    val speciesBreedId: Int,
    val imageFile: File?,
)

/** Strict parser for the split used by the Oxford-IIIT Pet paper and G2D evaluation. */
class OxfordPetsDataset private constructor(
    val samples: List<OxfordPetsSample>,
) {
    val sampleCount: Int get() = samples.size

    fun classHistogram(): Map<Int, Int> = samples
        .groupingBy(OxfordPetsSample::classIndex)
        .eachCount()
        .toSortedMap()

    /** Takes the first N official test entries per breed without creating a new random split. */
    fun stratifiedSamples(perClass: Int): List<OxfordPetsSample> {
        require(perClass > 0) { "Samples per class must be positive." }
        val grouped = samples.groupBy(OxfordPetsSample::classIndex).toSortedMap()
        val selected = grouped.flatMap { (_, breed) ->
            require(breed.size >= perClass) {
                "Oxford-Pets class ${breed.first().className} has only ${breed.size} samples."
            }
            breed.take(perClass)
        }
        require(selected.size == grouped.size * perClass)
        return selected
    }

    fun samplesFor(scale: OxfordPetsRunScale): List<OxfordPetsSample> = when (scale) {
        OxfordPetsRunScale.FULL -> samples
        else -> stratifiedSamples(requireNotNull(scale.perClass))
    }

    companion object {
        const val ANNOTATIONS_URL =
            "https://www.robots.ox.ac.uk/~vgg/data/pets/data/annotations.tar.gz"
        const val IMAGES_URL = "https://www.robots.ox.ac.uk/~vgg/data/pets/data/images.tar.gz"
        const val OFFICIAL_TEST_SAMPLES = 3_669
        const val OFFICIAL_TRAINVAL_SAMPLES = 3_680
        const val CLASS_COUNT = 37

        val CLASS_NAMES: List<String> = listOf(
            "Abyssinian",
            "american_bulldog",
            "american_pit_bull_terrier",
            "basset_hound",
            "beagle",
            "Bengal",
            "Birman",
            "Bombay",
            "boxer",
            "British_Shorthair",
            "chihuahua",
            "Egyptian_Mau",
            "english_cocker_spaniel",
            "english_setter",
            "german_shorthaired",
            "great_pyrenees",
            "havanese",
            "japanese_chin",
            "keeshond",
            "leonberger",
            "Maine_Coon",
            "miniature_pinscher",
            "newfoundland",
            "Persian",
            "pomeranian",
            "pug",
            "Ragdoll",
            "Russian_Blue",
            "saint_bernard",
            "samoyed",
            "scottish_terrier",
            "shiba_inu",
            "Siamese",
            "Sphynx",
            "staffordshire_bull_terrier",
            "wheaten_terrier",
            "yorkshire_terrier",
        )

        private val OFFICIAL_TEST_HISTOGRAM = listOf(
            98, 100, 100, 100, 100, 100, 100, 88, 99, 100,
            100, 97, 100, 100, 100, 100, 100, 100, 99, 100,
            100, 100, 100, 100, 100, 100, 100, 100, 100, 100,
            99, 100, 100, 100, 89, 100, 100,
        )

        fun openOfficialTest(
            annotationsRoot: File,
            imagesRoot: File? = null,
            requireImages: Boolean = imagesRoot != null,
        ): OxfordPetsDataset {
            val split = if (annotationsRoot.isDirectory) {
                File(annotationsRoot, "test.txt")
            } else {
                annotationsRoot
            }
            return openSplit(
                splitFile = split,
                imagesRoot = imagesRoot,
                requireOfficialTest = true,
                requireImages = requireImages,
            )
        }

        fun openSplit(
            splitFile: File,
            imagesRoot: File? = null,
            requireOfficialTest: Boolean = false,
            requireImages: Boolean = false,
        ): OxfordPetsDataset {
            require(splitFile.isFile) { "Oxford-Pets split file is missing: $splitFile" }
            require(!requireImages || imagesRoot?.isDirectory == true) {
                "Oxford-Pets images directory is missing: $imagesRoot"
            }

            val samples = splitFile.useLines { lines ->
                lines.filter(String::isNotBlank).mapIndexed { index, line ->
                    parseLine(index, line, imagesRoot, requireImages)
                }.toList()
            }
            require(samples.isNotEmpty()) { "Oxford-Pets split must contain at least one sample." }
            require(samples.map(OxfordPetsSample::imageId).distinct().size == samples.size) {
                "Oxford-Pets split contains duplicate image IDs."
            }

            val dataset = OxfordPetsDataset(samples)
            if (requireOfficialTest) dataset.requireOfficialTestProtocol()
            return dataset
        }

        private fun parseLine(
            index: Int,
            rawLine: String,
            imagesRoot: File?,
            requireImages: Boolean,
        ): OxfordPetsSample {
            val fields = rawLine.trim().split(Regex("\\s+"))
            require(fields.size == 4) {
                "Invalid Oxford-Pets record at line ${index + 1}: expected four fields."
            }
            val imageId = fields[0]
            require(imageId.matches(Regex("[A-Za-z0-9_]+"))) {
                "Invalid Oxford-Pets image ID at line ${index + 1}: $imageId"
            }
            val classId = fields[1].toIntOrNull()
                ?: throw IllegalArgumentException("Invalid class ID at line ${index + 1}.")
            require(classId in 1..CLASS_COUNT) {
                "Oxford-Pets class ID must be in 1..$CLASS_COUNT at line ${index + 1}."
            }
            val speciesId = fields[2].toIntOrNull()
                ?: throw IllegalArgumentException("Invalid species ID at line ${index + 1}.")
            val species = OxfordPetsSpecies.fromOfficialId(speciesId)
            val breedId = fields[3].toIntOrNull()
                ?: throw IllegalArgumentException("Invalid breed ID at line ${index + 1}.")
            val validBreedRange = if (species == OxfordPetsSpecies.CAT) 1..12 else 1..25
            require(breedId in validBreedRange) {
                "Oxford-Pets breed ID $breedId is invalid for ${species.displayName}."
            }

            val className = imageId.replace(Regex("_[0-9]+$"), "")
            require(className == CLASS_NAMES[classId - 1]) {
                "Image $imageId does not match official class ID $classId."
            }
            val imageFile = imagesRoot?.let { File(it, "$imageId.jpg") }
            require(!requireImages || imageFile?.isFile == true) {
                "Oxford-Pets image is missing: $imageFile"
            }
            return OxfordPetsSample(
                officialIndex = index,
                imageId = imageId,
                classIndex = classId - 1,
                className = className,
                species = species,
                speciesBreedId = breedId,
                imageFile = imageFile,
            )
        }

        private fun OxfordPetsDataset.requireOfficialTestProtocol() {
            require(sampleCount == OFFICIAL_TEST_SAMPLES) {
                "Official Oxford-Pets test.txt must contain $OFFICIAL_TEST_SAMPLES samples, got $sampleCount."
            }
            val histogram = classHistogram()
            require(histogram.keys == (0 until CLASS_COUNT).toSet()) {
                "Official Oxford-Pets test.txt must contain all $CLASS_COUNT classes."
            }
            require((0 until CLASS_COUNT).all { histogram[it] == OFFICIAL_TEST_HISTOGRAM[it] }) {
                "Oxford-Pets test.txt class histogram does not match the official split."
            }
        }
    }
}

enum class OxfordPetsRunScale(
    val perClass: Int?,
    val displayName: String,
    val expectedSamples: Int,
) {
    SMOKE(1, "37 张冒烟（每类 1 张）", 37),
    PILOT(10, "370 张试跑（每类 10 张）", 370),
    FULL(null, "3,669 张官方测试集", OxfordPetsDataset.OFFICIAL_TEST_SAMPLES),
}
