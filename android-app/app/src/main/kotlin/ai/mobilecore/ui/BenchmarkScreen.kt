package ai.mobilecore.ui

import ai.mobilecore.benchmark.BenchmarkProfile
import ai.mobilecore.benchmark.BenchmarkUiState

data class BenchmarkLiveSnapshot(
    val batteryPercent: Int? = null,
    val temperatureCelsius: Double? = null,
    val decodeTokensPerSecond: Double? = null,
    val elapsedMs: Long = 0L
)

data class BenchmarkScreenUiModel(
    val title: String,
    val message: String,
    val progressPercent: Int,
    val phaseLabel: String,
    val remainingLabel: String,
    val isRunning: Boolean
)

object BenchmarkScreenPresenter {
    fun present(
        state: BenchmarkUiState,
        live: BenchmarkLiveSnapshot,
        modelDisplayName: (String) -> String = { it }
    ): BenchmarkScreenUiModel {
        val progress = progress(state)
        return BenchmarkScreenUiModel(
            title = title(state),
            message = message(state, modelDisplayName),
            progressPercent = progress,
            phaseLabel = phase(state),
            remainingLabel = estimateRemaining(state, progress, live.elapsedMs),
            isRunning = state.isRunning
        )
    }

    fun progress(state: BenchmarkUiState): Int = when (state) {
        is BenchmarkUiState.Checking -> 8
        is BenchmarkUiState.LoadingModel -> 18
        is BenchmarkUiState.WarmingUp -> 20 + (state.current * 10 / state.total.coerceAtLeast(1))
        is BenchmarkUiState.Measuring -> 30 + (state.current * 58 / state.total.coerceAtLeast(1))
        is BenchmarkUiState.Cooling -> 90
        is BenchmarkUiState.Cancelling -> 92
        is BenchmarkUiState.Completed -> 100
        else -> 0
    }

    private fun title(state: BenchmarkUiState): String = when (state) {
        BenchmarkUiState.Ready -> "准备就绪"
        is BenchmarkUiState.NeedsModel -> "还差一个标准模型"
        is BenchmarkUiState.Checking -> "正在检查设备"
        is BenchmarkUiState.LoadingModel -> "正在准备模型"
        is BenchmarkUiState.WarmingUp -> "正在预热"
        is BenchmarkUiState.Measuring -> "正在计分"
        is BenchmarkUiState.Cooling -> "正在等待设备冷却"
        is BenchmarkUiState.Cancelling -> "正在安全取消"
        is BenchmarkUiState.Blocked -> "暂时不能开始"
        is BenchmarkUiState.Completed -> "跑分完成"
        is BenchmarkUiState.Failed -> "本次跑分未完成"
        BenchmarkUiState.Cancelled -> "跑分已取消"
    }

    private fun message(state: BenchmarkUiState, modelDisplayName: (String) -> String): String = when (state) {
        BenchmarkUiState.Ready -> "标准模型已就绪。测试时会自动启动本机服务。"
        is BenchmarkUiState.NeedsModel -> "需要 ${state.fileName}，下载完成后即可开始。"
        is BenchmarkUiState.Checking -> "正在校验电量、温度、存储和模型完整性。"
        is BenchmarkUiState.LoadingModel -> "正在加载 ${modelDisplayName(state.modelName.substringBeforeLast('.'))}。"
        is BenchmarkUiState.WarmingUp -> "预热 ${state.current} / ${state.total}，这部分不计分。"
        is BenchmarkUiState.Measuring -> "计分 ${state.current} / ${state.total}，请保持应用在前台。"
        is BenchmarkUiState.Cooling -> "剩余约 ${state.secondsRemaining} 秒，避免温度影响下一轮。"
        is BenchmarkUiState.Cancelling -> "正在停止当前推理并保存诊断信息。"
        is BenchmarkUiState.Blocked -> "处理下面的项目后，点击重新检测。"
        is BenchmarkUiState.Completed -> "${formatScore(state.headlineScore)} TuiMa · 标准分 ${state.canonicalScore} / 1000"
        is BenchmarkUiState.Failed -> state.message
        BenchmarkUiState.Cancelled -> "没有生成成绩，你可以随时重新开始。"
    }

    private fun phase(state: BenchmarkUiState): String = when (state) {
        is BenchmarkUiState.Checking -> "1/5 设备检查"
        is BenchmarkUiState.LoadingModel -> "2/5 加载模型"
        is BenchmarkUiState.WarmingUp -> "3/5 模型预热"
        is BenchmarkUiState.Measuring -> "4/5 正式计分"
        is BenchmarkUiState.Cooling -> "4/5 散热等待"
        is BenchmarkUiState.Completed -> "5/5 生成结果"
        else -> "等待开始"
    }

    private fun estimateRemaining(state: BenchmarkUiState, progress: Int, elapsedMs: Long): String {
        if (!state.isRunning) return if (state is BenchmarkUiState.Completed) "已完成" else "尚未开始"
        if (state is BenchmarkUiState.Cooling) return "约 ${state.secondsRemaining} 秒"
        if (elapsedMs <= 0L || progress <= 0) return "正在估算"
        val remaining = (elapsedMs.toDouble() * (100 - progress).toDouble() / progress.toDouble()).toLong()
        return formatRemainingDuration(remaining.coerceAtLeast(1_000L))
    }

    private fun formatScore(value: Int): String = "%,d".format(value)
}
