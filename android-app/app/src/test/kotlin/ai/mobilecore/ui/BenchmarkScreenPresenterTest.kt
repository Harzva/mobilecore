package ai.mobilecore.ui

import ai.mobilecore.benchmark.BenchmarkProfile
import ai.mobilecore.benchmark.BenchmarkUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkScreenPresenterTest {
    @Test
    fun `running phases expose monotonic progress and explicit stage labels`() {
        val states = listOf(
            BenchmarkUiState.Checking(BenchmarkProfile.STANDARD),
            BenchmarkUiState.LoadingModel(BenchmarkProfile.STANDARD, "qwen.gguf"),
            BenchmarkUiState.WarmingUp(BenchmarkProfile.STANDARD, 1, 1),
            BenchmarkUiState.Measuring(BenchmarkProfile.STANDARD, 1, 3),
            BenchmarkUiState.Cooling(BenchmarkProfile.STANDARD, 3),
            BenchmarkUiState.Completed(BenchmarkProfile.STANDARD, 908_000, 908)
        )

        val models = states.map { state ->
            BenchmarkScreenPresenter.present(state, BenchmarkLiveSnapshot())
        }

        assertEquals(models.map { it.progressPercent }.sorted(), models.map { it.progressPercent })
        assertEquals("1/5 设备检查", models[0].phaseLabel)
        assertEquals("3/5 模型预热", models[2].phaseLabel)
        assertEquals("4/5 正式计分", models[3].phaseLabel)
        assertEquals("4/5 散热等待", models[4].phaseLabel)
        assertEquals("5/5 生成结果", models[5].phaseLabel)
        assertTrue(models.take(5).all { it.isRunning })
        assertFalse(models.last().isRunning)
        assertTrue(models.last().message.contains("908,000 TuiMa"))
    }

    @Test
    fun `elapsed progress produces a useful remaining estimate`() {
        val model = BenchmarkScreenPresenter.present(
            state = BenchmarkUiState.Measuring(BenchmarkProfile.STANDARD, 1, 3),
            live = BenchmarkLiveSnapshot(elapsedMs = 49_000L)
        )

        assertEquals(49, model.progressPercent)
        assertEquals("约 51 秒", model.remainingLabel)
    }
}
