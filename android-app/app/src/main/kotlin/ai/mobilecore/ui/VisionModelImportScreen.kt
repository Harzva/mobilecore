package ai.mobilecore.ui

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
import java.io.File
import java.util.Locale

enum class VisionModelSlot(
    val defaultTitle: String,
    val taskLabel: String,
    val expectedFormat: String,
    val defaultRuntime: String
) {
    YOLO_DETECT(
        defaultTitle = "YOLO 目标检测",
        taskLabel = "检测",
        expectedFormat = "ONNX / ORT / TFLite",
        defaultRuntime = "ONNX Runtime Mobile"
    ),
    YOLO_SEGMENT(
        defaultTitle = "YOLO 实例分割",
        taskLabel = "分割",
        expectedFormat = "ONNX / ORT / TFLite",
        defaultRuntime = "ONNX Runtime Mobile"
    ),
    CLIP_RETRIEVAL(
        defaultTitle = "CLIP 图文检索",
        taskLabel = "图文匹配",
        expectedFormat = "图像编码器 + 文本编码器 / embedding sidecar",
        defaultRuntime = "ONNX Runtime Mobile"
    ),
    SMALL_VLM(
        defaultTitle = "小型 VLM 复核",
        taskLabel = "G2D 复核",
        expectedFormat = "GGUF 主模型 + mmproj",
        defaultRuntime = "llama.cpp"
    )
}

enum class VisionArtifactRole(val label: String) {
    YOLO_MODEL("模型"),
    CLIP_IMAGE_ENCODER("图像编码器"),
    CLIP_TEXT_ENCODER("文本编码器"),
    CLIP_EMBEDDING_SIDECAR("固定标签 sidecar"),
    VLM_MAIN_MODEL("GGUF 主模型"),
    VLM_MMPROJ("mmproj 视觉投影")
}

data class VisionModelArtifact(
    val fileName: String,
    val role: VisionArtifactRole,
    val sizeBytes: Long = 0L
)

enum class VisionTransferPhase {
    IDLE,
    IMPORTING,
    PAUSED,
    FAILED
}

data class VisionImportTransfer(
    val phase: VisionTransferPhase = VisionTransferPhase.IDLE,
    val bytesCopied: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String = ""
)

data class VisionModelPackageInput(
    val id: String,
    val slot: VisionModelSlot,
    val title: String = slot.defaultTitle,
    val artifacts: List<VisionModelArtifact> = emptyList(),
    val transfer: VisionImportTransfer = VisionImportTransfer()
)

enum class VisionPackageStatus {
    READY,
    LIMITED,
    MISSING_FILES,
    INCOMPATIBLE,
    IMPORTING,
    PAUSED,
    FAILED
}

data class VisionModelActionUiModel(
    val id: String,
    val label: String,
    val destructive: Boolean = false
)

data class VisionModelPackageUiModel(
    val id: String,
    val title: String,
    val taskLabel: String,
    val status: VisionPackageStatus,
    val statusLabel: String,
    val statusDetail: String,
    val formatLabel: String,
    val runtimeLabel: String,
    val accelerationLabel: String,
    val artifactLabels: List<String>,
    val progressPercent: Int?,
    val progressLabel: String?,
    val actions: List<VisionModelActionUiModel>
)

data class VisionModelImportUiModel(
    val readyCount: Int,
    val totalCount: Int,
    val summary: String,
    val packages: List<VisionModelPackageUiModel>
)

/**
 * Converts imported files into honest package readiness. READY means the required files are
 * present; it does not claim that a GPU/NPU execution provider or a model architecture works.
 * Runtime compatibility is intentionally left to the diagnostic action.
 */
object VisionModelImportPresenter {
    fun present(inputs: List<VisionModelPackageInput>): VisionModelImportUiModel {
        val packagesBySlot = inputs.groupBy { it.slot }
        val normalized = VisionModelSlot.entries.flatMap { slot ->
            packagesBySlot[slot].orEmpty().ifEmpty {
                listOf(VisionModelPackageInput(id = slot.name.lowercase(Locale.US), slot = slot))
            }
        }
        val packages = normalized.map(::presentPackage)
        val ready = packages.count { it.status == VisionPackageStatus.READY || it.status == VisionPackageStatus.LIMITED }
        return VisionModelImportUiModel(
            readyCount = ready,
            totalCount = packages.size,
            summary = if (ready == 0) {
                "尚无完整视觉模型包，先导入一个任务所需的全部文件。"
            } else {
                "$ready / ${packages.size} 个模型包文件就绪，运行前仍需逐项诊断。"
            },
            packages = packages
        )
    }

