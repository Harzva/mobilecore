package ai.mobilecore.ui

import ai.mobilecore.playground.PlaygroundArtifactOrigin
import ai.mobilecore.playground.PlaygroundCatalogEntry
import ai.mobilecore.playground.PlaygroundInstallFailureCode
import ai.mobilecore.playground.PlaygroundInstallPhase
import ai.mobilecore.playground.PlaygroundInstallSnapshot
import java.io.File

enum class PlaygroundLocalPhase {
    NOT_DOWNLOADED,
    PREFLIGHT,
    DOWNLOADING,
    VERIFYING,
    INSTALLED,
    LOADING,
    LOADED,
    VERIFICATION_FAILED,
    SOURCE_MISMATCH,
    DOWNLOAD_FAILED,
    INSUFFICIENT_STORAGE,
    ARTIFACT_MISSING,
    ATOMIC_INSTALL_FAILED,
    UNINSTALL_FAILED,
    VERIFICATION_IO_FAILED,
    LOAD_FAILED,
    CANCELLED,
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
    val progressPercent: Int,
    val primaryActionLabel: String,
    val primaryActionEnabled: Boolean,
    val canUninstall: Boolean,
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
        activeModelPath: String?,
        managedPrimaryModelPath: String? = null,
        installSnapshot: PlaygroundInstallSnapshot? = null,
    ): PlaygroundEntryUiModel {
        val required = entry.requiredArtifactNames
        val present = required.count { candidate -> localFileNames.any { it.equals(candidate, ignoreCase = true) } }
        val primary = entry.artifacts.firstOrNull { it.role == "mobile_runtime" }?.name
        val activeModelFileName = activeModelPath?.let { File(it).name }
        val activeTrustedModel = installSnapshot?.verified == true &&
            sameCanonicalPath(activeModelPath, managedPrimaryModelPath)
        val discoveredPhase = when {
            primary != null &&
                primary.equals(activeModelFileName, ignoreCase = true) &&
                present == required.size -> PlaygroundLocalPhase.ACTIVE_UNVERIFIED
            present == 0 -> PlaygroundLocalPhase.NOT_DOWNLOADED
            present == required.size -> PlaygroundLocalPhase.LOCAL_UNVERIFIED
            else -> PlaygroundLocalPhase.PARTIAL
        }
        val phase = installSnapshot?.let { snapshot ->
            when (snapshot.phase) {
                PlaygroundInstallPhase.NOT_DOWNLOADED,
                PlaygroundInstallPhase.UNINSTALLED,
                -> PlaygroundLocalPhase.NOT_DOWNLOADED
                PlaygroundInstallPhase.PREFLIGHT -> PlaygroundLocalPhase.PREFLIGHT
                PlaygroundInstallPhase.DOWNLOADING -> PlaygroundLocalPhase.DOWNLOADING
                PlaygroundInstallPhase.VERIFYING -> PlaygroundLocalPhase.VERIFYING
                PlaygroundInstallPhase.INSTALLED -> when {
                    !snapshot.verified -> discoveredPhase
                    activeTrustedModel -> PlaygroundLocalPhase.LOADED
                    else -> PlaygroundLocalPhase.INSTALLED
                }
                PlaygroundInstallPhase.LOADING -> if (snapshot.verified) {
                    PlaygroundLocalPhase.LOADING
                } else {
                    discoveredPhase
                }
                PlaygroundInstallPhase.LOADED -> when {
                    !snapshot.verified -> discoveredPhase
                    activeTrustedModel -> PlaygroundLocalPhase.LOADED
                    else -> PlaygroundLocalPhase.INSTALLED
                }
                PlaygroundInstallPhase.VERIFICATION_FAILED ->
                    if (snapshot.failure?.code == PlaygroundInstallFailureCode.VERIFICATION_IO_FAILED) {
                        PlaygroundLocalPhase.VERIFICATION_IO_FAILED
                    } else {
                        PlaygroundLocalPhase.VERIFICATION_FAILED
                    }
                PlaygroundInstallPhase.SOURCE_MISMATCH -> PlaygroundLocalPhase.SOURCE_MISMATCH
                PlaygroundInstallPhase.CANCELLED -> PlaygroundLocalPhase.CANCELLED
                PlaygroundInstallPhase.FAILED -> when (snapshot.failure?.code) {
                    PlaygroundInstallFailureCode.MODEL_LOAD_FAILED -> PlaygroundLocalPhase.LOAD_FAILED
                    PlaygroundInstallFailureCode.SOURCE_MISMATCH -> PlaygroundLocalPhase.SOURCE_MISMATCH
                    PlaygroundInstallFailureCode.CHECKSUM_MISMATCH,
                    PlaygroundInstallFailureCode.SIZE_MISMATCH,
                    -> PlaygroundLocalPhase.VERIFICATION_FAILED
                    PlaygroundInstallFailureCode.VERIFICATION_IO_FAILED -> PlaygroundLocalPhase.VERIFICATION_IO_FAILED
                    PlaygroundInstallFailureCode.INSUFFICIENT_STORAGE -> PlaygroundLocalPhase.INSUFFICIENT_STORAGE
                    PlaygroundInstallFailureCode.ARTIFACT_MISSING -> PlaygroundLocalPhase.ARTIFACT_MISSING
                    PlaygroundInstallFailureCode.ATOMIC_INSTALL_FAILED -> PlaygroundLocalPhase.ATOMIC_INSTALL_FAILED
                    PlaygroundInstallFailureCode.UNINSTALL_FAILED -> PlaygroundLocalPhase.UNINSTALL_FAILED
                    else -> PlaygroundLocalPhase.DOWNLOAD_FAILED
                }
            }
        } ?: discoveredPhase
        val status = when (phase) {
            PlaygroundLocalPhase.NOT_DOWNLOADED -> "未下载" to "本机没有发现清单中的模型文件"
            PlaygroundLocalPhase.PREFLIGHT -> "检查安装空间" to "正在确认私有目录可容纳模型与安全余量"
            PlaygroundLocalPhase.DOWNLOADING -> "下载中 · ${installSnapshot?.progressPercent ?: 0}%" to
                "正在写入可续传临时文件；尚未作为模型安装"
            PlaygroundLocalPhase.VERIFYING -> "校验中" to "正在核对精确字节数与 SHA-256，完成前不会加载"
            PlaygroundLocalPhase.INSTALLED -> "已安装 · 已校验" to "固定来源、字节数和 SHA-256 均匹配，可安全加载"
            PlaygroundLocalPhase.LOADING -> "加载中" to "已校验模型正在交给本地 llama.cpp 运行时"
            PlaygroundLocalPhase.LOADED -> "已加载" to "当前运行时正在使用这份已校验模型"
            PlaygroundLocalPhase.VERIFICATION_FAILED -> "校验失败" to
                "下载文件与清单字节数或 SHA-256 不一致，临时文件已清理"
            PlaygroundLocalPhase.SOURCE_MISMATCH -> "来源不匹配" to
                "发现同名但未经该清单验证的文件；不会覆盖或加载"
            PlaygroundLocalPhase.DOWNLOAD_FAILED -> "下载失败 · 可续传" to
                "网络中断时保留受限 .part 文件；再次下载会从断点继续"
            PlaygroundLocalPhase.INSUFFICIENT_STORAGE -> "存储空间不足" to
                "可用空间低于模型字节数与安全余量；未开始网络传输"
            PlaygroundLocalPhase.ARTIFACT_MISSING -> "安装文件缺失" to
                "清单要求的受管文件已不存在；不会把残留文件视为完整安装"
            PlaygroundLocalPhase.ATOMIC_INSTALL_FAILED -> "原子安装失败" to
                "校验后的临时文件未能原子切换为正式模型；不会加载半安装文件"
            PlaygroundLocalPhase.UNINSTALL_FAILED -> "卸载未完成" to
                "至少一个受管文件无法删除；当前状态不会显示为已卸载"
            PlaygroundLocalPhase.VERIFICATION_IO_FAILED -> "校验读取失败" to
                "完整 SHA-256 校验或校验记录提交未完成；模型保持未受信任"
            PlaygroundLocalPhase.LOAD_FAILED -> "加载失败" to "模型已校验安装，但本地运行时未能加载"
            PlaygroundLocalPhase.CANCELLED -> "已取消" to
                "当前下载或校验已停止；临时下载已清理，正式安装文件未被删除"
            PlaygroundLocalPhase.PARTIAL -> "文件不完整" to "多文件模型缺少 ${required.size - present} 个组件"
            PlaygroundLocalPhase.LOCAL_UNVERIFIED -> "本地文件 · 待校验" to "文件已发现，尚未按 Playground SHA-256 校验"
            PlaygroundLocalPhase.ACTIVE_UNVERIFIED -> if (required.size > 1) {
                "主模型运行中 · 来源待校验" to
                    "主 GGUF 正在运行；投影组件仅确认存在，尚未确认已加载或匹配清单 SHA-256"
            } else {
                "运行中 · 来源待校验" to "当前运行时使用同名文件，尚未匹配清单 SHA-256"
            }
        }
        val action = when (phase) {
            PlaygroundLocalPhase.NOT_DOWNLOADED,
            PlaygroundLocalPhase.DOWNLOAD_FAILED,
            -> "下载并校验" to true
            PlaygroundLocalPhase.CANCELLED -> if (installSnapshot?.installedArtifactNames?.isNotEmpty() == true) {
                "重新校验" to true
            } else {
                "下载并校验" to true
            }
            PlaygroundLocalPhase.VERIFICATION_FAILED -> if (installSnapshot?.installedArtifactNames?.isNotEmpty() == true) {
                "移除错误文件" to true
            } else {
                "重新下载并校验" to true
            }
            PlaygroundLocalPhase.PREFLIGHT -> "正在检查空间" to false
            PlaygroundLocalPhase.DOWNLOADING -> "取消下载" to true
            PlaygroundLocalPhase.VERIFYING -> "取消校验" to true
            PlaygroundLocalPhase.INSTALLED,
            PlaygroundLocalPhase.LOAD_FAILED,
            -> "加载模型" to true
            PlaygroundLocalPhase.LOADING -> "正在加载" to false
            PlaygroundLocalPhase.LOADED -> "模型运行中" to false
            PlaygroundLocalPhase.SOURCE_MISMATCH -> "移除错误文件" to true
            PlaygroundLocalPhase.INSUFFICIENT_STORAGE -> "重新检查空间" to true
            PlaygroundLocalPhase.ARTIFACT_MISSING -> if (installSnapshot?.installedArtifactNames?.isNotEmpty() == true) {
                "移除残留文件" to true
            } else {
                "重新下载并校验" to true
            }
            PlaygroundLocalPhase.ATOMIC_INSTALL_FAILED -> "重试原子安装" to true
            PlaygroundLocalPhase.UNINSTALL_FAILED -> "重试卸载" to true
            PlaygroundLocalPhase.VERIFICATION_IO_FAILED -> "重新校验" to true
            PlaygroundLocalPhase.PARTIAL,
            PlaygroundLocalPhase.LOCAL_UNVERIFIED,
            PlaygroundLocalPhase.ACTIVE_UNVERIFIED,
            -> "查看本机文件" to false
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
            progressPercent = installSnapshot?.progressPercent ?: 0,
            primaryActionLabel = action.first,
            primaryActionEnabled = action.second,
            canUninstall = installSnapshot != null && phase in setOf(
                PlaygroundLocalPhase.INSTALLED,
                PlaygroundLocalPhase.LOAD_FAILED,
                PlaygroundLocalPhase.LOADED,
                PlaygroundLocalPhase.UNINSTALL_FAILED,
            ),
        )
    }

    fun originAccessibilityLabel(origin: PlaygroundArtifactOrigin): String = when (origin) {
        PlaygroundArtifactOrigin.HARZVA -> "由 Harzva 转换"
        PlaygroundArtifactOrigin.THIRD_PARTY -> "由第三方转换，保留原转换者署名"
        PlaygroundArtifactOrigin.UPSTREAM -> "由模型官方上游发布"
        PlaygroundArtifactOrigin.RECIPE -> "仅提供转换配方"
    }

    private fun sameCanonicalPath(first: String?, second: String?): Boolean {
        if (first.isNullOrBlank() || second.isNullOrBlank()) return false
        val canonicalFirst = runCatching { File(first).canonicalPath }.getOrNull() ?: return false
        val canonicalSecond = runCatching { File(second).canonicalPath }.getOrNull() ?: return false
        return canonicalFirst == canonicalSecond
    }
}
