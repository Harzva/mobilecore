package ai.mobilecore.ui

import ai.mobilecore.g2d.G2dInferenceTrace
import ai.mobilecore.g2d.G2dAgenticInferenceResult
import ai.mobilecore.g2d.G2dBranchTool
import ai.mobilecore.g2d.G2dRoute
import ai.mobilecore.g2d.G2dVariant
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.ceil

enum class G2dValidationRunState {
    READY,
    RUNNING,
    COMPLETED,
    FAILED,
}

enum class G2dValidationExperiment(
    val title: String,
    val subtitle: String,
) {
    CLIP_ONLY("CLIP-only", "判别模型基线"),
    VLM_ONLY("VLM-only", "独立生成模型基线"),
    G2D_ONE_THETA("G2D 1θ", "高置信走 CLIP，其余走候选验证器"),
    G2D_TWO_THETA("G2D 2θ", "CLIP、独立生成器、候选验证器三路调度"),
    AGENTIC_G2D("Agentic G2D", "本地小模型从注册工具中选择分支（无真值）"),
}

data class G2dValidationRouteCounts(
    val routeA: Int,
    val routeB: Int,
    val routeC: Int = 0,
) {
    init {
        require(routeA >= 0 && routeB >= 0 && routeC >= 0) {
            "Route sample counts must be non-negative."
        }
    }

    val total: Int get() = routeA + routeB + routeC
}

/**
 * Raw measurements from one real device run. Accuracy is deliberately absent:
 * the presenter derives it only from [correctSamples] / [evaluatedSamples].
 */
data class G2dValidationMeasurement(
    val experiment: G2dValidationExperiment,
    val evaluatedSamples: Int? = null,
    val correctSamples: Int? = null,
    val routeCounts: G2dValidationRouteCounts? = null,
    val p50LatencyMs: Double? = null,
    val p95LatencyMs: Double? = null,
    val peakMemoryMb: Double? = null,
    val temperatureDeltaC: Double? = null,
    val batteryDeltaPercentagePoints: Double? = null,
    val backend: String? = null,
    val quantization: String? = null,
    val agentToolCounts: Map<G2dBranchTool, Int>? = null,
    val agentFallbackCount: Int? = null,
    val routerP50LatencyMs: Double? = null,
) {
    init {
        require(evaluatedSamples == null || evaluatedSamples > 0) {
            "Evaluated sample count must be positive when present."
        }
        require(correctSamples == null || evaluatedSamples != null) {
            "Correct sample count requires an evaluated sample count."
        }
        require(correctSamples == null || correctSamples in 0..requireNotNull(evaluatedSamples)) {
            "Correct sample count must be between zero and evaluated samples."
        }
        requireFiniteNonNegative(p50LatencyMs, "P50 latency")
        requireFiniteNonNegative(p95LatencyMs, "P95 latency")
        requireFiniteNonNegative(peakMemoryMb, "Peak memory")
        requireFiniteNonNegative(routerP50LatencyMs, "Router P50 latency")
        require(temperatureDeltaC == null || temperatureDeltaC.isFinite()) {
            "Temperature delta must be finite."
        }
        require(batteryDeltaPercentagePoints == null || batteryDeltaPercentagePoints.isFinite()) {
            "Battery delta must be finite."
        }
        require(backend == null || backend.isNotBlank()) { "Backend must not be blank." }
        require(quantization == null || quantization.isNotBlank()) {
            "Quantization must not be blank."
        }
        require(agentToolCounts == null || experiment == G2dValidationExperiment.AGENTIC_G2D) {
            "Agent tool counts are valid only for the Agentic G2D experiment."
        }
        require(agentToolCounts == null || agentToolCounts.values.all { it >= 0 }) {
            "Agent tool counts must be non-negative."
        }
        require(agentFallbackCount == null || experiment == G2dValidationExperiment.AGENTIC_G2D) {
            "Agent fallback count is valid only for the Agentic G2D experiment."
        }
        require(agentFallbackCount == null || agentFallbackCount >= 0) {
            "Agent fallback count must be non-negative."
        }
        require(routerP50LatencyMs == null || experiment == G2dValidationExperiment.AGENTIC_G2D) {
            "Router latency is valid only for the Agentic G2D experiment."
        }
        require(
            experiment != G2dValidationExperiment.G2D_ONE_THETA ||
                routeCounts == null ||
                routeCounts.routeC == 0
        ) {
            "G2D 1theta uses Route A/B only; Route C count must be zero."
        }
        require(routeCounts == null || evaluatedSamples == null || routeCounts.total == evaluatedSamples) {
            "Route counts must cover the same evaluated samples."
        }
        require(agentToolCounts == null || evaluatedSamples == null ||
            agentToolCounts.values.sum() == evaluatedSamples
        ) {
            "Agent tool calls must cover the same evaluated samples."
        }
        require(agentFallbackCount == null || evaluatedSamples == null ||
            agentFallbackCount <= evaluatedSamples
        ) {
            "Agent fallback count must not exceed evaluated samples."
        }
        require(p50LatencyMs == null || p95LatencyMs == null || p95LatencyMs >= p50LatencyMs) {
            "P95 latency must not be lower than P50 latency."
        }
    }

    internal fun hasMeasuredValue(): Boolean = listOfNotNull(
        evaluatedSamples,
        correctSamples,
        routeCounts,
        p50LatencyMs,
        p95LatencyMs,
        peakMemoryMb,
        temperatureDeltaC,
        batteryDeltaPercentagePoints,
        agentToolCounts,
        agentFallbackCount,
        routerP50LatencyMs,
    ).isNotEmpty()

    companion object {
        private fun requireFiniteNonNegative(value: Double?, label: String) {
            require(value == null || value.isFinite() && value >= 0.0) {
                "$label must be finite and non-negative."
            }
        }
    }
}

