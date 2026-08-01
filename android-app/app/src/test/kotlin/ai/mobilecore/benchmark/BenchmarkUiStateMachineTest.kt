package ai.mobilecore.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkUiStateMachineTest {
    @Test
    fun `standard run moves through explicit lifecycle without touching scoring`() {
        val machine = BenchmarkUiStateMachine()

        assertEquals(BenchmarkUiState.Checking(BenchmarkProfile.STANDARD), machine.dispatch(BenchmarkUiEvent.Started(BenchmarkProfile.STANDARD)))
        assertEquals(
            BenchmarkUiState.LoadingModel(BenchmarkProfile.STANDARD, "qwen.gguf"),
            machine.dispatch(BenchmarkUiEvent.ModelLoading(BenchmarkProfile.STANDARD, "qwen.gguf"))
        )
        assertEquals(
            BenchmarkUiState.WarmingUp(BenchmarkProfile.STANDARD, 1, 1),
            machine.dispatch(BenchmarkUiEvent.WarmupProgress(BenchmarkProfile.STANDARD, 1, 1))
        )
        assertEquals(
            BenchmarkUiState.Measuring(BenchmarkProfile.STANDARD, 2, 3),
            machine.dispatch(BenchmarkUiEvent.MeasurementProgress(BenchmarkProfile.STANDARD, 2, 3))
        )
        assertEquals(
            BenchmarkUiState.Completed(BenchmarkProfile.STANDARD, 720, 612),
            machine.dispatch(BenchmarkUiEvent.Finished(BenchmarkProfile.STANDARD, 720, 612))
        )
        assertFalse(machine.state.isRunning)
    }

    @Test
    fun `readiness does not replace an active run`() {
        val machine = BenchmarkUiStateMachine()
        machine.dispatch(BenchmarkUiEvent.Started(BenchmarkProfile.QUICK))

        machine.dispatch(BenchmarkUiEvent.ReadinessChanged("missing.gguf"))

        assertEquals(BenchmarkUiState.Checking(BenchmarkProfile.QUICK), machine.state)
        assertTrue(machine.state.isRunning)
    }

    @Test
    fun `preflight failure exposes every recovery reason`() {
        val machine = BenchmarkUiStateMachine()
        machine.dispatch(BenchmarkUiEvent.Started(BenchmarkProfile.STANDARD))

        val state = machine.dispatch(
            BenchmarkUiEvent.PreflightBlocked(
                BenchmarkProfile.STANDARD,
                listOf(BenchmarkPreflightReason.DEVICE_CHARGING, BenchmarkPreflightReason.THERMAL_TOO_HIGH)
            )
        )

        assertEquals(
            BenchmarkUiState.Blocked(
                BenchmarkProfile.STANDARD,
                listOf(BenchmarkPreflightReason.DEVICE_CHARGING, BenchmarkPreflightReason.THERMAL_TOO_HIGH)
            ),
            state
        )
        assertFalse(state.isRunning)
    }

    @Test
    fun `cancel only appears while a run is active`() {
        val machine = BenchmarkUiStateMachine()
        assertEquals(BenchmarkUiState.Ready, machine.dispatch(BenchmarkUiEvent.CancelRequested))

        machine.dispatch(BenchmarkUiEvent.Started(BenchmarkProfile.STRESS))
        val cancelling = machine.dispatch(BenchmarkUiEvent.CancelRequested)

        assertEquals(BenchmarkUiState.Cancelling(BenchmarkProfile.STRESS), cancelling)
        assertTrue(cancelling.isRunning)
    }
}
