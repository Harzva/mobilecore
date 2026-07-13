package ai.mobilecore.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkProfileTest {
    @Test
    fun profilesFreezeRc2Workloads() {
        assertEquals(1, BenchmarkProfile.QUICK.warmupRuns)
        assertEquals(1, BenchmarkProfile.QUICK.measuredRuns)
        assertEquals(64, BenchmarkProfile.QUICK.outputTokens)
        assertFalse(BenchmarkProfile.QUICK.officialLeaderboardEligible)

        assertEquals(1, BenchmarkProfile.STANDARD.warmupRuns)
        assertEquals(3, BenchmarkProfile.STANDARD.measuredRuns)
        assertEquals(128, BenchmarkProfile.STANDARD.outputTokens)
        assertTrue(BenchmarkProfile.STANDARD.officialLeaderboardEligible)

        assertEquals(1, BenchmarkProfile.STRESS.warmupRuns)
        assertEquals(10, BenchmarkProfile.STRESS.measuredRuns)
        assertEquals(128, BenchmarkProfile.STRESS.outputTokens)
        assertFalse(BenchmarkProfile.STRESS.officialLeaderboardEligible)
    }

    @Test
    fun specUsesFrozenV2IdentityAndClampsThreads() {
        val spec = BenchmarkSpecV2.forProfile(BenchmarkProfile.STANDARD, threads = 0)

        assertEquals("tuima-llm-benchmark-v2", spec.id)
        assertEquals(2, spec.version)
        assertEquals("tuima-score-v2", spec.scoreAlgorithmId)
        assertEquals(1, spec.threads)
        assertEquals(2048, spec.contextLength)
        assertEquals(0.2f, spec.temperature)
    }
}