data class G2dValidationInput(
    val state: G2dValidationRunState = G2dValidationRunState.READY,
    val datasetName: String = "",
    val targetSampleCount: Int? = null,
    val completedWorkItems: Int = 0,
    val totalWorkItems: Int = 0,
    val measurements: List<G2dValidationMeasurement> = emptyList(),
    val failureMessage: String = "",
    val preparationMessage: String = "",
) {
    init {
        require(targetSampleCount == null || targetSampleCount > 0) {
            "Target sample count must be positive when present."
        }
        require(completedWorkItems >= 0 && totalWorkItems >= 0) {
            "Progress counts must be non-negative."
        }
        require(totalWorkItems == 0 || completedWorkItems <= totalWorkItems) {
            "Completed work must not exceed total work."
        }
        require(measurements.map { it.experiment }.distinct().size == measurements.size) {
            "Each validation experiment may appear at most once."
        }
    }
}

/** Converts real per-sample [G2dInferenceTrace] records into one UI measurement. */
object G2dValidationTraceAdapter {
    fun <ID> aggregate(
        experiment: G2dValidationExperiment,
        traces: List<G2dInferenceTrace<ID>>,
        correctSamples: Int,
        peakMemoryMb: Double? = null,
        temperatureDeltaC: Double? = null,
        batteryDeltaPercentagePoints: Double? = null,
        backend: String? = null,
        quantization: String? = null,
    ): G2dValidationMeasurement {
        require(experiment == G2dValidationExperiment.G2D_ONE_THETA ||
            experiment == G2dValidationExperiment.G2D_TWO_THETA
        ) {
            "G2D traces can only populate G2D 1theta or G2D 2theta experiments."
        }
        require(traces.isNotEmpty()) { "At least one G2D trace is required." }
        val expectedVariant = if (experiment == G2dValidationExperiment.G2D_ONE_THETA) {
            G2dVariant.ONE_THRESHOLD
        } else {
            G2dVariant.TWO_THRESHOLD
        }
        require(traces.all { it.variant == expectedVariant }) {
            "Every trace variant must match the selected validation experiment."
        }
        require(traces.all { it.totalLatencyMs.isFinite() && it.totalLatencyMs >= 0.0 }) {
            "Every G2D trace must contain a finite non-negative total latency."
        }

        val latencies = traces.map { it.totalLatencyMs }.sorted()
        val routeCounts = G2dValidationRouteCounts(
            routeA = traces.count { it.route == G2dRoute.A },
            routeB = traces.count { it.route == G2dRoute.B },
            routeC = traces.count { it.route == G2dRoute.C },
        )
        return G2dValidationMeasurement(
            experiment = experiment,
            evaluatedSamples = traces.size,
            correctSamples = correctSamples,
            routeCounts = routeCounts,
            p50LatencyMs = nearestRankPercentile(latencies, 0.50),
            p95LatencyMs = nearestRankPercentile(latencies, 0.95),
            peakMemoryMb = peakMemoryMb,
            temperatureDeltaC = temperatureDeltaC,
            batteryDeltaPercentagePoints = batteryDeltaPercentagePoints,
            backend = backend,
            quantization = quantization,
        )
    }

