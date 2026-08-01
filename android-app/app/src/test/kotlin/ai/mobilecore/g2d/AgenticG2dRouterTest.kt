package ai.mobilecore.g2d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgenticG2dRouterTest {
    @Test
    fun `registered verifier tool executes candidate branch instead of threshold rule`() {
        var verifierCalled = false
        val engine = AgenticG2dEngine<String>(
            routeAgent = G2dRouteAgent {
                G2dAgentToolCall("candidate_verifier", "fine-grained ambiguity", 0.91)
            },
        )

        val result = engine.infer(
            datasetName = "DTD",
            sampleId = "dtd-0001",
            imageWidth = 224,
            imageHeight = 224,
            clipRanking = candidates(0.95, 0.03, 0.02),
            experts = G2dExperts(
                candidateVerifier = G2dCandidateVerifier {
                    verifierCalled = true
                    G2dGeneratedOutput.fromId("id-2")
                },
            ),
        )

        assertTrue(verifierCalled)
        assertEquals("id-2", result.inference.prediction)
        assertEquals(G2dVariant.AGENTIC, result.inference.trace.variant)
        assertEquals(G2dRoute.C, result.inference.trace.route)
        assertEquals(G2dBranchTool.CANDIDATE_VERIFIER, result.routing.selectedTool)
        assertFalse(result.routing.fallbackUsed)
    }

    @Test
    fun `no-prob verifier is a separate tool and strips priors`() {
        var captured: G2dVerifierInput<String>? = null
        val engine = AgenticG2dEngine<String>(
            routeAgent = G2dRouteAgent {
                G2dAgentToolCall("candidate_verifier_no_prob", "texture prior is unreliable", 0.88)
            },
        )

        val result = engine.infer(
            datasetName = "DTD",
            sampleId = "dtd-0002",
            imageWidth = 224,
            imageHeight = 224,
            clipRanking = candidates(0.50, 0.30, 0.20),
            experts = G2dExperts(
                candidateVerifier = G2dCandidateVerifier { input ->
                    captured = input
                    G2dGeneratedOutput.fromId("id-1")
                },
            ),
        )

        assertTrue(captured!!.noProb)
        assertTrue(captured!!.candidates.all { it.probability == null })
        assertTrue(result.inference.trace.noProb)
        assertEquals(G2dBranchTool.CANDIDATE_VERIFIER_NO_PROB, result.routing.selectedTool)
    }

    @Test
    fun `unavailable hallucinated tool is rejected and uses deterministic fallback`() {
        val engine = AgenticG2dEngine<String>(
            routeAgent = G2dRouteAgent {
                G2dAgentToolCall("oracle_ground_truth", "cheat", 1.0)
            },
        )

        val result = engine.infer(
            datasetName = "CIFAR-10",
            sampleId = "cifar10-1",
            imageWidth = 32,
            imageHeight = 32,
            clipRanking = candidates(0.95, 0.03, 0.02),
            experts = G2dExperts(),
        )

        assertEquals("id-1", result.inference.prediction)
        assertEquals(G2dBranchTool.CLIP_DIRECT, result.routing.selectedTool)
        assertTrue(result.routing.fallbackUsed)
        assertTrue(result.routing.fallbackReason!!.contains("oracle_ground_truth"))
    }

    @Test
    fun `unavailable generator tool cannot escape registry`() {
        val engine = AgenticG2dEngine<String>(
            routeAgent = G2dRouteAgent {
                G2dAgentToolCall("vlm_full_labels", "low prior", 0.8)
            },
        )

        val result = engine.infer(
            datasetName = "CIFAR-10",
            sampleId = "cifar10-2",
            imageWidth = 32,
            imageHeight = 32,
            clipRanking = candidates(0.80, 0.10, 0.10),
            experts = G2dExperts(),
        )

        assertTrue(result.routing.fallbackUsed)
        assertEquals(G2dBranchTool.CLIP_DIRECT, result.routing.selectedTool)
    }

    @Test
    fun `json agent emits a closed tool call and prompt never contains ground truth`() {
        var prompt = ""
        val agent = JsonToolCallingG2dAgent(
            model = G2dRouterModel { generatedPrompt, _ ->
                prompt = generatedPrompt
                "```json\n{\"tool\":\"clip_direct\",\"reason\":\"large margin\",\"confidence\":0.97}\n```"
            },
        )
        val request = G2dAgentRoutingRequest(
            observation = G2dRoutingObservation(
                datasetName = "CIFAR-10",
                sampleId = "test-42",
                imageWidth = 32,
                imageHeight = 32,
                candidates = listOf(
                    G2dRoutingCandidate("cat", 1, 0.8),
                    G2dRoutingCandidate("dog", 2, 0.2),
                ),
                entropy = 0.5,
                adaptiveK = 2,
            ),
            tools = G2dToolRegistry.default().availableTools(G2dExperts<String>()),
        )

        val call = agent.selectTool(request)

        assertEquals("clip_direct", call.toolName)
        assertEquals(0.97, call.confidence, 1e-9)
        assertTrue(prompt.contains("Registered tools"))
        assertTrue(prompt.contains("Do not predict the class label"))
        assertFalse(prompt.contains("ground_truth"))
        assertFalse(prompt.contains("correct_label"))
    }

    private fun candidates(vararg probabilities: Double): List<G2dClipCandidate<String>> =
        probabilities.mapIndexed { index, probability ->
            G2dClipCandidate("id-${index + 1}", probability, "label-${index + 1}")
        }
}