    fun presentPackage(input: VisionModelPackageInput): VisionModelPackageUiModel {
        val validation = validate(input)
        val status = when (input.transfer.phase) {
            VisionTransferPhase.IMPORTING -> VisionPackageStatus.IMPORTING
            VisionTransferPhase.PAUSED -> VisionPackageStatus.PAUSED
            VisionTransferPhase.FAILED -> VisionPackageStatus.FAILED
            VisionTransferPhase.IDLE -> validation.status
        }
        val progress = input.transfer.takeIf {
            it.phase == VisionTransferPhase.IMPORTING || it.phase == VisionTransferPhase.PAUSED
        }?.let(::progressPercent)
        val runtime = inferRuntime(input)
        return VisionModelPackageUiModel(
            id = input.id,
            title = input.title,
            taskLabel = input.slot.taskLabel,
            status = status,
            statusLabel = statusLabel(status),
            statusDetail = when (status) {
                VisionPackageStatus.IMPORTING -> "正在复制到本机视觉模型目录，离开页面后可继续显示进度。"
                VisionPackageStatus.PAUSED -> "导入已暂停，已复制的临时文件将保留。"
                VisionPackageStatus.FAILED -> input.transfer.errorMessage.ifBlank { "导入失败，请重新选择文件。" }
                else -> validation.detail
            },
            formatLabel = actualFormatLabel(input),
            runtimeLabel = runtime,
            accelerationLabel = accelerationLabel(runtime, input.artifacts.isNotEmpty()),
            artifactLabels = input.artifacts.map(::artifactLabel).ifEmpty { listOf("尚未导入文件") },
            progressPercent = progress,
            progressLabel = progress?.let {
                "${formatBytes(input.transfer.bytesCopied)} / ${formatBytes(input.transfer.totalBytes)} · $it%"
            },
            actions = actions(status, input.artifacts.isNotEmpty())
        )
    }

    private fun validate(input: VisionModelPackageInput): Validation {
        val invalidArtifact = input.artifacts.firstOrNull { artifact ->
            artifact.sizeBytes < 0L || !isRoleAllowed(input.slot, artifact.role) || !isExtensionAllowed(artifact)
        }
        if (invalidArtifact != null) {
            return Validation(
                VisionPackageStatus.INCOMPATIBLE,
                "${invalidArtifact.fileName} 的角色或格式与此任务不匹配，请替换后再诊断。"
            )
        }
        if (input.artifacts.any { it.fileName.extensionLower() == "mnn" }) {
            return Validation(
                VisionPackageStatus.INCOMPATIBLE,
                "当前版本可管理 MNN 文件，但尚未接入此任务的 MNN 执行链路。"
            )
        }
        return when (input.slot) {
            VisionModelSlot.YOLO_DETECT,
            VisionModelSlot.YOLO_SEGMENT -> validateYolo(input)

            VisionModelSlot.CLIP_RETRIEVAL -> validateClip(input)
            VisionModelSlot.SMALL_VLM -> validateVlm(input)
        }
    }

    private fun validateYolo(input: VisionModelPackageInput): Validation {
        val model = input.artifacts.firstOrNull { it.role == VisionArtifactRole.YOLO_MODEL }
        return if (model == null) {
            Validation(VisionPackageStatus.MISSING_FILES, "缺少 YOLO 模型文件（ONNX / ORT / TFLite）。")
        } else {
            Validation(
                VisionPackageStatus.READY,
                "模型文件已就绪；输入尺寸、输出张量和算子支持仍需运行诊断确认。"
            )
        }
    }

