package ai.mobilecore.gallery.search

import ai.mobilecore.g2d.OxfordPetsDataset
import ai.mobilecore.g2d.OxfordPetsRunScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OxfordPetsRetrievalAdapterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `37 and 370 plans preserve official per-class order and closed relevance sets`() {
        val dataset = syntheticDataset()

        val smoke = OxfordPetsRetrievalAdapter.create(dataset, OxfordPetsRunScale.SMOKE)
        val pilot = OxfordPetsRetrievalAdapter.create(dataset, OxfordPetsRunScale.PILOT)

        assertEquals(37, smoke.photos.size)
        assertEquals(370, pilot.photos.size)
        assertEquals(37, smoke.queries.size)
        assertTrue(smoke.queries.all { it.relevantMediaIds.size == 1 })
        assertTrue(pilot.queries.all { it.relevantMediaIds.size == 10 })
        assertEquals(smoke.selectionDigest, OxfordPetsRetrievalAdapter.create(dataset, OxfordPetsRunScale.SMOKE).selectionDigest)
        assertNotEquals(smoke.selectionDigest, pilot.selectionDigest)
    }

    @Test
    fun `evaluation rejects rankings that escape the benchmark gallery`() {
        val plan = OxfordPetsRetrievalAdapter.create(syntheticDataset(), OxfordPetsRunScale.SMOKE)
        val rankings = plan.queries.associate { query ->
            query.classIndex to listOf(query.relevantMediaIds.single())
        }.toMutableMap()
        rankings[0] = listOf("invented")

        val error = runCatching { OxfordPetsRetrievalAdapter.evaluate(plan, rankings) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `perfect closed rankings report deterministic retrieval metrics`() {
        val plan = OxfordPetsRetrievalAdapter.create(syntheticDataset(), OxfordPetsRunScale.PILOT)
        val rankings = plan.queries.associate { query ->
            query.classIndex to query.relevantMediaIds.toList()
        }

        val metrics = OxfordPetsRetrievalAdapter.evaluate(plan, rankings)

        assertEquals(1.0, metrics.recallAt1, 0.0)
        assertEquals(1.0, metrics.recallAt5, 0.0)
        assertEquals(1.0, metrics.meanReciprocalRankAt100, 0.0)
        assertTrue(OxfordPetsRetrievalQualityGate.evaluate(plan, rankings, metrics).passed)
    }

    @Test
    fun `mrr at 100 does not count a relevant result beyond the cutoff`() {
        val plan = OxfordPetsRetrievalAdapter.create(syntheticDataset(), OxfordPetsRunScale.PILOT)
        val rankings = plan.queries.associate { query ->
            val relevant = query.relevantMediaIds.first()
            val irrelevant = plan.photos
                .asSequence()
                .map { it.mediaId }
                .filterNot(query.relevantMediaIds::contains)
                .take(100)
                .toList()
            query.classIndex to (irrelevant + relevant)
        }

        val metrics = OxfordPetsRetrievalAdapter.evaluate(plan, rankings)

        assertEquals(0.0, metrics.meanReciprocalRankAt100, 0.0)
    }

    @Test
    fun `quality gate rejects random-level recall even with diverse top one results`() {
        val plan = OxfordPetsRetrievalAdapter.create(syntheticDataset(), OxfordPetsRunScale.SMOKE)
        val rankings = plan.queries.associate { query ->
            val wrongClass = (query.classIndex + 1) % plan.queries.size
            query.classIndex to listOf(plan.queries[wrongClass].relevantMediaIds.single())
        }
        val metrics = OxfordPetsRetrievalAdapter.evaluate(plan, rankings)

        val quality = OxfordPetsRetrievalQualityGate.evaluate(plan, rankings, metrics)

        assertFalse(quality.passed)
        assertTrue("recall_at_1_not_above_random_gate" in quality.failureCodes)
        assertFalse("top1_diversity_too_low" in quality.failureCodes)
        assertFalse("top1_mode_collapse" in quality.failureCodes)
    }

    @Test
    fun `quality gate rejects fixed top one mode collapse independently of recall metric`() {
        val plan = OxfordPetsRetrievalAdapter.create(syntheticDataset(), OxfordPetsRunScale.SMOKE)
        val fixed = plan.photos.first().mediaId
        val rankings = plan.queries.associate { it.classIndex to listOf(fixed) }
        val declaredMetrics = OxfordPetsRetrievalMetrics(
            queryCount = plan.queries.size,
            recallAt1 = 1.0,
            recallAt5 = 1.0,
            meanReciprocalRankAt100 = 1.0,
        )

        val quality = OxfordPetsRetrievalQualityGate.evaluate(plan, rankings, declaredMetrics)

        assertFalse(quality.passed)
        assertTrue("top1_diversity_too_low" in quality.failureCodes)
        assertTrue("top1_mode_collapse" in quality.failureCodes)
    }

    private fun syntheticDataset(): OxfordPetsDataset {
        val root = temporary.newFolder("oxford")
        val images = root.resolve("images").apply { mkdirs() }
        val split = root.resolve("test.txt")
        val cats = setOf(
            "Abyssinian", "Bengal", "Birman", "Bombay", "British_Shorthair",
            "Egyptian_Mau", "Maine_Coon", "Persian", "Ragdoll", "Russian_Blue",
            "Siamese", "Sphynx",
        )
        var catBreed = 0
        var dogBreed = 0
        val records = buildList {
            OxfordPetsDataset.CLASS_NAMES.forEachIndexed { classIndex, className ->
                val species = if (className in cats) 1 else 2
                val breed = if (species == 1) ++catBreed else ++dogBreed
                repeat(10) { offset ->
                    val imageId = "${className}_${offset + 1}"
                    images.resolve("$imageId.jpg").writeBytes(byteArrayOf(0x01))
                    add("$imageId ${classIndex + 1} $species $breed")
                }
            }
        }
        split.writeText(records.joinToString("\n"))
        return OxfordPetsDataset.openSplit(split, images, requireImages = true)
    }
}
