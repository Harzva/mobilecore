package ai.mobilecore.ui

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsScreenPresenterTest {
    @Test
    fun `insight identifies strongest dimensions and memory bottleneck`() {
        val snapshot = requireNotNull(ResultsScreenPresenter.parse(report("run-2", 908, "quick", 117.86, 25.0, 462)))

        val insight = ResultsScreenPresenter.insight(snapshot)

        assertEquals("优秀", insight.rating)
        assertTrue("响应" in insight.strongest)
        assertEquals("内存", insight.bottleneck)
        assertTrue(insight.modeHint.contains("仅供预览"))
        assertEquals("CPU", snapshot.backendLabel)
        assertEquals("CPU · llama.cpp · 4 线程", snapshot.executionLabel)
    }

    @Test
    fun `comparison only accepts identical model device spec and profile`() {
        val current = requireNotNull(ResultsScreenPresenter.parse(report("run-2", 908, "standard", 120.0, 26.0, 462)))
        val matching = requireNotNull(ResultsScreenPresenter.parse(report("run-1", 874, "standard", 110.0, 25.0, 478)))
        val quick = requireNotNull(ResultsScreenPresenter.parse(report("run-0", 900, "quick", 118.0, 25.0, 470)))

        val comparison = ResultsScreenPresenter.compare(current, matching)

        assertNotNull(comparison)
        assertEquals(908, comparison?.current?.canonicalScore)
        assertEquals(34, comparison?.canonicalDelta)
        assertEquals(-16L, comparison?.memoryDeltaMb)
        assertEquals(0.0, comparison?.firstTokenPercentDelta ?: Double.NaN, 0.0001)
        assertNull(ResultsScreenPresenter.compare(current, quick))
    }

    @Test
    fun `explicit comparison baseline must be another compatible run`() {
        val current = requireNotNull(ResultsScreenPresenter.parse(report("run-2", 908, "standard", 120.0, 26.0, 462)))
        val matching = report("run-1", 874, "standard", 110.0, 25.0, 478)
        val quick = report("run-0", 900, "quick", 118.0, 25.0, 470)
        val reports = JSONArray().put(matching).put(quick)

        assertEquals("run-1", ResultsScreenPresenter.comparableByRunId(current, reports, "run-1")?.runId)
        assertNull(ResultsScreenPresenter.comparableByRunId(current, reports, "run-0"))
        assertNull(ResultsScreenPresenter.comparableByRunId(current, reports, "run-2"))
    }

    @Test
    fun `rating bands and standard eligibility are explained without population claims`() {
        val excellent = requireNotNull(ResultsScreenPresenter.parse(report("excellent", 850, "standard", 100.0, 25.0, 420)))
        val good = requireNotNull(ResultsScreenPresenter.parse(report("good", 700, "standard", 80.0, 25.0, 500)))
        val average = requireNotNull(ResultsScreenPresenter.parse(report("average", 500, "quick", 50.0, 25.0, 600)))
        val limited = requireNotNull(ResultsScreenPresenter.parse(report("limited", 499, "quick", 30.0, 25.0, 700)))

        assertEquals("优秀", ResultsScreenPresenter.insight(excellent).rating)
        assertEquals("良好", ResultsScreenPresenter.insight(good).rating)
        assertEquals("一般", ResultsScreenPresenter.insight(average).rating)
        assertEquals("受限", ResultsScreenPresenter.insight(limited).rating)
        assertTrue(ResultsScreenPresenter.insight(excellent).modeHint.contains("具备榜单资格"))
        assertTrue(ResultsScreenPresenter.insight(average).modeHint.contains("仅供预览"))
        assertFalse(ResultsScreenPresenter.insight(excellent).summary.contains("全国"))
        assertFalse(ResultsScreenPresenter.insight(excellent).summary.contains("用户"))
    }

    @Test
    fun `automatic previous comparison skips incompatible newer reports`() {
        val currentReport = report("run-3", 908, "standard", 120.0, 26.0, 462)
        val current = requireNotNull(ResultsScreenPresenter.parse(currentReport))
        val reports = JSONArray()
            .put(currentReport)
            .put(report("run-2", 900, "quick", 118.0, 25.0, 470))
            .put(report("run-1", 874, "standard", 110.0, 25.0, 478))

        val previous = ResultsScreenPresenter.previousComparable(current, reports)

        assertEquals("run-1", previous?.runId)
    }

    private fun report(runId: String, canonical: Int, profile: String, speed: Double, temperature: Double, memory: Long): JSONObject {
        return JSONObject().apply {
            put("run_id", runId)
            put("created_at_ms", 1_000L)
            put("manifest_sha256", "same-model")
            put("valid", true)
            put("execution", JSONObject().apply {
                put("compute_backend", "cpu")
                put("runtime", "llama.cpp")
                put("gpu_layers", 0)
            })
            put("device", JSONObject().apply {
                put("manufacturer", "Google")
                put("model", "Pixel")
                put("device", "pixel")
            })
            put("spec", JSONObject().apply {
                put("id", "tuima-llm-benchmark-v2")
                put("version", 2)
                put("score_algorithm_id", "tuima-score-v2")
                put("profile", profile)
                put("prompt_asset_id", "prompt")
                put("context_length", 2048)
                put("threads", 4)
                put("temperature", 0.2)
            })
            put("summary", JSONObject().apply {
                put("median_decode_tokens_per_second", speed)
                put("median_first_token_ms", 400)
                put("memory_peak_mb", memory)
                put("battery_temperature_peak_celsius", temperature)
                put("battery_delta_percent", 1)
            })
            put("score", JSONObject().apply {
                put("headline", canonical * 1000)
                put("canonical", canonical)
                put("dimensions", JSONObject().apply {
                    put("inference", 323)
                    put("responsiveness", 150)
                    put("memory", 85)
                    put("sustained_performance", 200)
                    put("stability", 150)
                })
            })
        }
    }
}