    private fun validateClip(input: VisionModelPackageInput): Validation {
        val hasImage = input.artifacts.any { it.role == VisionArtifactRole.CLIP_IMAGE_ENCODER }
        val hasText = input.artifacts.any { it.role == VisionArtifactRole.CLIP_TEXT_ENCODER }
        val hasSidecar = input.artifacts.any { it.role == VisionArtifactRole.CLIP_EMBEDDING_SIDECAR }
        if (!hasImage) {
            return Validation(VisionPackageStatus.MISSING_FILES, "缺少 CLIP 图像编码器。")
        }
        if (hasText) {
            return Validation(
                VisionPackageStatus.READY,
                "图像与文本编码器已配对，可在诊断通过后用于开放文本检索。"
            )
        }
        if (hasSidecar) {
            return Validation(
                VisionPackageStatus.LIMITED,
                "图像编码器与 embedding sidecar 已配对，仅支持 sidecar 内的固定标签；图文搜索仍缺文本编码器。"
            )
        }
        return Validation(
            VisionPackageStatus.MISSING_FILES,
            "缺少 CLIP 文本编码器；也可导入 JSON embedding sidecar 进行固定标签验证。"
        )
    }

    private fun validateVlm(input: VisionModelPackageInput): Validation {
        val hasMain = input.artifacts.any { it.role == VisionArtifactRole.VLM_MAIN_MODEL }
        val hasMmproj = input.artifacts.any { it.role == VisionArtifactRole.VLM_MMPROJ }
        return when {
            !hasMain && !hasMmproj -> Validation(
                VisionPackageStatus.MISSING_FILES,
                "必须成对导入 GGUF 主模型和匹配的 mmproj 视觉投影文件。"
            )

            !hasMain -> Validation(VisionPackageStatus.MISSING_FILES, "已有 mmproj，但缺少与之匹配的 GGUF 主模型。")
            !hasMmproj -> Validation(VisionPackageStatus.MISSING_FILES, "已有 GGUF 主模型，但缺少匹配的 mmproj 视觉投影文件。")
            else -> Validation(
                VisionPackageStatus.READY,
                "GGUF 主模型与 mmproj 文件已配对；架构和投影维度仍需运行诊断确认。"
            )
        }
    }

    private fun isRoleAllowed(slot: VisionModelSlot, role: VisionArtifactRole): Boolean = when (slot) {
        VisionModelSlot.YOLO_DETECT,
        VisionModelSlot.YOLO_SEGMENT -> role == VisionArtifactRole.YOLO_MODEL

        VisionModelSlot.CLIP_RETRIEVAL -> role in setOf(
            VisionArtifactRole.CLIP_IMAGE_ENCODER,
            VisionArtifactRole.CLIP_TEXT_ENCODER,
            VisionArtifactRole.CLIP_EMBEDDING_SIDECAR
        )

        VisionModelSlot.SMALL_VLM -> role == VisionArtifactRole.VLM_MAIN_MODEL || role == VisionArtifactRole.VLM_MMPROJ
    }

    private fun isExtensionAllowed(artifact: VisionModelArtifact): Boolean {
        val extension = artifact.fileName.extensionLower()
        return when (artifact.role) {
            VisionArtifactRole.YOLO_MODEL -> extension in setOf("onnx", "ort", "tflite", "mnn")
            VisionArtifactRole.CLIP_IMAGE_ENCODER,
            VisionArtifactRole.CLIP_TEXT_ENCODER -> extension in setOf("onnx", "ort", "tflite", "mnn")

            VisionArtifactRole.CLIP_EMBEDDING_SIDECAR -> extension == "json"
            VisionArtifactRole.VLM_MAIN_MODEL -> extension == "gguf"
            VisionArtifactRole.VLM_MMPROJ -> extension == "mmproj" || extension == "gguf"
        }
    }

    private fun actualFormatLabel(input: VisionModelPackageInput): String {
        if (input.artifacts.isEmpty()) return input.slot.expectedFormat
        val formats = input.artifacts.map { artifact ->
            when (artifact.role) {
                VisionArtifactRole.VLM_MMPROJ -> "mmproj"
                VisionArtifactRole.CLIP_EMBEDDING_SIDECAR -> "JSON sidecar"
                else -> artifact.fileName.extensionLower().uppercase(Locale.US)
            }
        }.distinct()
        return formats.joinToString(" + ")
    }

