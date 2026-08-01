package ai.mobilecore.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VisionModelImportPresenterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `empty library exposes every supported package slot as missing`() {
        val model = VisionModelImportPresenter.present(emptyList())

        assertEquals(VisionModelSlot.entries.size, model.totalCount)
        assertEquals(0, model.readyCount)
        assertEquals(VisionModelSlot.entries.toSet(), model.packages.map { packageModel ->
            VisionModelSlot.entries.first { it.taskLabel == packageModel.taskLabel }
        }.toSet())
        assertTrue(model.packages.all { it.status == VisionPackageStatus.MISSING_FILES })
    }

    @Test
    fun `yolo onnx package is file ready but does not claim acceleration`() {
        val model = VisionModelImportPresenter.presentPackage(
            VisionModelPackageInput(
                id = "yolo11n-detect",
                slot = VisionModelSlot.YOLO_DETECT,
                artifacts = listOf(
                    VisionModelArtifact("yolo11n.onnx", VisionArtifactRole.YOLO_MODEL, 6_000_000L)
                )
            )
        )

        assertEquals(VisionPackageStatus.READY, model.status)
        assertEquals("ONNX Runtime Mobile", model.runtimeLabel)
        assertTrue(model.accelerationLabel.contains("CPU"))
        assertTrue(model.accelerationLabel.contains("未注册"))
        assertFalse(model.accelerationLabel.contains("GPU 已启用"))
        assertEquals(listOf("diagnose", "remove"), model.actions.map { it.id })
    }

    @Test
    fun `clip dual encoders are ready for open text retrieval`() {
        val model = VisionModelImportPresenter.presentPackage(
            VisionModelPackageInput(
                id = "mobileclip-s2",
                slot = VisionModelSlot.CLIP_RETRIEVAL,
                artifacts = listOf(
                    VisionModelArtifact("mobileclip-image.onnx", VisionArtifactRole.CLIP_IMAGE_ENCODER),
                    VisionModelArtifact("mobileclip-text.onnx", VisionArtifactRole.CLIP_TEXT_ENCODER)
                )
            )
        )

        assertEquals(VisionPackageStatus.READY, model.status)
        assertTrue(model.statusDetail.contains("开放文本检索"))
        assertEquals(2, model.artifactLabels.size)
    }

    @Test
    fun `clip image encoder with embedding sidecar is limited to fixed labels`() {
        val model = VisionModelImportPresenter.presentPackage(
            VisionModelPackageInput(
                id = "clip-fixed-labels",
                slot = VisionModelSlot.CLIP_RETRIEVAL,
                artifacts = listOf(
                    VisionModelArtifact("clip-image.onnx", VisionArtifactRole.CLIP_IMAGE_ENCODER),
                    VisionModelArtifact("cifar10-text-embeddings.json", VisionArtifactRole.CLIP_EMBEDDING_SIDECAR)
                )
            )
        )

        assertEquals(VisionPackageStatus.LIMITED, model.status)
        assertEquals("固定标签就绪", model.statusLabel)
        assertTrue(model.statusDetail.contains("仍缺文本编码器"))
    }

    @Test
    fun `vlm requires gguf main model and mmproj as a pair`() {
        val missingProjection = VisionModelImportPresenter.presentPackage(
            VisionModelPackageInput(
                id = "qwen-vl",
                slot = VisionModelSlot.SMALL_VLM,
                artifacts = listOf(
                    VisionModelArtifact("qwen-vl-0.8b-q4.gguf", VisionArtifactRole.VLM_MAIN_MODEL)
                )
            )
        )
        val complete = VisionModelImportPresenter.presentPackage(
            VisionModelPackageInput(
                id = "qwen-vl-complete",
                slot = VisionModelSlot.SMALL_VLM,
                artifacts = listOf(
                    VisionModelArtifact("qwen-vl-0.8b-q4.gguf", VisionArtifactRole.VLM_MAIN_MODEL),
                    VisionModelArtifact("qwen-vl-mmproj-f16.gguf", VisionArtifactRole.VLM_MMPROJ)
                )
            )
        )

        assertEquals(VisionPackageStatus.MISSING_FILES, missingProjection.status)
        assertTrue(missingProjection.statusDetail.contains("缺少匹配的 mmproj"))
        assertEquals(VisionPackageStatus.READY, complete.status)
        assertTrue(complete.statusDetail.contains("架构和投影维度仍需运行诊断"))
        assertEquals("GGUF + mmproj", complete.formatLabel)
        assertTrue(complete.accelerationLabel.contains("gpu_layers=0"))
    }

    @Test
    fun `unsupported mnn execution path is incompatible rather than falsely ready`() {
        val model = VisionModelImportPresenter.presentPackage(
            VisionModelPackageInput(
                id = "yolo-mnn",
                slot = VisionModelSlot.YOLO_SEGMENT,
                artifacts = listOf(
                    VisionModelArtifact("yolo11n-seg.mnn", VisionArtifactRole.YOLO_MODEL)
                )
            )
        )

        assertEquals(VisionPackageStatus.INCOMPATIBLE, model.status)
        assertTrue(model.statusDetail.contains("尚未接入"))
        assertTrue(model.accelerationLabel.contains("未验证"))
    }

    @Test
    fun `import progress exposes pause then resume and remove actions`() {
        val importing = VisionModelImportPresenter.presentPackage(
            VisionModelPackageInput(
                id = "yolo-import",
                slot = VisionModelSlot.YOLO_DETECT,
                transfer = VisionImportTransfer(
                    phase = VisionTransferPhase.IMPORTING,
                    bytesCopied = 75L,
                    totalBytes = 100L
                )
            )
        )
        val paused = VisionModelImportPresenter.presentPackage(
            VisionModelPackageInput(
                id = "yolo-import",
                slot = VisionModelSlot.YOLO_DETECT,
                transfer = VisionImportTransfer(
                    phase = VisionTransferPhase.PAUSED,
                    bytesCopied = 75L,
                    totalBytes = 100L
                )
            )
        )

        assertEquals(75, importing.progressPercent)
        assertEquals(listOf("pause"), importing.actions.map { it.id })
        assertEquals(listOf("resume", "remove"), paused.actions.map { it.id })
    }

    @Test
    fun `catalog groups imported files into yolo clip and vlm packages`() {
        listOf(
            "yolo11n.onnx",
            "yolo11n-seg.tflite",
            "mobileclip-image.onnx",
            "mobileclip-text.onnx",
            "qwen35-08b.gguf",
            "mmproj-qwen35.gguf"
        ).forEach { temporaryFolder.newFile(it) }

        val packages = VisionModelImportCatalog.fromFiles(temporaryFolder.root.listFiles().orEmpty().toList())
            .associateBy { it.slot }

        assertEquals(1, packages.getValue(VisionModelSlot.YOLO_DETECT).artifacts.size)
        assertEquals(1, packages.getValue(VisionModelSlot.YOLO_SEGMENT).artifacts.size)
        assertEquals(2, packages.getValue(VisionModelSlot.CLIP_RETRIEVAL).artifacts.size)
        assertEquals(
            setOf(VisionArtifactRole.VLM_MAIN_MODEL, VisionArtifactRole.VLM_MMPROJ),
            packages.getValue(VisionModelSlot.SMALL_VLM).artifacts.map { it.role }.toSet()
        )
    }
}
