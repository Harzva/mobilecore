package ai.mobilecore.g2d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class G2dEngineTest {
    @Test
    fun `two-threshold high boundary is inclusive and takes route A`() {
        var generatorCalled = false
        var verifierCalled = false
        val engine = G2dEngine<String>(
            G2dConfig.twoThreshold(lowThreshold = 0.20, highThreshold = 0.70)
        )

        val result = engine.infer(
            clipRanking = candidates(0.70, 0.20, 0.10),
            experts = G2dExperts(
                standaloneGenerator = G2dStandaloneGenerator {
                    generatorCalled = true
                    G2dGeneratedOutput.fromId("id-2")
                },
                candidateVerifier = G2dCandidateVerifier {
                    verifierCalled = true
                    G2dGeneratedOutput.fromId("id-2")
                },
            ),
        )

        assertEquals("id-1", result.prediction)
        assertEquals(G2dRoute.A, result.trace.route)
        assertEquals(G2dExpert.CLIP, result.trace.expert)
        assertEquals(G2dProjectionMethod.CLIP_TOP_ONE, result.trace.projectionMethod)
        assertFalse(generatorCalled)
        assertFalse(verifierCalled)
    }

    @Test
    fun `two-threshold low boundary is inclusive and takes generator route B`() {
        val engine = G2dEngine<String>(
            G2dConfig.twoThreshold(lowThreshold = 0.20, highThreshold = 0.70)
        )

        val result = engine.infer(
            clipRanking = candidates(0.20, 0.20, 0.20, 0.20, 0.20),
            experts = G2dExperts(
                standaloneGenerator = G2dStandaloneGenerator {
                    G2dGeneratedOutput.fromId("id-4")
                }
            ),
        )

        assertEquals("id-4", result.prediction)
        assertEquals(G2dRoute.B, result.trace.route)
        assertEquals(G2dExpert.STANDALONE_GENERATOR, result.trace.expert)
        assertEquals(G2dProjectionMethod.DIRECT_ID, result.trace.projectionMethod)
    }

    @Test
    fun `two-threshold intermediate confidence takes verifier route C`() {
        val engine = G2dEngine<String>(
            G2dConfig.twoThreshold(lowThreshold = 0.20, highThreshold = 0.70)
        )

        val result = engine.infer(
            clipRanking = candidates(0.50, 0.30, 0.20),
            experts = G2dExperts(
                candidateVerifier = G2dCandidateVerifier {
                    G2dGeneratedOutput.fromId("id-2")
                }
            ),
        )

        assertEquals("id-2", result.prediction)
        assertEquals(G2dRoute.C, result.trace.route)
        assertEquals(G2dExpert.CANDIDATE_VERIFIER, result.trace.expert)
        assertEquals(0.30, result.trace.selectedConfidence, 1e-9)
    }

    @Test
    fun `one-threshold non-high sample uses verifier as paper route B`() {
        var verifierInput: G2dVerifierInput<String>? = null
        val engine = G2dEngine<String>(G2dConfig.oneThreshold(highThreshold = 0.70))

        val result = engine.infer(
            clipRanking = candidates(0.60, 0.25, 0.15),
            experts = G2dExperts(
                candidateVerifier = G2dCandidateVerifier { input ->
                    verifierInput = input
                    G2dGeneratedOutput.fromId("id-2")
                }
            ),
        )

        assertEquals(G2dVariant.ONE_THRESHOLD, result.trace.variant)
        assertEquals(G2dRoute.B, result.trace.route)
        assertEquals(G2dExpert.CANDIDATE_VERIFIER, result.trace.expert)
        assertNotNull(verifierInput)
    }

    @Test
    fun `touching thresholds prefer high-confidence route A`() {
        val engine = G2dEngine<String>(
            G2dConfig.twoThreshold(lowThreshold = 0.50, highThreshold = 0.50)
        )

        val result = engine.infer(candidates(0.50, 0.30, 0.20))

        assertEquals(G2dRoute.A, result.trace.route)
        assertEquals("id-1", result.prediction)
    }

    @Test
    fun `entropy adaptive K honors low high and interpolation boundaries`() {
        val engine = G2dEngine<String>()

        assertEquals(3, engine.adaptiveCandidateCount(entropy = 0.0, availableCandidates = 20))
        assertEquals(3, engine.adaptiveCandidateCount(entropy = 0.5, availableCandidates = 20))
        assertEquals(7, engine.adaptiveCandidateCount(entropy = 1.25, availableCandidates = 20))
        assertEquals(10, engine.adaptiveCandidateCount(entropy = 2.0, availableCandidates = 20))
        assertEquals(10, engine.adaptiveCandidateCount(entropy = 3.0, availableCandidates = 20))
    }

    @Test
    fun `adaptive K never invents candidates when corpus is smaller than K min`() {
        val engine = G2dEngine<String>()

        assertEquals(2, engine.adaptiveCandidateCount(entropy = 0.0, availableCandidates = 2))
    }

    @Test
    fun `entropy is computed from normalized weights and trace keeps shortlist`() {
        val engine = G2dEngine<String>(G2dConfig(highThreshold = 0.90))

        val result = engine.infer(
            clipRanking = listOf(
                G2dClipCandidate("a", 4.0),
                G2dClipCandidate("b", 3.0),
                G2dClipCandidate("c", 2.0),
                G2dClipCandidate("d", 1.0),
            ),
            experts = G2dExperts(
                candidateVerifier = G2dCandidateVerifier {
                    G2dGeneratedOutput.fromId("a")
                }
            ),
        )

        assertEquals(0.40, result.trace.topConfidence, 1e-9)
        assertEquals(1.279854, result.trace.entropy, 1e-6)
        assertEquals(4, result.trace.candidates.size)
        assertEquals(1.0, result.trace.candidates.sumOf { it.probability }, 1e-9)
    }

    @Test
    fun `No-Prob keeps ranking in trace but omits priors from verifier input`() {
        var captured: G2dVerifierInput<String>? = null
        val engine = G2dEngine<String>(
            G2dConfig.twoThreshold(
                lowThreshold = 0.20,
                highThreshold = 0.70,
                noProb = true,
            )
        )

        val result = engine.infer(
            clipRanking = candidates(0.40, 0.35, 0.25),
            experts = G2dExperts(
                candidateVerifier = G2dCandidateVerifier { input ->
                    captured = input
                    G2dGeneratedOutput.fromId("id-2")
                }
            ),
        )

        assertTrue(result.trace.noProb)
        assertTrue(captured!!.noProb)
        assertTrue(captured!!.candidates.all { it.probability == null })
        assertTrue(result.trace.candidates.all { it.probability > 0.0 })
    }

    @Test
    fun `default verifier input includes normalized CLIP probabilities`() {
        var captured: G2dVerifierInput<String>? = null
        val engine = G2dEngine<String>(
            G2dConfig.twoThreshold(lowThreshold = 0.10, highThreshold = 0.80)
        )

        engine.infer(
            clipRanking = candidates(4.0, 3.0, 3.0),
            experts = G2dExperts(
                candidateVerifier = G2dCandidateVerifier { input ->
                    captured = input
                    G2dGeneratedOutput.fromId("id-1")
                }
            ),
        )

        assertFalse(captured!!.noProb)
        assertTrue(captured!!.candidates.all { it.probability != null })
        assertEquals(1.0, captured!!.candidates.sumOf { it.probability!! }, 1e-9)
    }

    @Test
    fun `numbered punctuation and separators project to canonical candidate`() {
        val engine = G2dEngine<String>(
            G2dConfig.twoThreshold(lowThreshold = 0.10, highThreshold = 0.80)
        )
        val ranking = listOf(
            G2dClipCandidate("dog", 0.50, "golden-retriever"),
            G2dClipCandidate("cat", 0.30, "tabby cat"),
            G2dClipCandidate("bird", 0.20, "blue jay"),
        )

        val result = engine.infer(
            clipRanking = ranking,
            experts = G2dExperts(
                candidateVerifier = G2dCandidateVerifier {
                    G2dGeneratedOutput.fromText("1. Golden_retriever!!!")
                }
            ),
        )

        assertEquals("dog", result.prediction)
        assertEquals(G2dProjectionMethod.NORMALIZED_LABEL, result.trace.projectionMethod)
        assertEquals("1. Golden_retriever!!!", result.trace.rawOutput)
    }

    @Test
    fun `minor label typo uses fuzzy projection inside shortlist`() {
        val engine = G2dEngine<String>(
            G2dConfig.twoThreshold(lowThreshold = 0.10, highThreshold = 0.80)
        )
        val ranking = listOf(
            G2dClipCandidate("dog", 0.50, "golden retriever"),
            G2dClipCandidate("cat", 0.30, "tabby cat"),
            G2dClipCandidate("bird", 0.20, "blue jay"),
        )

        val result = engine.infer(
            clipRanking = ranking,
            experts = G2dExperts(
                candidateVerifier = G2dCandidateVerifier {
                    G2dGeneratedOutput.fromText("golden retrievr")
                }
            ),
        )

        assertEquals("dog", result.prediction)
        assertEquals(G2dProjectionMethod.FUZZY_LABEL, result.trace.projectionMethod)
        assertTrue(result.trace.projectionSimilarity!! >= 0.85)
    }

    @Test
    fun `thinking envelope projects the final category rather than falling back to CLIP`() {
        val engine = G2dEngine<String>(
            G2dConfig.twoThreshold(lowThreshold = 0.10, highThreshold = 0.80)
        )
        val result = engine.inferWithExpert(
            clipRanking = listOf(
                G2dClipCandidate("bulldog", 0.70, "american_bulldog"),
                G2dClipCandidate("pomeranian", 0.20, "pomeranian"),
                G2dClipCandidate("pug", 0.10, "pug"),
            ),
            expert = G2dExpert.STANDALONE_GENERATOR,
            experts = G2dExperts(
                standaloneGenerator = G2dStandaloneGenerator {
                    G2dGeneratedOutput.fromText("<think>visual notes</think>\n\nCategory: pomeranian")
                }
            ),
        )

        assertEquals("pomeranian", result.prediction)
        assertEquals(G2dProjectionMethod.EXACT_LABEL, result.trace.projectionMethod)
    }

    @Test
    fun `invalid verifier output is projected to top candidate and never escapes shortlist`() {
        val engine = G2dEngine<String>(
            G2dConfig.twoThreshold(lowThreshold = 0.10, highThreshold = 0.80)
        )

        val result = engine.infer(
            clipRanking = candidates(0.50, 0.30, 0.20),
            experts = G2dExperts(
                candidateVerifier = G2dCandidateVerifier {
                    G2dGeneratedOutput(id = "outside-shortlist", rawText = "not a valid candidate")
                }
            ),
        )

        assertEquals("id-1", result.prediction)
        assertEquals(
            G2dProjectionMethod.FALLBACK_TO_CLIP_TOP_ONE,
            result.trace.projectionMethod,
        )
        assertTrue(result.trace.candidates.any { it.id == result.prediction })
    }

    @Test
    fun `valid corpus ID outside adaptive shortlist is still rejected by verifier projection`() {
        val engine = G2dEngine<String>(
            G2dConfig.twoThreshold(lowThreshold = 0.10, highThreshold = 0.80)
        )
        val ranking = buildList {
            add(G2dClipCandidate("id-1", 0.50, "label-1"))
            repeat(10) { index ->
                add(
                    G2dClipCandidate(
                        id = "id-${index + 2}",
                        probability = 0.05,
                        label = "label-${index + 2}",
                    )
                )
            }
        }

        val result = engine.infer(
            clipRanking = ranking,
            experts = G2dExperts(
                candidateVerifier = G2dCandidateVerifier {
                    G2dGeneratedOutput.fromId("id-11")
                }
            ),
        )

        assertTrue(result.trace.adaptiveK < ranking.size)
        assertFalse(result.trace.candidates.any { it.id == "id-11" })
        assertEquals("id-1", result.prediction)
        assertEquals(
            G2dProjectionMethod.FALLBACK_TO_CLIP_TOP_ONE,
            result.trace.projectionMethod,
        )
    }

    @Test
    fun `generic photo IDs support album text retrieval without classification assumptions`() {
        data class PhotoId(val value: String)

        val beach = PhotoId("photo-2026-001")
        val dog = PhotoId("photo-2026-002")
        val meeting = PhotoId("photo-2026-003")
        var verifierCandidates: List<PhotoId> = emptyList()
        val engine = G2dEngine<PhotoId>(
            G2dConfig.twoThreshold(lowThreshold = 0.10, highThreshold = 0.80)
        )

        val result = engine.infer(
            clipRanking = listOf(
                G2dClipCandidate(beach, 0.45, beach.value),
                G2dClipCandidate(dog, 0.35, dog.value),
                G2dClipCandidate(meeting, 0.20, meeting.value),
            ),
            experts = G2dExperts(
                candidateVerifier = G2dCandidateVerifier { input ->
                    verifierCandidates = input.candidates.map { it.id }
                    G2dGeneratedOutput.fromId(dog)
                }
            ),
        )

        assertEquals(dog, result.prediction)
        assertEquals(listOf(beach, dog, meeting), verifierCandidates)
        assertEquals(G2dRoute.C, result.trace.route)
        assertEquals(G2dExpert.CANDIDATE_VERIFIER, result.trace.expert)
    }

    @Test
    fun `trace records routing expert and end-to-end latency`() {
        var nanos = 0L
        val clock = G2dNanoClock {
            nanos += 1_000_000L
            nanos
        }
        val engine = G2dEngine<String>(
            config = G2dConfig.twoThreshold(lowThreshold = 0.10, highThreshold = 0.80),
            clock = clock,
        )

        val result = engine.infer(
            clipRanking = candidates(0.50, 0.30, 0.20),
            experts = G2dExperts(
                candidateVerifier = G2dCandidateVerifier {
                    G2dGeneratedOutput.fromId("id-2")
                }
            ),
        )

        assertEquals(1.0, result.trace.routingLatencyMs, 0.0)
        assertEquals(1.0, result.trace.expertLatencyMs, 0.0)
        assertEquals(4.0, result.trace.totalLatencyMs, 0.0)
        assertEquals(0.50, result.trace.topConfidence, 1e-9)
        assertEquals("label-2", result.trace.selectedLabel)
        assertEquals(0.30, result.trace.selectedConfidence, 1e-9)
        assertEquals(3, result.trace.corpusSize)
        assertEquals(0.80, result.trace.highThreshold, 0.0)
        assertEquals(0.10, result.trace.lowThreshold!!, 0.0)
        assertNotNull(result.trace.candidates)
    }

    @Test
    fun `missing selected expert fails only when that route is dispatched`() {
        val routeA = G2dEngine<String>(
            G2dConfig.twoThreshold(lowThreshold = 0.10, highThreshold = 0.70)
        )
        assertEquals("id-1", routeA.infer(candidates(0.80, 0.10, 0.10)).prediction)

        val routeC = G2dEngine<String>(
            G2dConfig.twoThreshold(lowThreshold = 0.10, highThreshold = 0.80)
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            routeC.infer(candidates(0.50, 0.30, 0.20))
        }
        assertTrue(error.message.orEmpty().contains("candidate verifier"))
    }

    @Test
    fun `invalid configuration and ambiguous candidates are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            G2dConfig(lowThreshold = 0.80, highThreshold = 0.70)
        }
        val engine = G2dEngine<String>()
        assertThrows(IllegalArgumentException::class.java) {
            engine.infer(
                listOf(
                    G2dClipCandidate("a", 0.50, "red_car"),
                    G2dClipCandidate("b", 0.50, "red-car"),
                )
            )
        }
    }

    @Test
    fun `uniform fallback handles an all-zero CLIP distribution`() {
        val engine = G2dEngine<String>(
            G2dConfig.twoThreshold(lowThreshold = 0.34, highThreshold = 0.80)
        )

        val result = engine.infer(
            clipRanking = candidates(0.0, 0.0, 0.0),
            experts = G2dExperts(
                standaloneGenerator = G2dStandaloneGenerator {
                    G2dGeneratedOutput.fromText("label-2")
                }
            ),
        )

        assertEquals(G2dRoute.B, result.trace.route)
        assertEquals("id-2", result.prediction)
        assertEquals(1.0 / 3.0, result.trace.topConfidence, 1e-9)
        assertEquals(G2dProjectionMethod.EXACT_LABEL, result.trace.projectionMethod)
        assertEquals(1.0, result.trace.projectionSimilarity!!, 0.0)
    }

    private fun candidates(vararg probabilities: Double): List<G2dClipCandidate<String>> =
        probabilities.mapIndexed { index, probability ->
            G2dClipCandidate(
                id = "id-${index + 1}",
                probability = probability,
                label = "label-${index + 1}",
            )
        }
}