    fun <ID> aggregateAgentic(
        results: List<G2dAgenticInferenceResult<ID>>,
        correctSamples: Int,
        peakMemoryMb: Double? = null,
        temperatureDeltaC: Double? = null,
        batteryDeltaPercentagePoints: Double? = null,
        backend: String? = null,
        quantization: String? = null,
    ): G2dValidationMeasurement {
        require(results.isNotEmpty()) { "At least one Agentic G2D result is required." }
        require(results.all { it.inference.trace.variant == G2dVariant.AGENTIC }) {
            "Every Agentic result must contain an AGENTIC inference trace."
        }
        require(results.all {
            it.inference.trace.totalLatencyMs.isFinite() && it.inference.trace.totalLatencyMs >= 0.0 &&
                it.routing.routerLatencyMs.isFinite() && it.routing.routerLatencyMs >= 0.0
        }) {
            "Agentic traces must contain finite non-negative inference and router latency."
        }
        val traces = results.map { it.inference.trace }
        val totalLatencies = results.map {
            it.inference.trace.totalLatencyMs + it.routing.routerLatencyMs
        }.sorted()
        val routerLatencies = results.map { it.routing.routerLatencyMs }.sorted()
        return G2dValidationMeasurement(
            experiment = G2dValidationExperiment.AGENTIC_G2D,
            evaluatedSamples = results.size,
            correctSamples = correctSamples,
            routeCounts = G2dValidationRouteCounts(
                routeA = traces.count { it.route == G2dRoute.A },
                routeB = traces.count { it.route == G2dRoute.B },
                routeC = traces.count { it.route == G2dRoute.C },
            ),
            p50LatencyMs = nearestRankPercentile(totalLatencies, 0.50),
            p95LatencyMs = nearestRankPercentile(totalLatencies, 0.95),
            peakMemoryMb = peakMemoryMb,
            temperatureDeltaC = temperatureDeltaC,
            batteryDeltaPercentagePoints = batteryDeltaPercentagePoints,
            backend = backend,
            quantization = quantization,
            agentToolCounts = results.groupingBy { it.routing.selectedTool }.eachCount(),
            agentFallbackCount = results.count { it.routing.fallbackUsed },
            routerP50LatencyMs = nearestRankPercentile(routerLatencies, 0.50),
        )
    }

    private fun nearestRankPercentile(sortedValues: List<Double>, percentile: Double): Double {
        val index = (ceil(percentile * sortedValues.size).toInt() - 1)
            .coerceIn(0, sortedValues.lastIndex)
        return sortedValues[index]
    }
}

data class G2dValidationExperimentUiModel(
    val experiment: G2dValidationExperiment,
    val title: String,
    val subtitle: String,
    val sampleLabel: String,
    val accuracyLabel: String,
    val upliftLabel: String,
    val routeALabel: String,
    val routeBLabel: String,
    val routeCLabel: String,
    val p50LatencyLabel: String,
    val p95LatencyLabel: String,
    val peakMemoryLabel: String,
    val temperatureDeltaLabel: String,
    val batteryDeltaLabel: String,
    val backendLabel: String,
    val quantizationLabel: String,
    val agentToolCallsLabel: String,
    val agentFallbackLabel: String,
    val routerP50LatencyLabel: String,
    val hasMeasuredData: Boolean,
)

data class G2dValidationUiModel(
    val state: G2dValidationRunState,
    val statusLabel: String,
    val statusDetail: String,
    val datasetLabel: String,
    val sampleCountLabel: String,
    val progressPercent: Int?,
    val progressLabel: String?,
    val experiments: List<G2dValidationExperimentUiModel>,
    val primaryActionLabel: String,
    val canStart: Boolean,
    val canCancel: Boolean,
    val canExport: Boolean,
)

