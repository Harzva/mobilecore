package ai.mobilecore.gallery.search

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.mobilecore.g2d.OxfordPetsDataset
import ai.mobilecore.g2d.OxfordPetsRunScale
import ai.onnxruntime.OrtEnvironment
import com.mobilecore.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in ARM64 Android evidence harness for the real ONNX CLIP retrieval path.
 *
 * The large model and Oxford-Pets files are deliberately not bundled in the APK. Seed them in the
 * target app's private directories, then pass `-e runGalleryRetrieval true`. A normal connected
 * test run skips this case instead of silently substituting fixtures for model inference.
 */
@RunWith(AndroidJUnit4::class)
class OxfordPetsGalleryRetrievalInstrumentedTest {
    @Test
    fun runRealClipRetrievalOnRequestedOxfordPetsScale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Real CLIP artifacts and Oxford-Pets media are required for this opt-in harness.",
            arguments.getString("runGalleryRetrieval") == "true",
        )
        assertTrue("This evidence harness requires ARM64.", Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val environment = GalleryEvidenceEnvironmentClassifier.classify(
            fingerprint = Build.FINGERPRINT,
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            device = Build.DEVICE,
            product = Build.PRODUCT,
            hardware = Build.HARDWARE,
        )
        val requiredEnvironment = when (
            arguments.getString("galleryEnvironment", "emulator").lowercase()
        ) {
            "emulator" -> GalleryEvidenceEnvironment.ANDROID_EMULATOR
            "physical" -> GalleryEvidenceEnvironment.ANDROID_PHYSICAL
            else -> error("galleryEnvironment must be emulator or physical.")
        }
        assertEquals(
            "Refusing to write evidence for a device class different from the requested class.",
            requiredEnvironment,
            environment,
        )

        val scale = when (arguments.getString("galleryScale", "smoke").lowercase()) {
            "smoke" -> OxfordPetsRunScale.SMOKE
            "pilot" -> OxfordPetsRunScale.PILOT
            else -> error("Gallery retrieval supports only smoke or pilot during this iteration.")
        }
        val context = instrumentation.targetContext
        val models = File(context.filesDir, "vision/models")
        val benchmarkRoot = File(requireNotNull(context.getExternalFilesDir("g2d")), "retrieval")
        val annotations = File(benchmarkRoot, "annotations/test.txt")
        val images = File(benchmarkRoot, "images")
        val dataset = OxfordPetsDataset.openOfficialTest(
            annotationsRoot = annotations,
            imagesRoot = images,
            // The smoke/pilot harness only needs the deterministic selected subset on device.
            requireImages = false,
        )
        val plan = OxfordPetsRetrievalAdapter.create(dataset, scale)
        val opened = OnnxClipGalleryRuntime.open(GalleryClipArtifactSet.discover(models))
        val runtime = (opened as? GalleryRuntimeOpenResult.Ready)?.runtime
            ?: error("Real CLIP runtime did not open: ${(opened as GalleryRuntimeOpenResult.Blocked).failure.code}")
        val indexFile = File(context.cacheDir, "qa/oxford-pets-gallery-${scale.name.lowercase()}.mcgi")
        BinaryGalleryIndexStore(indexFile).clear()

        val startedAt = SystemClock.elapsedRealtime()
        val rankings = linkedMapOf<Int, List<String>>()
        val measurements = try {
            val coordinator = GallerySearchCoordinator(
                discovery = plan.discovery,
                mediaReader = plan.mediaReader,
                runtime = runtime,
                store = BinaryGalleryIndexStore(indexFile),
            )
            val indexStarted = SystemClock.elapsedRealtime()
            val indexOutcome = coordinator.buildOrUpdateIndex(plan.selection) { progress ->
                if (progress.processedCount == progress.totalCount || progress.processedCount % 5 == 0) {
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString(
                            "gallery_progress",
                            "index ${progress.processedCount}/${progress.totalCount}",
                        )
                    })
                }
            }
            val indexingMs = SystemClock.elapsedRealtime() - indexStarted
            val completed = indexOutcome as? GalleryIndexOutcome.Completed
                ?: error("Oxford-Pets gallery indexing did not complete: $indexOutcome")
            assertEquals(scale.expectedSamples, completed.snapshot.entries.size)

            val searchStarted = SystemClock.elapsedRealtime()
            plan.queries.forEachIndexed { queryIndex, query ->
                val result = coordinator.search(
                    query = query.text,
                    topK = minOf(100, scale.expectedSamples),
                ) as? GalleryQueryOutcome.Results
                    ?: error("CLIP text-to-image query was blocked for class ${query.classIndex}")
                rankings[query.classIndex] = result.hits.map { it.photo.mediaId }
                if ((queryIndex + 1) % 5 == 0 || queryIndex == plan.queries.lastIndex) {
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString(
                            "gallery_progress",
                            "query ${queryIndex + 1}/${plan.queries.size}",
                        )
                    })
                }
            }
            val searchMs = SystemClock.elapsedRealtime() - searchStarted
            Triple(completed, indexingMs, searchMs)
        } finally {
            runtime.close()
        }

        val metrics = OxfordPetsRetrievalAdapter.evaluate(plan, rankings)
        assertEquals(OxfordPetsDataset.CLASS_COUNT, metrics.queryCount)
        assertTrue(metrics.recallAt1 in 0.0..1.0)
        assertTrue(metrics.recallAt5 in 0.0..1.0)
        assertTrue(metrics.meanReciprocalRankAt100 in 0.0..1.0)
        val quality = OxfordPetsRetrievalQualityGate.evaluate(plan, rankings, metrics)

        val (completed, indexingMs, searchMs) = measurements
        val report = JSONObject().apply {
            put("schema_version", "1.1")
            put("status", if (quality.passed) "pass" else "fail")
            put("task", "text_to_image_retrieval")
            put("dataset", "Oxford-IIIT Pet official test.txt")
            put("scale", scale.name.lowercase())
            put("sample_count", scale.expectedSamples)
            put("query_count", metrics.queryCount)
            put("retrieval_depth", minOf(OxfordPetsRetrievalAdapter.MRR_CUTOFF, scale.expectedSamples))
            put("selection_sha256", plan.selectionDigest)
            put("app", JSONObject().apply {
                put("application_id", BuildConfig.APPLICATION_ID)
                put("version_name", BuildConfig.VERSION_NAME)
                put("version_code", BuildConfig.VERSION_CODE)
            })
            put("model", JSONObject().apply {
                put("id", runtime.descriptor.modelId)
                put("combined_artifact_sha256", runtime.descriptor.modelDigest)
                put("image_encoder", runtime.descriptor.imageEncoderName)
                put("text_encoder", runtime.descriptor.textEncoderName)
                put("embedding_dimension", runtime.descriptor.embeddingDimension)
                put("identity_verified", runtime.descriptor.identityVerified)
            })
            put("runtime", JSONObject().apply {
                put("name", "ONNX Runtime Android")
                put("version", OrtEnvironment.getEnvironment().version)
                put("execution_provider", "CPUExecutionProvider")
                put("provider_configuration", "default session options; no accelerator EP registered")
                put(
                    "available_providers",
                    JSONArray(OrtEnvironment.getAvailableProviders().map { it.name }.sorted()),
                )
            })
            put("index", JSONObject().apply {
                put("encoded_count", completed.stats.encodedCount)
                put("reused_count", completed.stats.reusedCount)
                put("indexing_ms", indexingMs)
            })
            put("metrics", JSONObject().apply {
                put("recall_at_1", metrics.recallAt1)
                put("recall_at_5", metrics.recallAt5)
                put("mrr_at_100", metrics.meanReciprocalRankAt100)
                put("search_ms", searchMs)
            })
            put("quality_sanity", JSONObject().apply {
                put("passed", quality.passed)
                put("failure_codes", JSONArray(quality.failureCodes))
                put("random_recall_at_1_baseline", quality.randomRecallAt1Baseline)
                put("required_recall_at_1_exclusive", quality.requiredRecallAt1Exclusive)
                put("top1_distinct_count", quality.top1DistinctCount)
                put("required_top1_distinct_count", quality.requiredTop1DistinctCount)
                put("top1_max_share", quality.top1MaxShare)
                put("allowed_top1_max_share", quality.allowedTop1MaxShare)
            })
            put("environment", JSONObject().apply {
                put("class", environment.wireName)
                put("physical_device_claim", environment == GalleryEvidenceEnvironment.ANDROID_PHYSICAL)
                put("api_level", Build.VERSION.SDK_INT)
                put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("brand", Build.BRAND)
                put("device", Build.DEVICE)
                put("product", Build.PRODUCT)
                put("hardware", Build.HARDWARE)
                put("build_fingerprint", Build.FINGERPRINT)
            })
            put("privacy", JSONObject().apply {
                put("raw_images_persisted_by_index", false)
                put("queries_persisted", false)
                put("network_required_during_inference", false)
            })
            put("total_ms", SystemClock.elapsedRealtime() - startedAt)
        }
        val reportDirectory = File(requireNotNull(context.getExternalFilesDir("g2d")), "reports")
            .apply { mkdirs() }
        val reportFile = File(
            reportDirectory,
            "oxford-pets-gallery-${scale.name.lowercase()}-emulator.json",
        )
        reportFile.writeText(report.toString(2))
        instrumentation.sendStatus(0, Bundle().apply {
            putString("gallery_report", reportFile.absolutePath)
            putString("gallery_summary", report.toString())
        })
        assertTrue(
            "Oxford-Pets retrieval quality sanity gate failed: ${quality.failureCodes.joinToString()}",
            quality.passed,
        )
    }
}
