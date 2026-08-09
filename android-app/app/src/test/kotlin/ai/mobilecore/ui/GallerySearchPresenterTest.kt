package ai.mobilecore.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GallerySearchPresenterTest {
    @Test
    fun `top k results are ranked and disclose clip versus g2d source`() {
        var state = readyState("红色衣服")
        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.SearchStarted)
        state = GallerySearchStateMachine.reduce(
            state,
            GallerySearchEvent.SearchCompleted(
                "红色衣服",
                listOf(
                    result("b", 0.71, GalleryResultSource.G2D_VERIFIED),
                    result("a", 0.93, GalleryResultSource.CLIP_DIRECT),
                    result("c", 0.64, GalleryResultSource.CLIP_DIRECT),
                ),
            ),
        )

        val ui = GallerySearchPresenter.present(state, topK = 2)

        assertEquals(listOf("a", "b"), ui.results.map { it.mediaId })
        assertEquals(listOf(1, 2), ui.results.map { it.rank })
        assertEquals("CLIP 直出", ui.results[0].sourceLabel)
        assertTrue(ui.results[0].sourceDetail.contains("未应用校准阈值"))
        assertTrue(ui.results[0].scoreLabel.startsWith("余弦 "))
        assertEquals("G2D 复核", ui.results[1].sourceLabel)
        assertTrue(ui.results[1].sourceDetail.contains("候选集"))
        assertEquals("Top-2 结果", ui.resultTitle)
    }

    @Test
    fun `duplicate media ids are removed before rendering`() {
        var state = readyState("白色小狗")
        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.SearchStarted)
        state = GallerySearchStateMachine.reduce(
            state,
            GallerySearchEvent.SearchCompleted(
                "白色小狗",
                listOf(
                    result("same", 0.82, GalleryResultSource.G2D_VERIFIED),
                    result("same", 0.90, GalleryResultSource.CLIP_DIRECT),
                ),
            ),
        )

        val results = GallerySearchPresenter.present(state).results
        assertEquals(1, results.size)
        assertEquals("CLIP 直出", results.single().sourceLabel)
    }

    @Test
    fun `no-match state is explicit and privacy copy never implies cloud upload`() {
        var state = readyState("月球上的猫")
        state = GallerySearchStateMachine.reduce(state, GallerySearchEvent.SearchStarted)
        state = GallerySearchStateMachine.reduce(
            state,
            GallerySearchEvent.SearchCompleted("月球上的猫", emptyList()),
        )

        val ui = GallerySearchPresenter.present(state)

        assertTrue(ui.showNoMatch)
        assertTrue(ui.resultMessage.contains("没有返回"))
        assertTrue(ui.privacyLabel.startsWith("MobileCore 不上传"))
        assertTrue(ui.privacyLabel.contains("照片、查询或索引"))
        assertFalse(ui.isSearching)
    }

    @Test
    fun `permission and model preparation remain visible before search`() {
        val ui = GallerySearchPresenter.present(GallerySearchState())

        assertEquals(GalleryStatusAction.REQUEST_ACCESS, ui.indexStatus.action)
        assertEquals(GalleryStatusAction.PREPARE_MODELS, ui.modelStatus.action)
        assertFalse(ui.queryEnabled)
        assertFalse(ui.searchEnabled)
        assertTrue(ui.searchHint.contains("索引"))
    }

    @Test
    fun `clip-only readiness supports search while explaining missing verifier`() {
        val state = GallerySearchState(
            index = GalleryIndexState.Ready(320),
            models = GalleryModelState.Ready("MobileCLIP image", "MobileCLIP text"),
            query = "会议投影里的柱状图",
        )

        val ui = GallerySearchPresenter.present(state)

        assertTrue(ui.queryEnabled)
        assertTrue(ui.searchEnabled)
        assertTrue(ui.modelStatus.detail.contains("G2D 复核未启用"))
        assertFalse(ui.searchHint.contains("文字"))
        assertEquals(GalleryStatusAction.RETRY_INDEX, ui.indexStatus.action)
        assertEquals(GalleryStatusAction.CLEAR_INDEX, ui.indexStatus.secondaryAction)
        assertEquals(GalleryStatusAction.RELEASE_MODELS, ui.modelStatus.action)
        assertTrue(ui.modelStatus.detail.contains("身份未验证"))
        assertTrue(ui.modelStatus.eyebrow.contains("身份未验证"))
    }

    @Test
    fun `verified clip identity is preserved while limited access offers system reselection`() {
        val state = GallerySearchState(
            index = GalleryIndexState.Ready(12),
            models = GalleryModelState.Ready(
                clipImageEncoder = "image.onnx",
                clipTextEncoder = "text.onnx",
                modelId = "onnx-community/clip-vit-base-patch16-ONNX",
                identityVerified = true,
            ),
            limitedPhotoAccess = true,
        )

        val ui = GallerySearchPresenter.present(state)

        assertTrue(ui.modelStatus.detail.contains("身份已验证"))
        assertTrue(ui.modelStatus.eyebrow.contains("身份已验证"))
        assertTrue(ui.modelStatus.detail.contains("onnx-community/clip-vit-base-patch16-ONNX"))
        assertEquals(GalleryStatusAction.SELECT_MORE_PHOTOS, ui.indexStatus.action)
        assertEquals("选择更多并更新索引", ui.indexStatus.actionLabel)
    }

    private fun readyState(query: String): GallerySearchState = GallerySearchState(
        index = GalleryIndexState.Ready(800),
        models = GalleryModelState.Ready("MobileCLIP image", "MobileCLIP text", "Qwen 0.8B"),
        query = query,
    )

    private fun result(id: String, score: Double, source: GalleryResultSource) = GallerySearchResult(
        mediaId = id,
        contentUri = "content://photos/$id",
        title = "照片 $id",
        subtitle = "2026-07-18 · 上海",
        similarity = score,
        source = source,
    )
}