object G2dValidationPresenter {
    const val WAITING_FOR_MEASUREMENT = "等待实测"
    private const val NOT_APPLICABLE = "—"

    fun present(input: G2dValidationInput): G2dValidationUiModel {
        val measurements = input.measurements.associateBy { it.experiment }
        val cards = G2dValidationExperiment.entries.map { experiment ->
            presentExperiment(experiment, measurements)
        }
        val isConfigured = input.datasetName.isNotBlank() && input.targetSampleCount != null
        val isRunnable = isConfigured && input.preparationMessage.isBlank()
        val hasExportableData = input.measurements.any { measurement ->
            measurement.evaluatedSamples != null && measurement.correctSamples != null
        }
        val progress = progress(input)
        val status = statusCopy(input, cards)

        return G2dValidationUiModel(
            state = input.state,
            statusLabel = status.first,
            statusDetail = status.second,
            datasetLabel = input.datasetName.ifBlank { "未选择数据集" },
            sampleCountLabel = input.targetSampleCount?.let { "$it 张" } ?: "等待选择",
            progressPercent = progress?.first,
            progressLabel = progress?.second,
            experiments = cards,
            primaryActionLabel = when (input.state) {
                G2dValidationRunState.READY -> if (isRunnable) {
                    "开始端侧验证"
                } else {
                    "等待数据与模型"
                }
                G2dValidationRunState.RUNNING -> "验证运行中"
                G2dValidationRunState.COMPLETED -> "重新验证"
                G2dValidationRunState.FAILED -> "重试验证"
            },
            canStart = input.state != G2dValidationRunState.RUNNING && isRunnable,
            canCancel = input.state == G2dValidationRunState.RUNNING,
            canExport = input.state == G2dValidationRunState.COMPLETED && hasExportableData,
        )
    }

    private fun presentExperiment(
        experiment: G2dValidationExperiment,
        measurements: Map<G2dValidationExperiment, G2dValidationMeasurement>,
    ): G2dValidationExperimentUiModel {
        val measurement = measurements[experiment]
        val measuredAccuracy = accuracy(measurement)
        val baselineKind = when (experiment) {
            G2dValidationExperiment.CLIP_ONLY -> null
            G2dValidationExperiment.VLM_ONLY -> G2dValidationExperiment.CLIP_ONLY
            G2dValidationExperiment.G2D_ONE_THETA,
            G2dValidationExperiment.G2D_TWO_THETA -> G2dValidationExperiment.VLM_ONLY
            G2dValidationExperiment.AGENTIC_G2D -> G2dValidationExperiment.G2D_TWO_THETA
        }
        val baseline = baselineKind?.let(measurements::get)
        val routes = routeLabels(experiment, measurement?.routeCounts)

        return G2dValidationExperimentUiModel(
            experiment = experiment,
            title = experiment.title,
            subtitle = experiment.subtitle,
            sampleLabel = measurement?.evaluatedSamples?.let { "$it 张实测" }
                ?: WAITING_FOR_MEASUREMENT,
            accuracyLabel = measuredAccuracy?.let { percent(it) } ?: WAITING_FOR_MEASUREMENT,
            upliftLabel = when {
                baselineKind == null -> "基线"
                measuredAccuracy == null -> WAITING_FOR_MEASUREMENT
                baseline == null || accuracy(baseline) == null -> "等待基线实测"
                baseline.evaluatedSamples != measurement?.evaluatedSamples -> "等待同样本实测"
                else -> {
                    val delta = measuredAccuracy - requireNotNull(accuracy(baseline))
                    "${signed(delta * 100.0, "pp")} · 较 ${baselineKind.title}"
                }
            },
            routeALabel = routes[0],
            routeBLabel = routes[1],
            routeCLabel = routes[2],
            p50LatencyLabel = milliseconds(measurement?.p50LatencyMs),
            p95LatencyLabel = milliseconds(measurement?.p95LatencyMs),
            peakMemoryLabel = measurement?.peakMemoryMb?.let { decimal(it, "MB") }
                ?: WAITING_FOR_MEASUREMENT,
            temperatureDeltaLabel = measurement?.temperatureDeltaC?.let {
                signed(it, "°C")
            } ?: WAITING_FOR_MEASUREMENT,
            batteryDeltaLabel = measurement?.batteryDeltaPercentagePoints?.let {
                signed(it, "pp")
            } ?: WAITING_FOR_MEASUREMENT,
            backendLabel = measurement?.backend ?: WAITING_FOR_MEASUREMENT,
            quantizationLabel = measurement?.quantization ?: WAITING_FOR_MEASUREMENT,
            agentToolCallsLabel = agentToolCalls(experiment, measurement?.agentToolCounts),
            agentFallbackLabel = agentFallback(experiment, measurement),
            routerP50LatencyLabel = if (experiment == G2dValidationExperiment.AGENTIC_G2D) {
                milliseconds(measurement?.routerP50LatencyMs)
            } else {
                NOT_APPLICABLE
            },
            hasMeasuredData = measurement?.hasMeasuredValue() == true,
        )
    }

