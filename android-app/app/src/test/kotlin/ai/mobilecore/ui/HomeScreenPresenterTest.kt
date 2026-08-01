package ai.mobilecore.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenPresenterTest {
    @Test
    fun `download presenter calculates progress and remaining time`() {
        val model = HomeScreenPresenter.standardModelDownload(
            phase = StandardModelDownloadPhase.DOWNLOADING,
            bytesDownloaded = 300L,
            totalBytes = 600L,
            startedAtMs = 1_000L,
            startedBytes = 100L,
            nowMs = 3_000L
        )

        assertEquals(50, model.progressPercent)
        assertEquals("暂停下载", model.actionLabel)
        assertTrue(model.remainingLabel.contains("3 秒"))
    }

    @Test
    fun `download phases keep the next action on the home surface`() {
        val paused = HomeScreenPresenter.standardModelDownload(
            phase = StandardModelDownloadPhase.PAUSED,
            bytesDownloaded = 366L,
            totalBytes = 469L,
            startedAtMs = 1_000L,
            startedBytes = 0L,
            nowMs = 2_000L
        )
        val complete = HomeScreenPresenter.standardModelDownload(
            phase = StandardModelDownloadPhase.COMPLETE,
            bytesDownloaded = 469L,
            totalBytes = 469L,
            startedAtMs = 1_000L,
            startedBytes = 0L,
            nowMs = 2_000L
        )

        assertEquals("继续下载", paused.actionLabel)
        assertEquals("已保留下载进度", paused.remainingLabel)
        assertEquals("开始标准测试", complete.actionLabel)
        assertEquals(100, complete.progressPercent)
    }
}
