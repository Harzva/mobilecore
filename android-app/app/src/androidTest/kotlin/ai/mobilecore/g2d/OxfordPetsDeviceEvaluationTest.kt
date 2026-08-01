package ai.mobilecore.g2d

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OxfordPetsDeviceEvaluationTest {
    @Test
    fun runRequestedScaleOnDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val requested = InstrumentationRegistry.getArguments().getString("scale", "smoke")
        val scale = when (requested.lowercase()) {
            "smoke" -> OxfordPetsRunScale.SMOKE
            "pilot" -> OxfordPetsRunScale.PILOT
            "full" -> OxfordPetsRunScale.FULL
            else -> error("Unknown Oxford-Pets scale: $requested")
        }
        val runner = OxfordPetsG2dRunner(instrumentation.targetContext)
        val startArgument = InstrumentationRegistry.getArguments().getString("range_start")
        val endArgument = InstrumentationRegistry.getArguments().getString("range_end")
        if (startArgument != null || endArgument != null) {
            val start = requireNotNull(startArgument).toInt()
            val end = requireNotNull(endArgument).toInt()
            val processed = runner.runShard(scale, start, end) { progress ->
                val message = "${progress.stage} ${progress.completed}/${progress.total} ${progress.sampleId.orEmpty()}"
                Log.i("MobileCoreG2D", message)
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("g2d_progress", message)
                })
            }
            assertEquals(end - start, processed)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("g2d_shard", "${scale.name}:[$start,$end)")
                putInt("g2d_shard_samples", processed)
            })
            return
        }
        val result = runner.run(scale) { progress ->
            val message = "${progress.stage} ${progress.completed}/${progress.total} ${progress.sampleId.orEmpty()}"
            Log.i("MobileCoreG2D", message)
            instrumentation.sendStatus(2, Bundle().apply {
                putString("g2d_progress", message)
            })
        }
        assertEquals(scale.expectedSamples, result.report.getInt("sample_count"))
        instrumentation.sendStatus(0, Bundle().apply {
            putString("g2d_report", result.reportFile.absolutePath)
            putString("g2d_summary", result.report.toString())
        })
    }
}