    private fun agentToolCalls(
        experiment: G2dValidationExperiment,
        counts: Map<G2dBranchTool, Int>?,
    ): String {
        if (experiment != G2dValidationExperiment.AGENTIC_G2D) return NOT_APPLICABLE
        if (counts == null) return WAITING_FOR_MEASUREMENT
        return listOf(
            "CLIP ${counts[G2dBranchTool.CLIP_DIRECT] ?: 0}",
            "VLM ${counts[G2dBranchTool.VLM_FULL_LABELS] ?: 0}",
            "候选 ${counts[G2dBranchTool.CANDIDATE_VERIFIER] ?: 0}",
            "无概率 ${counts[G2dBranchTool.CANDIDATE_VERIFIER_NO_PROB] ?: 0}",
        ).joinToString(" · ")
    }

    private fun agentFallback(
        experiment: G2dValidationExperiment,
        measurement: G2dValidationMeasurement?,
    ): String {
        if (experiment != G2dValidationExperiment.AGENTIC_G2D) return NOT_APPLICABLE
        val fallback = measurement?.agentFallbackCount ?: return WAITING_FOR_MEASUREMENT
        val total = measurement.evaluatedSamples ?: return "$fallback 次"
        return "$fallback 次 · ${percent(fallback.toDouble() / total)}"
    }

    private fun routeLabels(
        experiment: G2dValidationExperiment,
        counts: G2dValidationRouteCounts?,
    ): List<String> {
        if (experiment == G2dValidationExperiment.CLIP_ONLY ||
            experiment == G2dValidationExperiment.VLM_ONLY
        ) {
            return listOf(NOT_APPLICABLE, NOT_APPLICABLE, NOT_APPLICABLE)
        }
        if (counts == null || counts.total == 0) {
            return if (experiment == G2dValidationExperiment.G2D_ONE_THETA) {
                listOf(WAITING_FOR_MEASUREMENT, WAITING_FOR_MEASUREMENT, NOT_APPLICABLE)
            } else {
                List(3) { WAITING_FOR_MEASUREMENT }
            }
        }
        fun ratio(count: Int): String = percent(count.toDouble() / counts.total)
        return if (experiment == G2dValidationExperiment.G2D_ONE_THETA) {
            listOf(ratio(counts.routeA), ratio(counts.routeB), NOT_APPLICABLE)
        } else {
            listOf(ratio(counts.routeA), ratio(counts.routeB), ratio(counts.routeC))
        }
    }

    private fun accuracy(measurement: G2dValidationMeasurement?): Double? {
        val samples = measurement?.evaluatedSamples ?: return null
        val correct = measurement.correctSamples ?: return null
        return correct.toDouble() / samples
    }

    private fun progress(input: G2dValidationInput): Pair<Int, String>? {
        if (input.state != G2dValidationRunState.RUNNING || input.totalWorkItems <= 0) return null
        val percent = (input.completedWorkItems * 100.0 / input.totalWorkItems)
            .toInt()
            .coerceIn(0, 100)
        return percent to "${input.completedWorkItems} / ${input.totalWorkItems} 次端侧推理 · $percent%"
    }

