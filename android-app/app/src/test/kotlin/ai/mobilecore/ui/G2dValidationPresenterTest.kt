package ai.mobilecore.ui

import ai.mobilecore.g2d.G2dCandidateTrace
import ai.mobilecore.g2d.G2dAgentRoutingTrace
import ai.mobilecore.g2d.G2dAgenticInferenceResult
import ai.mobilecore.g2d.G2dBranchTool
import ai.mobilecore.g2d.G2dExpert
import ai.mobilecore.g2d.G2dInferenceResult
import ai.mobilecore.g2d.G2dInferenceTrace
import ai.mobilecore.g2d.G2dProjectionMethod
import ai.mobilecore.g2d.G2dRoute
import ai.mobilecore.g2d.G2dVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class G2dValidationPresenterTest {
    @Test
    fun `empty ready state exposes all five experiments without fabricated metrics`() {
        val model = G2dValidationPresenter.present(G2dValidationInput())

        assertEquals(G2dValidationExperiment.entries.toList(), model.experiments.map { it.experiment })
        assertEquals("未选择数据集", model.datasetLabel)
        assertEquals("等待选择", model.sampleCountLabel)
        assertTrue(model.experiments.all {
            it.accuracyLabel == G2dValidationPresenter.WAITING_FOR_MEASUREMENT
        })
        assertTrue(model.experiments.all {
            it.p50LatencyLabel == G2dValidationPresenter.WAITING_FOR_MEASUREMENT
        })
        assertFalse(model.experiments.any { it.hasMeasuredData })
        assertFalse(model.canStart)
        assertFalse(model.canExport)
        assertNull(model.progressPercent)
    }

    @Test
    fun `accuracy and uplift are derived from matched raw sample counts`() {
        val model = G2dValidationPresenter.present(
            completedInput(
                measurement(G2dValidationExperiment.CLIP_ONLY, correct = 80),
                measurement(G2dValidationExperiment.VLM_ONLY, correct = 60),
                measurement(
                    G2dValidationExperiment.G2D_ONE_THETA,
                    correct = 75,
                    routes = G2dValidationRouteCounts(40, 60),
                ),
                measurement(
                    G2dValidationExperiment.G2D_TWO_THETA,
                    correct = 90,
                    routes = G2dValidationRouteCounts(30, 20, 50),
                ),
                measurement(
                    G2dValidationExperiment.AGENTIC_G2D,
                    correct = 92,
                    routes = G2dValidationRouteCounts(25, 15, 60),
                ),
            )
        )

        val cards = model.experiments.associateBy { it.experiment }
        assertEquals("80.00%", cards.getValue(G2dValidationExperiment.CLIP_ONLY).accuracyLabel)
        assertEquals("基线", cards.getValue(G2dValidationExperiment.CLIP_ONLY).upliftLabel)
        assertEquals(
            "-20.00 pp · 较 CLIP-only",
            cards.getValue(G2dValidationExperiment.VLM_ONLY).upliftLabel,
        )
        assertEquals(
            "+15.00 pp · 较 VLM-only",
            cards.getValue(G2dValidationExperiment.G2D_ONE_THETA).upliftLabel,
        )
        assertEquals(
            "+30.00 pp · 较 VLM-only",
            cards.getValue(G2dValidationExperiment.G2D_TWO_THETA).upliftLabel,
        )
        assertEquals(
            "+2.00 pp · 较 G2D 2θ",
            cards.getValue(G2dValidationExperiment.AGENTIC_G2D).upliftLabel,
        )
        assertTrue(model.canExport)
    }

    @Test
    fun `uplift waits when experiment and baseline use different sample counts`() {
        val model = G2dValidationPresenter.present(
            completedInput(
                G2dValidationMeasurement(
                    experiment = G2dValidationExperiment.VLM_ONLY,
                    evaluatedSamples = 80,
                    correctSamples = 40,
                ),
                measurement(
                    G2dValidationExperiment.G2D_ONE_THETA,
                    correct = 70,
                    routes = G2dValidationRouteCounts(40, 60),
                ),
            )
        )

        val g2d = model.experiments.first {
            it.experiment == G2dValidationExperiment.G2D_ONE_THETA
        }
        assertEquals("等待同样本实测", g2d.upliftLabel)
    }

    @Test
    fun `route ratios follow one-theta and two-theta paper semantics`() {
        val model = G2dValidationPresenter.present(
            completedInput(
                measurement(
                    G2dValidationExperiment.G2D_ONE_THETA,
                    correct = 70,
                    routes = G2dValidationRouteCounts(25, 75),
                ),
                measurement(
                    G2dValidationExperiment.G2D_TWO_THETA,
                    correct = 70,
                    routes = G2dValidationRouteCounts(30, 20, 50),
                ),
            )
        )

        val one = model.experiments.first {
            it.experiment == G2dValidationExperiment.G2D_ONE_THETA
        }
        val two = model.experiments.first {
            it.experiment == G2dValidationExperiment.G2D_TWO_THETA
        }
        assertEquals(listOf("25.00%", "75.00%", "—"), listOf(
            one.routeALabel,
            one.routeBLabel,
            one.routeCLabel,
        ))
        assertEquals(listOf("30.00%", "20.00%", "50.00%"), listOf(
            two.routeALabel,
            two.routeBLabel,
            two.routeCLabel,
        ))
    }

    @Test
    fun `baseline route fields are not applicable rather than invented`() {
        val model = G2dValidationPresenter.present(
            completedInput(
                measurement(G2dValidationExperiment.CLIP_ONLY, correct = 80),
                measurement(G2dValidationExperiment.VLM_ONLY, correct = 60),
            )
        )

        model.experiments
            .filter {
                it.experiment == G2dValidationExperiment.CLIP_ONLY ||
                    it.experiment == G2dValidationExperiment.VLM_ONLY
            }
            .forEach { card ->
                assertEquals(listOf("—", "—", "—"), listOf(
                    card.routeALabel,
                    card.routeBLabel,
                    card.routeCLabel,
                ))
            }
    }

    @Test
    fun `latency memory thermal battery backend and quantization keep measured units`() {
        val input = G2dValidationInput(
            state = G2dValidationRunState.COMPLETED,
            datasetName = "Oxford-Pets",
            targetSampleCount = 100,
            measurements = listOf(
                G2dValidationMeasurement(
                    experiment = G2dValidationExperiment.G2D_TWO_THETA,
                    evaluatedSamples = 100,
                    correctSamples = 81,
                    routeCounts = G2dValidationRouteCounts(30, 20, 50),
                    p50LatencyMs = 12.34,
                    p95LatencyMs = 45.67,
                    peakMemoryMb = 512.25,
                    temperatureDeltaC = 2.5,
                    batteryDeltaPercentagePoints = -4.0,
                    backend = "ONNX Runtime CPU + llama.cpp CPU",
                    quantization = "INT8 + Q4_K_M",
                )
            ),
        )

        val card = G2dValidationPresenter.present(input).experiments.first {
            it.experiment == G2dValidationExperiment.G2D_TWO_THETA
        }

        assertEquals("12.3 ms", card.p50LatencyLabel)
        assertEquals("45.7 ms", card.p95LatencyLabel)
        assertEquals("512.3 MB", card.peakMemoryLabel)
        assertEquals("+2.50 °C", card.temperatureDeltaLabel)
        assertEquals("-4.00 pp", card.batteryDeltaLabel)
        assertEquals("ONNX Runtime CPU + llama.cpp CPU", card.backendLabel)
        assertEquals("INT8 + Q4_K_M", card.quantizationLabel)
        assertTrue(card.hasMeasuredData)
    }

    @Test
    fun `backend configuration alone is not presented as a completed measurement`() {
        val model = G2dValidationPresenter.present(
            G2dValidationInput(
                state = G2dValidationRunState.COMPLETED,
                datasetName = "DTD",
                targetSampleCount = 47,
                measurements = listOf(
                    G2dValidationMeasurement(
                        experiment = G2dValidationExperiment.CLIP_ONLY,
                        backend = "ONNX Runtime CPU",
                        quantization = "FP32",
                    )
                ),
            )
        )

        val clip = model.experiments.first {
            it.experiment == G2dValidationExperiment.CLIP_ONLY
        }
        assertFalse(clip.hasMeasuredData)
        assertEquals(G2dValidationPresenter.WAITING_FOR_MEASUREMENT, clip.accuracyLabel)
        assertFalse(model.canExport)
        assertTrue(model.statusDetail.contains("缺失指标"))
    }

    @Test
    fun `ready running completed and failed states expose only valid actions`() {
        val ready = G2dValidationPresenter.present(
            G2dValidationInput(
                state = G2dValidationRunState.READY,
                datasetName = "CUB200",
                targetSampleCount = 200,
            )
        )
        assertTrue(ready.canStart)
        assertFalse(ready.canCancel)
        assertFalse(ready.canExport)
        assertEquals("开始端侧验证", ready.primaryActionLabel)

        val running = G2dValidationPresenter.present(
            G2dValidationInput(
                state = G2dValidationRunState.RUNNING,
                datasetName = "CUB200",
                targetSampleCount = 200,
                completedWorkItems = 125,
                totalWorkItems = 500,
            )
        )
        assertFalse(running.canStart)
        assertTrue(running.canCancel)
        assertEquals(25, running.progressPercent)
        assertEquals("125 / 500 次端侧推理 · 25%", running.progressLabel)

        val completed = G2dValidationPresenter.present(
            completedInput(measurement(G2dValidationExperiment.CLIP_ONLY, correct = 80))
        )
        assertTrue(completed.canStart)
        assertTrue(completed.canExport)
        assertEquals("重新验证", completed.primaryActionLabel)

        val failed = G2dValidationPresenter.present(
            G2dValidationInput(
                state = G2dValidationRunState.FAILED,
                datasetName = "Oxford-Pets",
                targetSampleCount = 100,
                failureMessage = "VLM mmproj 与主模型不匹配。",
            )
        )
        assertTrue(failed.canStart)
        assertFalse(failed.canCancel)
        assertEquals("重试验证", failed.primaryActionLabel)
        assertEquals("VLM mmproj 与主模型不匹配。", failed.statusDetail)
    }

    @Test
    fun `selected dataset remains blocked until real images and runtimes are ready`() {
        val model = G2dValidationPresenter.present(
            G2dValidationInput(
                datasetName = "Oxford-Pets（官方 test.txt）",
                targetSampleCount = 3_669,
                preparationMessage = "等待 Oxford-Pets 图像包、CLIP 和 VLM。",
            )
        )

        assertEquals("等待资源", model.statusLabel)
        assertEquals("等待数据与模型", model.primaryActionLabel)
        assertFalse(model.canStart)
        assertTrue(model.statusDetail.contains("Oxford-Pets"))
    }

    @Test
    fun `completed state with missing correct count remains waiting and cannot export`() {
        val model = G2dValidationPresenter.present(
            G2dValidationInput(
                state = G2dValidationRunState.COMPLETED,
                datasetName = "DTD",
                targetSampleCount = 100,
                measurements = listOf(
                    G2dValidationMeasurement(
                        experiment = G2dValidationExperiment.CLIP_ONLY,
                        evaluatedSamples = 100,
                        p50LatencyMs = 8.0,
                    )
                ),
            )
        )

        val clip = model.experiments.first {
            it.experiment == G2dValidationExperiment.CLIP_ONLY
        }
        assertEquals(G2dValidationPresenter.WAITING_FOR_MEASUREMENT, clip.accuracyLabel)
        assertEquals("8.0 ms", clip.p50LatencyLabel)
        assertFalse(model.canExport)
    }

    @Test
    fun `invalid measurements and progress are rejected before presentation`() {
        assertThrows(IllegalArgumentException::class.java) {
            G2dValidationMeasurement(
                experiment = G2dValidationExperiment.CLIP_ONLY,
                evaluatedSamples = 10,
                correctSamples = 11,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            G2dValidationMeasurement(
                experiment = G2dValidationExperiment.G2D_ONE_THETA,
                evaluatedSamples = 10,
                correctSamples = 8,
                routeCounts = G2dValidationRouteCounts(5, 4, 1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            G2dValidationMeasurement(
                experiment = G2dValidationExperiment.G2D_TWO_THETA,
                p50LatencyMs = 30.0,
                p95LatencyMs = 20.0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            G2dValidationMeasurement(
                experiment = G2dValidationExperiment.CLIP_ONLY,
                agentFallbackCount = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            G2dValidationInput(completedWorkItems = 11, totalWorkItems = 10)
        }
        val duplicate = measurement(G2dValidationExperiment.CLIP_ONLY, correct = 80)
        assertThrows(IllegalArgumentException::class.java) {
            G2dValidationInput(measurements = listOf(duplicate, duplicate))
        }
    }

    @Test
    fun `trace adapter aggregates real engine routes and nearest-rank latency`() {
        val traces = listOf(
            trace(G2dVariant.TWO_THRESHOLD, G2dRoute.A, 10.0),
            trace(G2dVariant.TWO_THRESHOLD, G2dRoute.B, 20.0),
            trace(G2dVariant.TWO_THRESHOLD, G2dRoute.C, 30.0),
            trace(G2dVariant.TWO_THRESHOLD, G2dRoute.C, 40.0),
            trace(G2dVariant.TWO_THRESHOLD, G2dRoute.C, 100.0),
        )

        val measurement = G2dValidationTraceAdapter.aggregate(
            experiment = G2dValidationExperiment.G2D_TWO_THETA,
            traces = traces,
            correctSamples = 4,
            peakMemoryMb = 620.0,
            backend = "ONNX Runtime CPU + llama.cpp CPU",
            quantization = "FP16 + Q4_K_M",
        )
        val model = G2dValidationPresenter.present(
            completedInput(measurement)
        ).experiments.first {
            it.experiment == G2dValidationExperiment.G2D_TWO_THETA
        }

        assertEquals(G2dValidationRouteCounts(1, 1, 3), measurement.routeCounts)
        assertEquals(30.0, measurement.p50LatencyMs!!, 0.0)
        assertEquals(100.0, measurement.p95LatencyMs!!, 0.0)
        assertEquals("80.00%", model.accuracyLabel)
        assertEquals(listOf("20.00%", "20.00%", "60.00%"), listOf(
            model.routeALabel,
            model.routeBLabel,
            model.routeCLabel,
        ))
    }

    @Test
    fun `trace adapter rejects baseline targets and mixed G2D variants`() {
        assertThrows(IllegalArgumentException::class.java) {
            G2dValidationTraceAdapter.aggregate(
                experiment = G2dValidationExperiment.CLIP_ONLY,
                traces = listOf(trace(G2dVariant.ONE_THRESHOLD, G2dRoute.A, 10.0)),
                correctSamples = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            G2dValidationTraceAdapter.aggregate(
                experiment = G2dValidationExperiment.G2D_TWO_THETA,
                traces = listOf(
                    trace(G2dVariant.TWO_THRESHOLD, G2dRoute.A, 10.0),
                    trace(G2dVariant.ONE_THRESHOLD, G2dRoute.B, 20.0),
                ),
                correctSamples = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            G2dValidationTraceAdapter.aggregate(
                experiment = G2dValidationExperiment.G2D_TWO_THETA,
                traces = listOf(trace(G2dVariant.TWO_THRESHOLD, G2dRoute.A, -1.0)),
                correctSamples = 1,
            )
        }
    }

    @Test
    fun `agentic trace adapter reports tools fallbacks and total router latency`() {
        val results = listOf(
            agenticResult(G2dBranchTool.CLIP_DIRECT, G2dRoute.A, 10.0, 5.0),
            agenticResult(G2dBranchTool.VLM_FULL_LABELS, G2dRoute.B, 20.0, 10.0),
            agenticResult(G2dBranchTool.CANDIDATE_VERIFIER, G2dRoute.C, 30.0, 15.0),
            agenticResult(
                G2dBranchTool.CANDIDATE_VERIFIER_NO_PROB,
                G2dRoute.C,
                40.0,
                20.0,
                fallback = true,
            ),
            agenticResult(G2dBranchTool.CANDIDATE_VERIFIER_NO_PROB, G2dRoute.C, 100.0, 25.0),
        )

        val measurement = G2dValidationTraceAdapter.aggregateAgentic(
            results = results,
            correctSamples = 4,
            backend = "ONNX Runtime + local Qwen router",
            quantization = "INT8 + Q4_K_M",
        )
        val card = G2dValidationPresenter.present(completedInput(measurement)).experiments.first {
            it.experiment == G2dValidationExperiment.AGENTIC_G2D
        }

        assertEquals(G2dValidationRouteCounts(1, 1, 3), measurement.routeCounts)
        assertEquals(45.0, measurement.p50LatencyMs!!, 0.0)
        assertEquals(125.0, measurement.p95LatencyMs!!, 0.0)
        assertEquals(15.0, measurement.routerP50LatencyMs!!, 0.0)
        assertEquals(1, measurement.agentFallbackCount)
        assertEquals(
            "CLIP 1 · VLM 1 · 候选 1 · 无概率 2",
            card.agentToolCallsLabel,
        )
        assertEquals("1 次 · 20.00%", card.agentFallbackLabel)
        assertEquals("15.0 ms", card.routerP50LatencyLabel)
    }

    private fun completedInput(
        vararg measurements: G2dValidationMeasurement,
    ): G2dValidationInput = G2dValidationInput(
        state = G2dValidationRunState.COMPLETED,
        datasetName = "Oxford-Pets",
        targetSampleCount = 100,
        measurements = measurements.toList(),
    )

    private fun measurement(
        experiment: G2dValidationExperiment,
        correct: Int,
        routes: G2dValidationRouteCounts? = null,
    ): G2dValidationMeasurement = G2dValidationMeasurement(
        experiment = experiment,
        evaluatedSamples = 100,
        correctSamples = correct,
        routeCounts = routes,
    )

    private fun trace(
        variant: G2dVariant,
        route: G2dRoute,
        totalLatencyMs: Double,
    ): G2dInferenceTrace<String> = G2dInferenceTrace(
        variant = variant,
        route = route,
        expert = when (route) {
            G2dRoute.A -> G2dExpert.CLIP
            G2dRoute.B -> if (variant == G2dVariant.ONE_THRESHOLD) {
                G2dExpert.CANDIDATE_VERIFIER
            } else {
                G2dExpert.STANDALONE_GENERATOR
            }
            G2dRoute.C -> G2dExpert.CANDIDATE_VERIFIER
        },
        corpusSize = 3,
        highThreshold = 0.70,
        lowThreshold = 0.20.takeIf { variant == G2dVariant.TWO_THRESHOLD },
        topConfidence = 0.50,
        selectedLabel = "label-1",
        selectedConfidence = 0.50,
        entropy = 1.0,
        adaptiveK = 3,
        candidates = listOf(G2dCandidateTrace("id-1", "label-1", 1, 0.50)),
        noProb = false,
        rawOutput = null,
        projectionMethod = G2dProjectionMethod.DIRECT_ID,
        projectionSimilarity = 1.0,
        routingLatencyMs = 1.0,
        expertLatencyMs = (totalLatencyMs - 1.0).coerceAtLeast(0.0),
        totalLatencyMs = totalLatencyMs,
    )

    private fun agenticResult(
        tool: G2dBranchTool,
        route: G2dRoute,
        inferenceLatencyMs: Double,
        routerLatencyMs: Double,
        fallback: Boolean = false,
    ): G2dAgenticInferenceResult<String> = G2dAgenticInferenceResult(
        inference = G2dInferenceResult(
            prediction = "id-1",
            trace = trace(G2dVariant.AGENTIC, route, inferenceLatencyMs),
        ),
        routing = G2dAgentRoutingTrace(
            selectedTool = tool,
            reason = "test",
            agentConfidence = 0.9,
            fallbackUsed = fallback,
            fallbackReason = "invalid_tool".takeIf { fallback },
            routerLatencyMs = routerLatencyMs,
            rawResponse = "{}",
        ),
    )
}
