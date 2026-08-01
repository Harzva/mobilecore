package ai.mobilecore.benchmark

/**
 * UI-only lifecycle for a TuiMa benchmark run.
 *
 * Scoring and report aggregation deliberately stay outside this state machine so
 * presentation changes cannot change benchmark results.
 */
sealed interface BenchmarkUiState {
    val profile: BenchmarkProfile?
        get() = null

    val isRunning: Boolean
        get() = this is Checking ||
            this is LoadingModel ||
            this is WarmingUp ||
            this is Measuring ||
            this is Cooling ||
            this is Cancelling

    data object Ready : BenchmarkUiState
    data class NeedsModel(val fileName: String) : BenchmarkUiState
    data class Checking(override val profile: BenchmarkProfile) : BenchmarkUiState
    data class LoadingModel(override val profile: BenchmarkProfile, val modelName: String) : BenchmarkUiState
    data class WarmingUp(override val profile: BenchmarkProfile, val current: Int, val total: Int) : BenchmarkUiState
    data class Measuring(override val profile: BenchmarkProfile, val current: Int, val total: Int) : BenchmarkUiState
    data class Cooling(override val profile: BenchmarkProfile, val secondsRemaining: Long) : BenchmarkUiState
    data class Cancelling(override val profile: BenchmarkProfile?) : BenchmarkUiState
    data class Blocked(
        override val profile: BenchmarkProfile,
        val reasons: List<BenchmarkPreflightReason>
    ) : BenchmarkUiState
    data class Completed(
        override val profile: BenchmarkProfile,
        val headlineScore: Int,
        val canonicalScore: Int
    ) : BenchmarkUiState
    data class Failed(
        override val profile: BenchmarkProfile?,
        val kind: BenchmarkFailureKind,
        val message: String
    ) : BenchmarkUiState
    data object Cancelled : BenchmarkUiState
}

sealed interface BenchmarkUiEvent {
    data class ReadinessChanged(val requiredModelFile: String?) : BenchmarkUiEvent
    data class Started(val profile: BenchmarkProfile) : BenchmarkUiEvent
    data class PreflightBlocked(
        val profile: BenchmarkProfile,
        val reasons: List<BenchmarkPreflightReason>
    ) : BenchmarkUiEvent
    data class ModelLoading(val profile: BenchmarkProfile, val modelName: String) : BenchmarkUiEvent
    data class WarmupProgress(val profile: BenchmarkProfile, val current: Int, val total: Int) : BenchmarkUiEvent
    data class MeasurementProgress(val profile: BenchmarkProfile, val current: Int, val total: Int) : BenchmarkUiEvent
    data class Cooldown(val profile: BenchmarkProfile, val secondsRemaining: Long) : BenchmarkUiEvent
    data object CancelRequested : BenchmarkUiEvent
    data class Finished(val profile: BenchmarkProfile, val headlineScore: Int, val canonicalScore: Int) : BenchmarkUiEvent
    data class Failed(val profile: BenchmarkProfile?, val kind: BenchmarkFailureKind, val message: String) : BenchmarkUiEvent
    data object Cancelled : BenchmarkUiEvent
}

class BenchmarkUiStateMachine(initialState: BenchmarkUiState = BenchmarkUiState.Ready) {
    var state: BenchmarkUiState = initialState
        private set

    fun dispatch(event: BenchmarkUiEvent): BenchmarkUiState {
        state = reduce(state, event)
        return state
    }

    companion object {
        fun reduce(current: BenchmarkUiState, event: BenchmarkUiEvent): BenchmarkUiState {
            return when (event) {
                is BenchmarkUiEvent.ReadinessChanged -> {
                    if (current.isRunning) current
                    else event.requiredModelFile?.let { BenchmarkUiState.NeedsModel(it) } ?: BenchmarkUiState.Ready
                }
                is BenchmarkUiEvent.Started -> {
                    if (current.isRunning) current else BenchmarkUiState.Checking(event.profile)
                }
                is BenchmarkUiEvent.PreflightBlocked -> {
                    if (current.isFor(event.profile)) BenchmarkUiState.Blocked(event.profile, event.reasons) else current
                }
                is BenchmarkUiEvent.ModelLoading -> {
                    if (current.isFor(event.profile)) BenchmarkUiState.LoadingModel(event.profile, event.modelName) else current
                }
                is BenchmarkUiEvent.WarmupProgress -> {
                    if (current.isFor(event.profile)) {
                        BenchmarkUiState.WarmingUp(event.profile, event.current.coerceAtLeast(1), event.total.coerceAtLeast(1))
                    } else current
                }
                is BenchmarkUiEvent.MeasurementProgress -> {
                    if (current.isFor(event.profile)) {
                        BenchmarkUiState.Measuring(event.profile, event.current.coerceAtLeast(1), event.total.coerceAtLeast(1))
                    } else current
                }
                is BenchmarkUiEvent.Cooldown -> {
                    if (current.isFor(event.profile)) {
                        BenchmarkUiState.Cooling(event.profile, event.secondsRemaining.coerceAtLeast(0L))
                    } else current
                }
                BenchmarkUiEvent.CancelRequested -> {
                    if (current.isRunning) BenchmarkUiState.Cancelling(current.profile) else current
                }
                is BenchmarkUiEvent.Finished -> {
                    if (current.isFor(event.profile)) {
                        BenchmarkUiState.Completed(event.profile, event.headlineScore, event.canonicalScore)
                    } else current
                }
                is BenchmarkUiEvent.Failed -> {
                    if (current.isRunning || current is BenchmarkUiState.Blocked || current is BenchmarkUiState.NeedsModel) {
                        BenchmarkUiState.Failed(event.profile, event.kind, event.message)
                    } else current
                }
                BenchmarkUiEvent.Cancelled -> {
                    if (current.isRunning || current is BenchmarkUiState.Failed) BenchmarkUiState.Cancelled else current
                }
            }
        }

        private fun BenchmarkUiState.isFor(profile: BenchmarkProfile): Boolean {
            return isRunning && this.profile == profile
        }
    }
}