    private fun statusCopy(
        input: G2dValidationInput,
        cards: List<G2dValidationExperimentUiModel>,
    ): Pair<String, String> = when (input.state) {
        G2dValidationRunState.READY -> when {
            input.datasetName.isBlank() || input.targetSampleCount == null ->
                "等待配置" to "选择数据集和样本范围后开始；此页不会预填任何准确率。"
            input.preparationMessage.isNotBlank() ->
                "等待资源" to input.preparationMessage
            else -> "准备就绪" to
                "将依次运行五种方法，全部指标只从本机测量记录生成。"
        }
        G2dValidationRunState.RUNNING -> "正在验证" to
            "保持设备散热稳定；取消后保留已完成的诊断记录，不生成完整结论。"
        G2dValidationRunState.COMPLETED -> "验证完成" to if (cards.all {
            it.accuracyLabel != WAITING_FOR_MEASUREMENT
        }) {
            "五组准确率已汇总；缺失的性能指标仍保持“等待实测”。"
        } else {
            "运行已经结束；缺失指标继续标记为“等待实测”，不会自动补值。"
        }
        G2dValidationRunState.FAILED -> "验证失败" to input.failureMessage.ifBlank {
            "未生成完整实验结果，请检查模型、数据集和设备状态后重试。"
        }
    }

    private fun percent(value: Double): String = "%.2f%%".format(Locale.US, value * 100.0)

    private fun milliseconds(value: Double?): String = value?.let { decimal(it, "ms") }
        ?: WAITING_FOR_MEASUREMENT

    private fun decimal(value: Double, unit: String): String =
        "%.1f %s".format(Locale.US, value, unit)

    private fun signed(value: Double, unit: String): String =
        "%+.2f %s".format(Locale.US, value, unit)
}

data class G2dValidationCallbacks(
    val onStart: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onExport: () -> Unit = {},
)

