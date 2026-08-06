package ai.mobilecore.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLifecyclePresenterTest {
    @Test
    fun `downloaded model is not reported as loaded`() {
        val model = ModelLifecyclePresenter.present(downloaded = true, active = false, loading = false)

        assertEquals(ModelLifecyclePhase.DOWNLOADED, model.phase)
        assertEquals("已下载", model.statusLabel)
        assertEquals("加载", model.actionLabel)
        assertTrue(model.actionEnabled)
    }

    @Test
    fun `only runtime confirmed active model is reported as loaded`() {
        val model = ModelLifecyclePresenter.present(downloaded = true, active = true, loading = false)

        assertEquals(ModelLifecyclePhase.LOADED, model.phase)
        assertEquals("已加载", model.statusLabel)
        assertFalse(model.actionEnabled)
    }

    @Test
    fun `loading and load failure remain distinct from download state`() {
        val loading = ModelLifecyclePresenter.present(downloaded = true, active = false, loading = true)
        val failed = ModelLifecyclePresenter.present(
            downloaded = true,
            active = false,
            loading = false,
            loadFailed = true,
        )

        assertEquals(ModelLifecyclePhase.LOADING, loading.phase)
        assertEquals(ModelLifecyclePhase.LOAD_FAILED, failed.phase)
        assertEquals("重试加载", failed.actionLabel)
    }

    @Test
    fun `download progress has its own user facing state`() {
        val model = ModelLifecyclePresenter.present(
            downloaded = false,
            active = false,
            loading = false,
            downloadStatus = "downloading",
            progressPercent = 37,
        )

        assertEquals(ModelLifecyclePhase.DOWNLOADING, model.phase)
        assertEquals("下载中 37%", model.statusLabel)
        assertEquals("暂停", model.actionLabel)
    }
}
