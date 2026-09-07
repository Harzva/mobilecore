package ai.mobilecore.ui

import ai.mobilecore.playground.PlaygroundCatalogParser
import ai.mobilecore.playground.PlaygroundInstallFailure
import ai.mobilecore.playground.PlaygroundInstallFailureCode
import ai.mobilecore.playground.PlaygroundInstallPhase
import ai.mobilecore.playground.PlaygroundInstallSnapshot
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

        val active = PlaygroundPresenter.present(entry, setOf(fileName), File("/ordinary/$fileName").path)
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

        val partial = PlaygroundPresenter.present(entry, setOf(primary), File("/ordinary/$primary").path)
        assertEquals(PlaygroundLocalPhase.PARTIAL, partial.localPhase)
        assertEquals("文件不完整", partial.localStatusLabel)

        val active = PlaygroundPresenter.present(entry, entry.requiredArtifactNames, File("/ordinary/$primary").path)
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
    fun `upstream direct download does not imply a harzva publication`() {
        val entry = catalog.entries.first { it.id == "qwen2.5-coder-0.5b-instruct-q4-k-m-official" }
        val model = PlaygroundPresenter.present(entry, emptySet(), null)
        assertTrue(entry.distribution.downloadable)
        assertEquals("来源仓已发布 · 可直接下载", model.distributionLabel)
        assertEquals("上游官方", model.originLabel)
        assertFalse(model.attributionLabel.contains("Harzva"))
    }

    @Test
    fun `featured entry still needs cleared license and verified capabilities`() {
        val base = catalog.entries.first { it.id == "qwen2.5-omni-3b-q4-k-m-ggml-org" }
        val featured = base.copy(featured = true)
        val model = PlaygroundPresenter.present(featured, emptySet(), null)
        assertFalse(model.recommended)
        assertFalse(model.validationPassed)
    }

    @Test
    fun `trusted installer phases are distinct from same-name discovery`() {
        val entry = catalog.entries.first { it.id == "qwen3-0.6b-q4-k-m" }
        val name = entry.requiredArtifactNames.single()
        val total = entry.artifacts.sumOf { it.sizeBytes }
        val downloading = snapshot(
            entry.id,
            PlaygroundInstallPhase.DOWNLOADING,
            downloaded = total / 2,
            total = total,
        )
        val downloadModel = PlaygroundPresenter.present(
            entry,
            emptySet(),
            null,
            installSnapshot = downloading,
        )
        assertEquals(PlaygroundLocalPhase.DOWNLOADING, downloadModel.localPhase)
        assertTrue(downloadModel.localStatusLabel.startsWith("下载中"))
        assertEquals("取消下载", downloadModel.primaryActionLabel)

        val installed = snapshot(
            entry.id,
            PlaygroundInstallPhase.INSTALLED,
            downloaded = total,
            total = total,
            verified = setOf(name),
            installed = setOf(name),
        )
        assertEquals(
            PlaygroundLocalPhase.INSTALLED,
            PlaygroundPresenter.present(
                entry,
                setOf(name),
                null,
                installSnapshot = installed,
            ).localPhase,
        )
        assertEquals(
            PlaygroundLocalPhase.LOADED,
            PlaygroundPresenter.present(
                entry,
                setOf(name),
                File("/managed/$name").path,
                File("/managed/$name").path,
                installed,
            ).localPhase,
        )
    }

    @Test
    fun `same basename in another directory never renders trusted artifact as loaded`() {
        val entry = catalog.entries.first { it.id == "qwen3-0.6b-q4-k-m" }
        val name = entry.requiredArtifactNames.single()
        val total = entry.artifacts.sumOf { it.sizeBytes }
        val installed = snapshot(
            entry.id,
            PlaygroundInstallPhase.INSTALLED,
            downloaded = total,
            total = total,
            verified = setOf(name),
            installed = setOf(name),
        )

        val model = PlaygroundPresenter.present(
            entry,
            setOf(name),
            File("/external/$name").path,
            File("/internal/$name").path,
            installed,
        )

        assertEquals(PlaygroundLocalPhase.INSTALLED, model.localPhase)
        assertFalse(model.localStatusLabel == "已加载")
    }

    @Test
    fun `verification and source failures have explicit recovery labels`() {
        val entry = catalog.entries.first { it.id == "qwen3-0.6b-q4-k-m" }
        val name = entry.requiredArtifactNames.single()
        val total = entry.artifacts.sumOf { it.sizeBytes }
        val failed = snapshot(
            entry.id,
            PlaygroundInstallPhase.VERIFICATION_FAILED,
            downloaded = 0L,
            total = total,
            installed = setOf(name),
            failure = PlaygroundInstallFailure(
                PlaygroundInstallFailureCode.CHECKSUM_MISMATCH,
                "redacted",
                name,
            ),
        )
        val failedModel = PlaygroundPresenter.present(
            entry,
            setOf(name),
            null,
            installSnapshot = failed,
        )
        assertEquals(PlaygroundLocalPhase.VERIFICATION_FAILED, failedModel.localPhase)
        assertEquals("校验失败", failedModel.localStatusLabel)
        assertEquals("移除错误文件", failedModel.primaryActionLabel)

        val mismatch = failed.copy(
            phase = PlaygroundInstallPhase.SOURCE_MISMATCH,
            failure = PlaygroundInstallFailure(
                PlaygroundInstallFailureCode.SOURCE_MISMATCH,
                "redacted",
                name,
            ),
        )
        val mismatchModel = PlaygroundPresenter.present(
            entry,
            setOf(name),
            null,
            installSnapshot = mismatch,
        )
        assertEquals(PlaygroundLocalPhase.SOURCE_MISMATCH, mismatchModel.localPhase)
        assertEquals("来源不匹配", mismatchModel.localStatusLabel)
    }

    @Test
    fun `trusted-looking phases never render installed or loaded without verification`() {
        val entry = catalog.entries.first { it.id == "qwen3-0.6b-q4-k-m" }
        val name = entry.requiredArtifactNames.single()
        val total = entry.artifacts.sumOf { it.sizeBytes }
        val unverifiedLoaded = snapshot(
            entry.id,
            PlaygroundInstallPhase.LOADED,
            downloaded = total,
            total = total,
            installed = setOf(name),
        )

        val model = PlaygroundPresenter.present(
            entry,
            setOf(name),
            File("/ordinary/$name").path,
            File("/managed/$name").path,
            unverifiedLoaded,
        )

        assertEquals(PlaygroundLocalPhase.ACTIVE_UNVERIFIED, model.localPhase)
        assertFalse(model.localStatusLabel == "已加载")
        assertFalse(model.canUninstall)
    }

    @Test
    fun `typed install failures keep accurate recovery semantics`() {
        val entry = catalog.entries.first { it.id == "qwen3-0.6b-q4-k-m" }
        val total = entry.artifacts.sumOf { it.sizeBytes }
        fun failed(code: PlaygroundInstallFailureCode, phase: PlaygroundInstallPhase = PlaygroundInstallPhase.FAILED) =
            PlaygroundPresenter.present(
                entry,
                emptySet(),
                null,
                installSnapshot = snapshot(
                    entry.id,
                    phase,
                    downloaded = 0L,
                    total = total,
                    failure = PlaygroundInstallFailure(code, "redacted"),
                ),
            )

        assertEquals("存储空间不足", failed(PlaygroundInstallFailureCode.INSUFFICIENT_STORAGE).localStatusLabel)
        assertEquals("安装文件缺失", failed(PlaygroundInstallFailureCode.ARTIFACT_MISSING).localStatusLabel)
        assertEquals("原子安装失败", failed(PlaygroundInstallFailureCode.ATOMIC_INSTALL_FAILED).localStatusLabel)
        assertEquals("卸载未完成", failed(PlaygroundInstallFailureCode.UNINSTALL_FAILED).localStatusLabel)
        val io = failed(
            PlaygroundInstallFailureCode.VERIFICATION_IO_FAILED,
            PlaygroundInstallPhase.VERIFICATION_FAILED,
        )
        assertEquals(PlaygroundLocalPhase.VERIFICATION_IO_FAILED, io.localPhase)
        assertEquals("校验读取失败", io.localStatusLabel)
        assertEquals("重新校验", io.primaryActionLabel)
    }

    @Test
    fun `verification can be cancelled from UI contract`() {
        val entry = catalog.entries.first { it.id == "qwen3-0.6b-q4-k-m" }
        val total = entry.artifacts.sumOf { it.sizeBytes }
        val verifying = snapshot(entry.id, PlaygroundInstallPhase.VERIFYING, 0L, total)

        val model = PlaygroundPresenter.present(
            entry,
            emptySet(),
            null,
            installSnapshot = verifying,
        )

        assertEquals("取消校验", model.primaryActionLabel)
        assertTrue(model.primaryActionEnabled)
    }

    private fun snapshot(
        modelId: String,
        phase: PlaygroundInstallPhase,
        downloaded: Long,
        total: Long,
        verified: Set<String> = emptySet(),
        installed: Set<String> = emptySet(),
        failure: PlaygroundInstallFailure? = null,
    ) = PlaygroundInstallSnapshot(
        modelId = modelId,
        phase = phase,
        downloadedBytes = downloaded,
        totalBytes = total,
        expectedArtifactCount = 1,
        verifiedArtifactNames = verified,
        installedArtifactNames = installed,
        failure = failure,
    )
}
