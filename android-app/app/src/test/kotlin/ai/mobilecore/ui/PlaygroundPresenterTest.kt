package ai.mobilecore.ui

import ai.mobilecore.playground.PlaygroundCatalogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaygroundPresenterTest {
    private val catalog by lazy {
        PlaygroundCatalogParser.parse(
            File("src/main/assets/mobile-model-playground/catalog-v1.json").readText()
        )
    }

    @Test
    fun `model lifecycle distinguishes absent local and loaded`() {
        val entry = catalog.entries.first { it.id == "qwen2.5-0.5b-instruct-q4-k-m-official" }
        val fileName = entry.requiredArtifactNames.single()

        val absent = PlaygroundPresenter.present(entry, emptySet(), null)
        assertEquals(PlaygroundLocalPhase.NOT_DOWNLOADED, absent.localPhase)
        assertEquals("未下载", absent.localStatusLabel)

        val local = PlaygroundPresenter.present(entry, setOf(fileName), null)
        assertEquals(PlaygroundLocalPhase.LOCAL_UNVERIFIED, local.localPhase)
        assertEquals("本地文件 · 待校验", local.localStatusLabel)

        val active = PlaygroundPresenter.present(entry, setOf(fileName), fileName)
        assertEquals(PlaygroundLocalPhase.ACTIVE_UNVERIFIED, active.localPhase)
        assertEquals("运行中 · 来源待校验", active.localStatusLabel)
    }

    @Test
    fun `quality failed third party model is never recommended`() {
        val entry = catalog.entries.first { it.id == "qwen3.5-0.8b-q4-k-m-bartowski" }
        val model = PlaygroundPresenter.present(entry, entry.requiredArtifactNames, null)
        assertFalse(model.recommended)
        assertEquals("第三方转换", model.originLabel)
        assertTrue(model.attributionLabel.contains("上游 Qwen"))
        assertTrue(model.attributionLabel.contains("转换者 bartowski"))
        assertFalse(model.validationPassed)
    }

    @Test
    fun `multi artifact model is partial until projector is present`() {
        val entry = catalog.entries.first { it.id == "qwen3.5-0.8b-q4-k-m-bartowski" }
        val primary = entry.artifacts.first { it.role == "mobile_runtime" }.name

        val partial = PlaygroundPresenter.present(entry, setOf(primary), primary)
        assertEquals(PlaygroundLocalPhase.PARTIAL, partial.localPhase)
        assertEquals("文件不完整", partial.localStatusLabel)

        val active = PlaygroundPresenter.present(entry, entry.requiredArtifactNames, primary)
        assertEquals(PlaygroundLocalPhase.ACTIVE_UNVERIFIED, active.localPhase)
        assertTrue(active.localStatusLabel.contains("来源待校验"))
        assertTrue(active.localStatusDetail.contains("投影组件"))
    }

    @Test
    fun `harzva conversion remains visibly distinct`() {
        val entry = catalog.entries.first { it.id == "qwen3-0.6b-q4-k-m" }
        val model = PlaygroundPresenter.present(entry, emptySet(), null)
        assertEquals("Harzva 转换", model.originLabel)
        assertTrue(model.recommended)
        assertTrue(model.attributionLabel.contains("上游 Qwen"))
        assertTrue(model.attributionLabel.contains("转换者 Harzva"))
        assertEquals("Hugging Face 已校验 · 提供固定直链", model.distributionLabel)
    }

    @Test
    fun `featured entry still needs cleared license and verified capabilities`() {
        val base = catalog.entries.first { it.id == "qwen2.5-omni-3b-q4-k-m-ggml-org" }
        val featured = base.copy(featured = true)
        val model = PlaygroundPresenter.present(featured, emptySet(), null)
        assertFalse(model.recommended)
        assertFalse(model.validationPassed)
    }
}