    private fun inferRuntime(input: VisionModelPackageInput): String {
        val coreExtension = input.artifacts.firstOrNull {
            it.role != VisionArtifactRole.CLIP_EMBEDDING_SIDECAR && it.role != VisionArtifactRole.VLM_MMPROJ
        }?.fileName?.extensionLower()
        return when (coreExtension) {
            "onnx", "ort" -> "ONNX Runtime Mobile"
            "tflite" -> "TensorFlow Lite"
            "mnn" -> "MNN（执行链路待接入）"
            "gguf" -> "llama.cpp"
            else -> input.slot.defaultRuntime
        }
    }

    private fun accelerationLabel(runtime: String, hasFiles: Boolean): String {
        val prefix = if (hasFiles) "当前默认" else "预计基线"
        return when {
            runtime.startsWith("ONNX") -> "$prefix：CPU · NNAPI/QNN/GPU 执行器未注册"
            runtime.startsWith("TensorFlow") -> "$prefix：CPU · GPU/NNAPI delegate 未添加"
            runtime.startsWith("llama.cpp") -> "$prefix：CPU（gpu_layers=0） · GPU/NPU 未启用"
            else -> "执行状态待诊断 · GPU/NPU 加速未验证"
        }
    }

    private fun actions(status: VisionPackageStatus, hasArtifacts: Boolean): List<VisionModelActionUiModel> = when (status) {
        VisionPackageStatus.IMPORTING -> listOf(VisionModelActionUiModel("pause", "暂停"))
        VisionPackageStatus.PAUSED -> listOf(
            VisionModelActionUiModel("resume", "继续导入"),
            VisionModelActionUiModel("remove", "移除", destructive = true)
        )

        VisionPackageStatus.FAILED -> listOf(
            VisionModelActionUiModel("retry", "重新导入"),
            VisionModelActionUiModel("remove", "清理临时文件", destructive = true)
        )

        VisionPackageStatus.MISSING_FILES -> listOfNotNull(
            VisionModelActionUiModel("import", if (hasArtifacts) "补齐文件" else "导入模型包"),
            VisionModelActionUiModel("remove", "移除", destructive = true).takeIf { hasArtifacts }
        )

        VisionPackageStatus.INCOMPATIBLE -> listOf(
            VisionModelActionUiModel("replace", "替换文件"),
            VisionModelActionUiModel("diagnose", "查看诊断")
        )

        VisionPackageStatus.READY,
        VisionPackageStatus.LIMITED -> listOf(
            VisionModelActionUiModel("diagnose", "运行诊断"),
            VisionModelActionUiModel("remove", "移除", destructive = true)
        )
    }

    private fun statusLabel(status: VisionPackageStatus): String = when (status) {
        VisionPackageStatus.READY -> "文件就绪"
        VisionPackageStatus.LIMITED -> "固定标签就绪"
        VisionPackageStatus.MISSING_FILES -> "缺文件"
        VisionPackageStatus.INCOMPATIBLE -> "不兼容"
        VisionPackageStatus.IMPORTING -> "导入中"
        VisionPackageStatus.PAUSED -> "已暂停"
        VisionPackageStatus.FAILED -> "导入失败"
    }

