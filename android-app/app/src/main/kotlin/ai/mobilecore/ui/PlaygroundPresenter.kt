package ai.mobilecore.ui

import ai.mobilecore.playground.PlaygroundArtifactOrigin
import ai.mobilecore.playground.PlaygroundCatalogEntry

enum class PlaygroundLocalPhase {
    NOT_DOWNLOADED,
    PARTIAL,
    LOCAL_UNVERIFIED,
    ACTIVE_UNVERIFIED,
}

data class PlaygroundEntryUiModel(
    val id: String,
    val title: String,
    val metadata: String,
    val originLabel: String,
    val attributionLabel: String,
    val validationLabel: String,
    val validationPassed: Boolean,
    val localStatusLabel: String,
    val localStatusDetail: String,
    val localPhase: PlaygroundLocalPhase,
    val distributionLabel: String,
    val recommended: Boolean,
)

object PlaygroundPresenter {
    private val positiveValidationStates = setOf(
        "EMULATOR_CONTRACT_CHECKED",
        "DEVICE_VALIDATED",
        "QUALITY_VALIDATED",
        "PERFORMANCE_VALIDATED",
    )

    fun present(
        entry: PlaygroundCatalogEntry,
        localFileNames: Set<String>,
        activeModelFileName: String?,
    ): PlaygroundEntryUiModel {
        val required = entry.requiredArtifactNames
        val present = required.count { candidate -> localFileNames.any { it.equals(candidate, ignoreCase = true) } }
        val primary = entry.artifacts.firstOrNull { it.role == "mobile_runtime" }?.name
        val phase = when {
            primary != null &&
                primary.equals(activeModelFileName, ignoreCase = true) &&
                present == required.size -> PlaygroundLocalPhase.ACTIVE_UNVERIFIED
            present == 0 -> PlaygroundLocalPhase.NOT_DOWNLOADED
            present == required.size -> PlaygroundLocalPhase.LOCAL_UNVERIFIED
            else -> PlaygroundLocalPhase.PARTIAL
        }
        val status = when (phase) {
            PlaygroundLocalPhase.NOT_DOWNLOADED -> "未下载" to "本机没有发现清单中的模型文件"
            PlaygroundLocalPhase.PARTIAL -> "文件不完整" to "多文件模型缺少 ${required.size - present} 个组件"
            PlaygroundLocalPhase.LOCAL_UNVERIFIED -> "本地文件 · 待校验" to "文件已发现，尚未按 Playground SHA-256 校验"
            PlaygroundLocalPhase.ACTIVE_UNVERIFIED -> if (required.size > 1) {
                "主模型运行中 · 来源待校验" to
                    "主 GGUF 正在运行；投影组件仅确认存在，尚未确认已加载或匹配清单 SHA-256"
            } else {
                "运行中 · 来源待校验" to "当前运行时使用同名文件，尚未匹配清单 SHA-256"
            }
        }
        val metadata = listOf(entry.parameterLabel, entry.quantizationLabel)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val validationPassed = entry.state in positiveValidationStates &&
            entry.verifiedCapabilities.status == "pass"
        val recommended = entry.featured &&
            entry.source.licenseReview == "cleared" &&
            validationPassed
        val attribution = when (entry.origin) {
            PlaygroundArtifactOrigin.UPSTREAM -> "上游 ${entry.source.upstreamPublisher} · 官方 GGUF"
            PlaygroundArtifactOrigin.HARZVA,
            PlaygroundArtifactOrigin.THIRD_PARTY,
            PlaygroundArtifactOrigin.RECIPE ->
                "上游 ${entry.source.upstreamPublisher} · 转换者 ${entry.source.conversionPublisher}"
        }
        val distributionLabel = when {
            entry.distribution.mode == "huggingface_model_repo" && entry.distribution.downloadable ->
                "Hugging Face 已校验 · 提供固定直链"
            entry.distribution.downloadable -> "已发布 · 可直接下载"
            entry.distribution.mode == "gitcode_model_repo" &&
                entry.distribution.publicationState == "POST_PUBLISH_VERIFIED" &&
                entry.distribution.installTransport == "git_lfs_batch" ->
                "GitCode 已校验 · 直装待 LFS"
            entry.distribution.published -> "来源仓已发布 · 暂未开放直装"
            entry.distribution.publishable -> "已通过发布门禁 · 尚未发布"
            else -> "已收录 · 暂不可发布"
        }
        return PlaygroundEntryUiModel(
            id = entry.id,
            title = entry.displayName,
            metadata = metadata,
            originLabel = entry.origin.displayLabel,
            attributionLabel = attribution,
            validationLabel = entry.validationLabel,
            validationPassed = validationPassed,
            localStatusLabel = status.first,
            localStatusDetail = status.second,
            localPhase = phase,
            distributionLabel = distributionLabel,
            recommended = recommended,
        )
    }

    fun originAccessibilityLabel(origin: PlaygroundArtifactOrigin): String = when (origin) {
        PlaygroundArtifactOrigin.HARZVA -> "由 Harzva 转换"
        PlaygroundArtifactOrigin.THIRD_PARTY -> "由第三方转换，保留原转换者署名"
        PlaygroundArtifactOrigin.UPSTREAM -> "由模型官方上游发布"
        PlaygroundArtifactOrigin.RECIPE -> "仅提供转换配方"
    }
}
