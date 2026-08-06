package ai.mobilecore.ui

/**
 * User-facing lifecycle for a local model. Downloading and loading are deliberately separate:
 * a file on disk is not advertised as an active runtime model until the runtime confirms it.
 */
enum class ModelLifecyclePhase {
    NOT_DOWNLOADED,
    DOWNLOADING,
    PAUSED,
    DOWNLOADED,
    LOADING,
    LOADED,
    DOWNLOAD_FAILED,
    LOAD_FAILED,
}

enum class ModelLifecycleTone {
    NEUTRAL,
    PROGRESS,
    READY,
    ACTIVE,
    WARNING,
    ERROR,
}

data class ModelLifecycleUiModel(
    val phase: ModelLifecyclePhase,
    val statusLabel: String,
    val supportingText: String,
    val actionLabel: String,
    val actionEnabled: Boolean,
    val tone: ModelLifecycleTone,
)

object ModelLifecyclePresenter {
    fun present(
        downloaded: Boolean,
        active: Boolean,
        loading: Boolean,
        downloadStatus: String? = null,
        loadFailed: Boolean = false,
        progressPercent: Int = 0,
    ): ModelLifecycleUiModel {
        val normalizedDownloadStatus = downloadStatus?.lowercase()
        val phase = when {
            active && downloaded -> ModelLifecyclePhase.LOADED
            loading && downloaded -> ModelLifecyclePhase.LOADING
            loadFailed && downloaded -> ModelLifecyclePhase.LOAD_FAILED
            normalizedDownloadStatus == "downloading" -> ModelLifecyclePhase.DOWNLOADING
            normalizedDownloadStatus == "paused" -> ModelLifecyclePhase.PAUSED
            normalizedDownloadStatus in setOf("failed", "cancelled") -> ModelLifecyclePhase.DOWNLOAD_FAILED
            downloaded -> ModelLifecyclePhase.DOWNLOADED
            else -> ModelLifecyclePhase.NOT_DOWNLOADED
        }
        return when (phase) {
            ModelLifecyclePhase.NOT_DOWNLOADED -> ModelLifecycleUiModel(
                phase, "未下载", "模型文件尚未保存在本机", "下载", true, ModelLifecycleTone.NEUTRAL,
            )
            ModelLifecyclePhase.DOWNLOADING -> ModelLifecycleUiModel(
                phase,
                "下载中 ${progressPercent.coerceIn(0, 100)}%",
                "正在写入应用模型库",
                "暂停",
                true,
                ModelLifecycleTone.PROGRESS,
            )
            ModelLifecyclePhase.PAUSED -> ModelLifecycleUiModel(
                phase, "已暂停", "下载进度已保留", "继续", true, ModelLifecycleTone.WARNING,
            )
            ModelLifecyclePhase.DOWNLOADED -> ModelLifecycleUiModel(
                phase, "已下载", "文件已在本机，尚未加载到内存", "加载", true, ModelLifecycleTone.READY,
            )
            ModelLifecyclePhase.LOADING -> ModelLifecycleUiModel(
                phase, "加载中", "运行时正在校验并映射模型", "加载中", false, ModelLifecycleTone.PROGRESS,
            )
            ModelLifecyclePhase.LOADED -> ModelLifecycleUiModel(
                phase, "已加载", "模型正在本机运行时中使用", "使用中", false, ModelLifecycleTone.ACTIVE,
            )
            ModelLifecyclePhase.DOWNLOAD_FAILED -> ModelLifecycleUiModel(
                phase, "下载失败", "未获得完整模型文件", "重新下载", true, ModelLifecycleTone.ERROR,
            )
            ModelLifecyclePhase.LOAD_FAILED -> ModelLifecycleUiModel(
                phase, "加载失败", "文件已下载，但运行时未能加载", "重试加载", true, ModelLifecycleTone.ERROR,
            )
        }
    }
}