    private fun progressPercent(transfer: VisionImportTransfer): Int {
        if (transfer.totalBytes <= 0L) return 0
        return (transfer.bytesCopied.toDouble() / transfer.totalBytes.toDouble() * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun artifactLabel(artifact: VisionModelArtifact): String {
        val size = artifact.sizeBytes.takeIf { it > 0L }?.let { " · ${formatBytes(it)}" }.orEmpty()
        return "${artifact.role.label} · ${artifact.fileName}$size"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "大小未知"
        val mib = bytes / (1024.0 * 1024.0)
        return if (mib >= 1024.0) "%.1f GB".format(Locale.US, mib / 1024.0) else "%.0f MB".format(Locale.US, mib)
    }

    private fun String.extensionLower(): String = substringAfterLast('.', "").lowercase(Locale.US)

    private data class Validation(val status: VisionPackageStatus, val detail: String)
}

/** Maps the files already present in MobileCore's private vision directory into task packages. */
object VisionModelImportCatalog {
    fun fromFiles(files: List<File>): List<VisionModelPackageInput> {
        return files
            .filter(File::isFile)
            .mapNotNull(::classify)
            .groupBy({ it.first }, { it.second })
            .map { (slot, artifacts) ->
                VisionModelPackageInput(
                    id = slot.name.lowercase(Locale.US),
                    slot = slot,
                    artifacts = artifacts.distinctBy { "${it.role}:${it.fileName}" }
                )
            }
    }

    private fun classify(file: File): Pair<VisionModelSlot, VisionModelArtifact>? {
        val lower = file.name.lowercase(Locale.US)
        val extension = file.extension.lowercase(Locale.US)
        val slot: VisionModelSlot
        val role: VisionArtifactRole
        when {
            ("yolo" in lower || "detect" in lower) && ("seg" in lower || "mask" in lower) -> {
                slot = VisionModelSlot.YOLO_SEGMENT
                role = VisionArtifactRole.YOLO_MODEL
            }
            "yolo" in lower || "detect" in lower -> {
                slot = VisionModelSlot.YOLO_DETECT
                role = VisionArtifactRole.YOLO_MODEL
            }
            "clip" in lower || "mobileclip" in lower || "vit" in lower -> {
                slot = VisionModelSlot.CLIP_RETRIEVAL
                role = when {
                    extension == "json" -> VisionArtifactRole.CLIP_EMBEDDING_SIDECAR
                    "text" in lower -> VisionArtifactRole.CLIP_TEXT_ENCODER
                    else -> VisionArtifactRole.CLIP_IMAGE_ENCODER
                }
            }
            extension in setOf("gguf", "mmproj") || "mmproj" in lower -> {
                slot = VisionModelSlot.SMALL_VLM
                role = if (extension == "mmproj" || "mmproj" in lower) {
                    VisionArtifactRole.VLM_MMPROJ
                } else {
                    VisionArtifactRole.VLM_MAIN_MODEL
                }
            }
            else -> return null
        }
        return slot to VisionModelArtifact(file.name, role, file.length())
    }
}

data class VisionModelImportCallbacks(
    val onImport: (String) -> Unit = {},
    val onPause: (String) -> Unit = {},
    val onResume: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onDiagnose: (String) -> Unit = {},
    val onReplace: (String) -> Unit = {},
    val onRetry: (String) -> Unit = {}
)

/** Standalone native View surface. The host only needs to provide state and action callbacks. */
class VisionModelImportScreen(context: Context) : LinearLayout(context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(24))
    }

    init {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Palette.background)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(model: VisionModelImportUiModel, callbacks: VisionModelImportCallbacks = VisionModelImportCallbacks()) {
        content.removeAllViews()
        content.addView(header())
        content.addView(space(12))
        content.addView(summaryCard(model))
        content.addView(space(16))
        content.addView(text("模型包", 18f, Palette.deepInk, Typeface.BOLD))
        content.addView(text("文件完整不代表运行兼容；每个模型包都需要通过本机诊断。", 12f, Palette.muted))
        content.addView(space(10))
        model.packages.forEachIndexed { index, packageModel ->
            content.addView(packageCard(packageModel, callbacks))
            if (index != model.packages.lastIndex) content.addView(space(12))
        }
    }

