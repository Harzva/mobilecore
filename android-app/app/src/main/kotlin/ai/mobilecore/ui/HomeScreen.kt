package ai.mobilecore.ui

enum class StandardModelDownloadPhase {
    IDLE,
    DOWNLOADING,
    PAUSED,
    FAILED,
    COMPLETE
}

data class StandardModelDownloadUiModel(
    val phase: StandardModelDownloadPhase,
    val title: String,
    val progressPercent: Int,
    val progressLabel: String,
    val remainingLabel: String,
    val actionLabel: String,
    val actionEnabled: Boolean
)

object HomeScreenPresenter {
    fun standardModelDownload(
        phase: StandardModelDownloadPhase,
        bytesDownloaded: Long,
        totalBytes: Long,
        startedAtMs: Long,
        startedBytes: Long,
        nowMs: Long
    ): StandardModelDownloadUiModel {
        val percent = if (totalBytes > 0L) {
            ((bytesDownloaded.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val remainingMs = estimateRemainingMs(bytesDownloaded, totalBytes, startedAtMs, startedBytes, nowMs)
        val title = when (phase) {
            StandardModelDownloadPhase.IDLE -> "下载标准模型"
            StandardModelDownloadPhase.DOWNLOADING -> "正在下载标准模型"
            StandardModelDownloadPhase.PAUSED -> "标准模型下载已暂停"
            StandardModelDownloadPhase.FAILED -> "标准模型下载失败"
            StandardModelDownloadPhase.COMPLETE -> "标准模型已下载"
        }
        val action = when (phase) {
            StandardModelDownloadPhase.IDLE -> "开始下载"
            StandardModelDownloadPhase.DOWNLOADING -> "暂停下载"
            StandardModelDownloadPhase.PAUSED -> "继续下载"
            StandardModelDownloadPhase.FAILED -> "重新下载"
            StandardModelDownloadPhase.COMPLETE -> "加载模型"
        }
        return StandardModelDownloadUiModel(
            phase = phase,
            title = title,
            progressPercent = percent,
            progressLabel = "${formatBytes(bytesDownloaded)} / ${if (totalBytes > 0L) formatBytes(totalBytes) else "约 469 MB"}",
            remainingLabel = when (phase) {
                StandardModelDownloadPhase.DOWNLOADING -> "预计剩余 ${formatRemainingDuration(remainingMs)}"
                StandardModelDownloadPhase.PAUSED -> "已保留下载进度"
                StandardModelDownloadPhase.FAILED -> "检查网络后可继续"
                StandardModelDownloadPhase.COMPLETE -> "文件已保存到本机，尚未加载"
                StandardModelDownloadPhase.IDLE -> "Qwen2.5 0.5B · 约 469 MB"
            },
            actionLabel = action,
            actionEnabled = true
        )
    }

    private fun estimateRemainingMs(
        downloaded: Long,
        total: Long,
        startedAtMs: Long,
        startedBytes: Long,
        nowMs: Long
    ): Long? {
        if (total <= downloaded || startedAtMs <= 0L || nowMs <= startedAtMs) return null
        val transferred = (downloaded - startedBytes).coerceAtLeast(0L)
        if (transferred <= 0L) return null
        val bytesPerMs = transferred.toDouble() / (nowMs - startedAtMs).toDouble()
        if (bytesPerMs <= 0.0) return null
        return ((total - downloaded) / bytesPerMs).toLong().coerceAtLeast(1L)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) "%.1f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
    }
}
