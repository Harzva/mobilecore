package ai.mobilecore.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultimodalQualitySanityTest {
    @Test
    fun acceptsDistinctSemanticallyRelevantCrossModalOutputs() {
        val report = MultimodalQualitySanity.evaluate(
            listOf(
                MultimodalSanityObservation("text_math", SanityModality.TEXT, "4", setOf("4")),
                MultimodalSanityObservation("image_color", SanityModality.IMAGE, "red square", setOf("red")),
                MultimodalSanityObservation("audio_word", SanityModality.AUDIO, "hello", setOf("hello")),
            ),
        )

        assertTrue(report.passed)
        assertEquals(3, report.distinctOutputCount)
        assertFalse(report.toJson().toString().contains("red square"))
        assertFalse(report.toJson().toString().contains("hello"))
    }

    @Test
    fun rejectsFixedFallbackAndModeCollapse() {
        val fixed = "Native stub response: model=local"
        val report = MultimodalQualitySanity.evaluate(
            listOf(
                MultimodalSanityObservation("text", SanityModality.TEXT, fixed),
                MultimodalSanityObservation("image", SanityModality.IMAGE, fixed),
                MultimodalSanityObservation("audio", SanityModality.AUDIO, fixed),
            ),
        )

        assertFalse(report.passed)
        assertTrue(report.failures.contains("set:mode_collapse"))
        assertTrue(report.failures.count { it.endsWith("fallback_output") } == 3)
    }

    @Test
    fun requiresTextImageAndAudioCoverage() {
        val report = MultimodalQualitySanity.evaluate(
            listOf(MultimodalSanityObservation("text", SanityModality.TEXT, "ok")),
        )

        assertFalse(report.passed)
        assertTrue(report.failures.contains("set:modality_coverage_incomplete"))
    }
}
