package ai.mobilecore.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GallerySearchStateMachineTest {
    @Test
    fun `permission scan and indexing form an explicit ready path`() {
        var state = GallerySearchState()

        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.AccessGranted)
        assertEquals(GalleryIndexState.Scanning(0), state.index)

        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.ScanProgress(42))
        assertEquals(GalleryIndexState.Scanning(42), state.index)

        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.ScanCompleted(120))
        assertEquals(GalleryIndexState.Indexing(0, 120), state.index)

        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.IndexProgress(63, 120))
        assertEquals(GalleryIndexState.Indexing(63, 120), state.index)

        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.IndexCompleted(118, 9_000L))
        assertEquals(GalleryIndexState.Ready(118, 9_000L), state.index)
    }

    @Test
    fun `empty granted gallery becomes ready but cannot search`() {
        var state = GallerySearchState()
        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.AccessGranted)
        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.ScanCompleted(0, 1_000L))
        state = GallerySearchStateMachine.reduce(
            state,
            GallerySearchEvent.ModelsReady("MobileCLIP image", "MobileCLIP text", "Qwen 0.8B"),
        )

        assertEquals(GalleryIndexState.Ready(0, 1_000L), state.index)
        assertFalse(state.canSearch)
        assertTrue(GallerySearchPresenter.present(state).resultMessage.contains("没有发现"))

        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.RetryIndex)
        assertEquals(GalleryIndexState.Scanning(0), state.index)
    }

    @Test
    fun `restart distinguishes granted access from a validated persisted index`() {
        var state = GallerySearchStateMachine.reduce(
            GallerySearchState(),
            GallerySearchEvent.AccessAvailable(persistedIndexDetected = true),
        )
        assertEquals(GalleryIndexState.AccessGranted(true), state.index)
        assertFalse(state.canSearch)

        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.IndexRestored(37, 12_000L))
        assertEquals(GalleryIndexState.Ready(37, 12_000L), state.index)

        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.IndexCleared)
        assertEquals(GalleryIndexState.AccessGranted(false), state.index)
        assertFalse(state.canSearch)
    }

    @Test
    fun `index progress and completion preserve skipped media count`() {
        var state = GallerySearchStateMachine.reduce(GallerySearchState(), GallerySearchEvent.AccessGranted)
        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.ScanCompleted(10))
        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.IndexProgress(7, 10, skippedCount = 2))
        assertEquals(GalleryIndexState.Indexing(7, 10, 2), state.index)

        state = GallerySearchStateMachine.reduce(
            state,
            GallerySearchEvent.IndexCompleted(8, 15_000L, skippedCount = 2),
        )
        assertEquals(GalleryIndexState.Ready(8, 15_000L, 2), state.index)
        assertTrue(GallerySearchPresenter.present(state).indexStatus.detail.contains("跳过 2 张"))
    }

    @Test
    fun `released clip memory preserves index readiness but blocks search`() {
        val ready = readyState("小狗")
        val released = GallerySearchStateMachine.reduce(
            ready,
            GallerySearchEvent.ModelsReleased("为 GGUF 释放内存"),
        )

        assertEquals(GalleryIndexState.Ready(100), released.index)
        assertTrue(released.models is GalleryModelState.Released)
        assertFalse(released.canSearch)
        assertEquals(
            GalleryStatusAction.PREPARE_MODELS,
            GallerySearchPresenter.present(released).modelStatus.action,
        )
    }

    @Test
    fun `photo access scope is explicit and revoked access clears limited state`() {
        var state = GallerySearchStateMachine.reduce(
            GallerySearchState(),
            GallerySearchEvent.PhotoAccessScopeChanged(limited = true),
        )
        assertTrue(state.limitedPhotoAccess)

        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.AccessRevoked)

        assertFalse(state.limitedPhotoAccess)
        assertEquals(GalleryIndexState.PermissionRequired, state.index)
    }

    @Test
    fun `model progress is clamped and g2d readiness is visible`() {
        var state = GallerySearchState()
        state = GallerySearchStateMachine.reduce(
            state,
            GallerySearchEvent.ModelPreparationStarted("MobileCLIP 文本编码器"),
        )
        state = GallerySearchStateMachine.reduce(
            state,
            GallerySearchEvent.ModelPreparationProgress("MobileCLIP 文本编码器", 140),
        )
        assertEquals(100, (state.models as GalleryModelState.Preparing).progressPercent)

        state = GallerySearchStateMachine.reduce(
            state,
            GallerySearchEvent.ModelsReady("MobileCLIP-S2 image", "MobileCLIP-S2 text", "Qwen3.5-0.8B"),
        )
        val ui = GallerySearchPresenter.present(state)

        assertTrue(ui.modelStatus.isSuccess)
        assertTrue(ui.modelStatus.title.contains("G2D"))
        assertTrue(ui.modelStatus.detail.contains("Qwen3.5-0.8B"))
    }

    @Test
    fun `model ready event retains audited identity instead of inferring it from filenames`() {
        val state = GallerySearchStateMachine.reduce(
            GallerySearchState(),
            GallerySearchEvent.ModelsReady(
                clipImageEncoder = "expected-name.onnx",
                clipTextEncoder = "expected-name-text.onnx",
                modelId = "audited/model",
                identityVerified = true,
            ),
        )

        val ready = state.models as GalleryModelState.Ready
        assertEquals("audited/model", ready.modelId)
        assertTrue(ready.identityVerified)
    }

    @Test
    fun `search is ignored until index and clip models are ready`() {
        val blocked = GallerySearchState(query = "海边穿红衣服的人")

        val unchanged = GallerySearchStateMachine.reduce(blocked, GallerySearchEvent.SearchStarted)

        assertSame(blocked, unchanged)

        val ready = blocked.copy(
            index = GalleryIndexState.Ready(500),
            models = GalleryModelState.Ready("image", "text"),
        )
        val searching = GallerySearchStateMachine.reduce(ready, GallerySearchEvent.SearchStarted)

        assertEquals(GalleryRetrievalState.Searching("海边穿红衣服的人"), searching.retrieval)
        assertFalse(GallerySearchPresenter.present(searching).searchEnabled)
    }

    @Test
    fun `stale asynchronous search results cannot replace current query`() {
        var state = readyState("小狗")
        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.SearchStarted)
        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.QueryChanged("会议柱状图"))
        state = GallerySearchStateMachine.reduce(
            state,
            GallerySearchEvent.SearchCompleted("小狗", listOf(result("old", 0.95, GalleryResultSource.CLIP_DIRECT))),
        )

        assertEquals(GalleryRetrievalState.Idle, state.retrieval)
        assertEquals("会议柱状图", state.query)
    }

    @Test
    fun `index failures expose retry only when allowed`() {
        val failed = GallerySearchStateMachine.reduce(
            GallerySearchState(),
            GallerySearchEvent.IndexFailed("可用存储空间不足", retryable = true),
        )
        val ui = GallerySearchPresenter.present(failed)

        assertEquals(GalleryStatusAction.RETRY_INDEX, ui.indexStatus.action)
        assertEquals("重新建立索引", ui.indexStatus.actionLabel)

        val retrying = GallerySearchStateMachine.reduce(failed, GallerySearchEvent.RetryIndex)
        assertEquals(GalleryIndexState.Scanning(0), retrying.index)

        val terminal = failed.copy(index = GalleryIndexState.Failed("访问被系统策略禁止", retryable = false))
        assertSame(terminal, GallerySearchStateMachine.reduce(terminal, GallerySearchEvent.RetryIndex))
    }

    private fun readyState(query: String): GallerySearchState = GallerySearchState(
        index = GalleryIndexState.Ready(100),
        models = GalleryModelState.Ready("image", "text", "verifier"),
        query = query,
    )

    private fun result(id: String, score: Double, source: GalleryResultSource) = GallerySearchResult(
        mediaId = id,
        contentUri = "content://photos/$id",
        title = "照片 $id",
        subtitle = "2026-07-18",
        similarity = score,
        source = source,
    )
}