    private fun header(): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(TuiMaTheme.compactHeaderHeightDp)
        addView(IconBadgeView(context, "image", Palette.sky), LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            marginEnd = dp(12)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("视觉模型", 20f, Palette.deepInk, Typeface.BOLD))
            addView(text("导入、配对与本机兼容性诊断", 12f, Palette.muted))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun summaryCard(model: VisionModelImportUiModel): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(Palette.mintWash, Palette.mint, 16f)
        addView(text("${model.readyCount} / ${model.totalCount} 模型包就绪", 18f, Palette.deepInk, Typeface.BOLD))
        addView(space(4))
        addView(text(model.summary, 13f, Palette.ink))
        addView(space(10))
        addView(text("当前执行基线为 CPU；GPU、NNAPI、QNN 或 NPU 只有在显式接入并通过诊断后才会标记启用。", 12f, Palette.muted))
    }

    private fun packageCard(model: VisionModelPackageUiModel, callbacks: VisionModelImportCallbacks): View {
        val accent = statusColor(model.status)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(14))
            background = rounded(Palette.surface, Palette.stroke, 16f)
            elevation = dp(1).toFloat()

            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(IconBadgeView(context, if (model.taskLabel == "G2D 复核") "chip" else "image", accent), LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginEnd = dp(11)
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(model.title, 16f, Palette.deepInk, Typeface.BOLD))
                    addView(text(model.taskLabel, 12f, Palette.muted))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(statusPill(model.statusLabel, accent))
            })
            addView(space(11))
            addView(text(model.statusDetail, 13f, Palette.ink))
            addView(space(10))
            addView(metaRow("格式", model.formatLabel))
            addView(metaRow("运行时", model.runtimeLabel))
            addView(metaRow("加速", model.accelerationLabel))
            addView(space(8))
            model.artifactLabels.forEach { label ->
                addView(text("• $label", 12f, Palette.muted).apply { setPadding(0, dp(2), 0, dp(2)) })
            }
            model.progressPercent?.let { percent ->
                addView(space(10))
                addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    progress = percent
                    progressTintList = ColorStateList.valueOf(Palette.mintDark)
                    progressBackgroundTintList = ColorStateList.valueOf(Palette.stroke)
                    contentDescription = "${model.title} 导入进度 $percent%"
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
                addView(space(5))
                addView(text(model.progressLabel.orEmpty(), 12f, Palette.muted))
            }
            if (model.actions.isNotEmpty()) {
                addView(space(12))
                addView(actionRow(model, callbacks))
            }
        }
    }

    private fun actionRow(model: VisionModelPackageUiModel, callbacks: VisionModelImportCallbacks): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            model.actions.forEachIndexed { index, action ->
                addView(
                    actionButton(action) { dispatchAction(action.id, model.id, callbacks) },
                    LinearLayout.LayoutParams(0, dp(TuiMaTheme.minimumTouchTargetDp), 1f).apply {
                        if (index > 0) marginStart = dp(8)
                    }
                )
            }
        }

    private fun dispatchAction(action: String, packageId: String, callbacks: VisionModelImportCallbacks) {
        when (action) {
            "import" -> callbacks.onImport(packageId)
            "pause" -> callbacks.onPause(packageId)
            "resume" -> callbacks.onResume(packageId)
            "remove" -> callbacks.onRemove(packageId)
            "diagnose" -> callbacks.onDiagnose(packageId)
            "replace" -> callbacks.onReplace(packageId)
            "retry" -> callbacks.onRetry(packageId)
        }
    }

    private fun metaRow(label: String, value: String): View = LinearLayout(context).apply {
        gravity = Gravity.TOP
        setPadding(0, dp(2), 0, dp(2))
        addView(text(label, 12f, Palette.muted, Typeface.BOLD), LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(text(value, 12f, Palette.ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun statusPill(label: String, accent: Int): TextView = text(label, 11f, accent, Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = rounded(tint(accent, 0.12f), tint(accent, 0.40f), 99f)
    }

    private fun actionButton(action: VisionModelActionUiModel, onClick: () -> Unit): Button = Button(context).apply {
        text = action.label
        textSize = 13f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (action.destructive) 0xFFE15E64.toInt() else Palette.deepInk)
        background = rounded(
            if (action.destructive) tint(0xFFE15E64.toInt(), 0.08f) else Palette.mintPale,
            if (action.destructive) tint(0xFFE15E64.toInt(), 0.34f) else Palette.mint,
            14f
        )
        contentDescription = action.label
        setOnClickListener { onClick() }
    }

    private fun statusColor(status: VisionPackageStatus): Int = when (status) {
        VisionPackageStatus.READY -> Palette.mintDark
        VisionPackageStatus.LIMITED -> Palette.sky
        VisionPackageStatus.IMPORTING -> Palette.blue
        VisionPackageStatus.PAUSED -> Palette.lavender
        VisionPackageStatus.MISSING_FILES -> Palette.muted
        VisionPackageStatus.INCOMPATIBLE,
        VisionPackageStatus.FAILED -> 0xFFE15E64.toInt()
    }

    private fun text(value: String, sizeSp: Float, color: Int, style: Int = Typeface.NORMAL): TextView =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, style)
            setLineSpacing(0f, 1.08f)
        }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
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
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