/** Standalone native View surface for the on-device G2D paper validation platform. */
class G2dValidationScreen(context: Context) : LinearLayout(context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(24))
    }

    init {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Palette.background)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(
        model: G2dValidationUiModel,
        callbacks: G2dValidationCallbacks = G2dValidationCallbacks(),
    ) {
        setBackgroundColor(Palette.background)
        content.removeAllViews()
        content.addView(header())
        content.addView(space(12))
        content.addView(statusCard(model, callbacks))
        content.addView(space(18))
        content.addView(text("五路端侧对照", 19f, Palette.deepInk, Typeface.BOLD))
        content.addView(space(4))
        content.addView(text(
            "1θ/2θ 保留为论文规则基线；Agentic 路由只能从已注册的 CLIP、VLM 与候选验证工具中选择。",
            12f,
            Palette.muted,
        ))
        content.addView(space(10))
        model.experiments.forEachIndexed { index, experiment ->
            content.addView(experimentCard(experiment))
            if (index != model.experiments.lastIndex) content.addView(space(12))
        }
        content.addView(space(14))
        content.addView(honestyNote())
    }

    private fun header(): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(TuiMaTheme.compactHeaderHeightDp)
        addView(
            IconBadgeView(context, "chip", Palette.lavender),
            LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) },
        )
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(text("G2D 端侧验证", 20f, Palette.deepInk, Typeface.BOLD))
            addView(text("规则基线 + Agentic 路由 · 同设备实测", 12f, Palette.muted))
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun statusCard(
        model: G2dValidationUiModel,
        callbacks: G2dValidationCallbacks,
    ): View {
        val accent = stateColor(model.state)
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(tint(accent, 0.08f), tint(accent, 0.45f), 16f)

            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(model.statusLabel, 18f, Palette.deepInk, Typeface.BOLD),
                    LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(statusPill(model, accent))
            })
            addView(space(6))
            addView(text(model.statusDetail, 13f, Palette.ink))
            addView(space(12))
            addView(metaRow("数据集", model.datasetLabel))
            addView(metaRow("样本数", model.sampleCountLabel))

            model.progressPercent?.let { percent ->
                addView(space(12))
                addView(ProgressBar(
                    context,
                    null,
                    android.R.attr.progressBarStyleHorizontal,
                ).apply {
                    progress = percent
                    progressTintList = ColorStateList.valueOf(accent)
                    progressBackgroundTintList = ColorStateList.valueOf(Palette.stroke)
                    contentDescription = "G2D 端侧验证进度 $percent%"
                }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
                addView(space(5))
                addView(text(model.progressLabel.orEmpty(), 12f, Palette.muted))
            }

            addView(space(13))
            addView(actionArea(model, callbacks))
        }
    }

    private fun actionArea(
        model: G2dValidationUiModel,
        callbacks: G2dValidationCallbacks,
    ): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        when (model.state) {
            G2dValidationRunState.RUNNING -> addView(
                actionButton("取消验证", enabled = model.canCancel, destructive = true, callbacks.onCancel),
                LayoutParams(0, dp(TuiMaTheme.minimumTouchTargetDp), 1f),
            )
            G2dValidationRunState.COMPLETED -> {
                addView(
                    actionButton(model.primaryActionLabel, model.canStart, false, callbacks.onStart),
                    LayoutParams(0, dp(TuiMaTheme.minimumTouchTargetDp), 1f),
                )
                addView(
                    actionButton("导出实测报告", model.canExport, false, callbacks.onExport),
                    LayoutParams(0, dp(TuiMaTheme.minimumTouchTargetDp), 1f).apply {
                        marginStart = dp(8)
                    },
                )
            }
            G2dValidationRunState.READY,
            G2dValidationRunState.FAILED -> addView(
                actionButton(model.primaryActionLabel, model.canStart, false, callbacks.onStart),
                LayoutParams(0, dp(TuiMaTheme.minimumTouchTargetDp), 1f),
            )
        }
    }

    private fun experimentCard(model: G2dValidationExperimentUiModel): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = rounded(Palette.surface, Palette.stroke, 16f)
            elevation = dp(1).toFloat()

            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(text(model.title, 17f, Palette.deepInk, Typeface.BOLD))
                    addView(text(model.subtitle, 12f, Palette.muted))
                }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(measurementPill(model.hasMeasuredData))
            })
            addView(space(13))
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(heroMetric("准确率", model.accuracyLabel), LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ))
                addView(heroMetric("提升", model.upliftLabel), LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply { marginStart = dp(9) })
            })
            addView(space(9))
            addView(metaRow("样本", model.sampleLabel))
            addView(metaRow("后端", model.backendLabel))
            addView(metaRow("量化", model.quantizationLabel))
            addView(space(11))
            addView(text("路由占比", 12f, Palette.muted, Typeface.BOLD))
            addView(space(6))
            addView(routeRow(model))
            addView(space(10))
            addView(metricGrid(model))
            if (model.experiment == G2dValidationExperiment.AGENTIC_G2D) {
                addView(space(10))
                addView(text("Agent 调度审计", 12f, Palette.muted, Typeface.BOLD))
                addView(space(5))
                addView(metaRow("工具调用", model.agentToolCallsLabel))
                addView(space(5))
                addView(metricPair(
                    "路由器 P50",
                    model.routerP50LatencyLabel,
                    "规则回退",
                    model.agentFallbackLabel,
                ))
            }
        }

    private fun heroMetric(label: String, value: String): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(11), dp(10), dp(11), dp(10))
        background = rounded(Palette.mintWash, Palette.mint, 12f)
        addView(text(label, 11f, Palette.muted, Typeface.BOLD))
        addView(space(3))
        addView(text(value, if (value == G2dValidationPresenter.WAITING_FOR_MEASUREMENT) 13f else 17f,
            Palette.deepInk, Typeface.BOLD))
    }

    private fun routeRow(model: G2dValidationExperimentUiModel): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            listOf(
                "A" to model.routeALabel,
                "B" to model.routeBLabel,
                "C" to model.routeCLabel,
            ).forEachIndexed { index, item ->
                addView(routeCell(item.first, item.second), LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply { if (index > 0) marginStart = dp(7) })
            }
        }

    private fun routeCell(label: String, value: String): View = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = rounded(Palette.blueWash, Palette.stroke, 10f)
        addView(text("Route $label", 10f, Palette.muted, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
        })
        addView(text(value, if (value.length > 8) 10f else 13f, Palette.deepInk, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun metricGrid(model: G2dValidationExperimentUiModel): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            addView(metricPair("P50 延迟", model.p50LatencyLabel, "P95 延迟", model.p95LatencyLabel))
            addView(space(7))
            addView(metricPair("峰值内存", model.peakMemoryLabel, "温度变化", model.temperatureDeltaLabel))
            addView(space(7))
            addView(metricPair("电量变化", model.batteryDeltaLabel, "记录状态",
                if (model.hasMeasuredData) "已有实测" else G2dValidationPresenter.WAITING_FOR_MEASUREMENT))
        }

    private fun metricPair(
        leftLabel: String,
        leftValue: String,
        rightLabel: String,
        rightValue: String,
    ): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(metricCell(leftLabel, leftValue), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(metricCell(rightLabel, rightValue), LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply { marginStart = dp(7) })
    }

    private fun metricCell(label: String, value: String): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(10), dp(9), dp(10), dp(9))
        background = rounded(Palette.background, Palette.stroke, 10f)
        addView(text(label, 10f, Palette.muted, Typeface.BOLD))
        addView(space(2))
        addView(text(value, if (value.length > 12) 11f else 13f, Palette.ink, Typeface.BOLD))
    }

    private fun honestyNote(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = rounded(Palette.lavenderWash, Palette.lavender, 14f)
        addView(text("实测原则", 13f, Palette.deepInk, Typeface.BOLD))
        addView(space(4))
        addView(text(
            "准确率只由正确样本数计算；Agent 不接收真值，非法工具调用会记录并回退到 2θ。不同样本范围不计算提升，缺失指标一律显示“等待实测”。",
            12f,
            Palette.ink,
        ))
    }

    private fun metaRow(label: String, value: String): View = LinearLayout(context).apply {
        gravity = Gravity.TOP
        setPadding(0, dp(2), 0, dp(2))
        addView(text(label, 12f, Palette.muted, Typeface.BOLD), LayoutParams(
            dp(58),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        addView(text(value, 12f, Palette.ink), LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ))
    }

    private fun statusPill(model: G2dValidationUiModel, accent: Int): TextView = text(
        when (model.state) {
            G2dValidationRunState.READY -> if (model.canStart) "READY" else "WAITING"
            G2dValidationRunState.RUNNING -> "RUNNING"
            G2dValidationRunState.COMPLETED -> "COMPLETED"
            G2dValidationRunState.FAILED -> "FAILED"
        },
        10f,
        accent,
        Typeface.BOLD,
    ).apply {
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = rounded(tint(accent, 0.10f), tint(accent, 0.42f), 99f)
    }

    private fun measurementPill(hasData: Boolean): TextView = text(
        if (hasData) "已有实测" else G2dValidationPresenter.WAITING_FOR_MEASUREMENT,
        10f,
        if (hasData) Palette.mintDark else Palette.muted,
        Typeface.BOLD,
    ).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(5), dp(8), dp(5))
        background = rounded(
            if (hasData) Palette.mintPale else Palette.background,
            if (hasData) Palette.mint else Palette.stroke,
            99f,
        )
    }

    private fun actionButton(
        label: String,
        enabled: Boolean,
        destructive: Boolean,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        isEnabled = enabled
        typeface = Typeface.DEFAULT_BOLD
        val accent = if (destructive) ERROR_COLOR else Palette.mintDark
        setTextColor(if (enabled) accent else Palette.muted)
        background = rounded(
            if (enabled) tint(accent, 0.11f) else Palette.background,
            if (enabled) tint(accent, 0.52f) else Palette.stroke,
            14f,
        )
        contentDescription = label
        setOnClickListener { if (isEnabled) onClick() }
    }

    private fun stateColor(state: G2dValidationRunState): Int = when (state) {
        G2dValidationRunState.READY -> Palette.blue
        G2dValidationRunState.RUNNING -> Palette.sky
        G2dValidationRunState.COMPLETED -> Palette.mintDark
        G2dValidationRunState.FAILED -> ERROR_COLOR
    }

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        style: Int = Typeface.NORMAL,
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = Typeface.create(Typeface.DEFAULT, style)
        setLineSpacing(0f, 1.08f)
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke(dp(1), stroke)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun tint(color: Int, alpha: Float): Int {
        val clamped = alpha.coerceIn(0f, 1f)
        return (color and 0x00FFFFFF) or ((clamped * 255f).toInt() shl 24)
    }

    private fun space(heightDp: Int): View = View(context).apply {
        layoutParams = LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val ERROR_COLOR = 0xFFE15E64.toInt()
    }
}
