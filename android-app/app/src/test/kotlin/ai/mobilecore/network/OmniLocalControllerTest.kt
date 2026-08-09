package ai.mobilecore.network

import ai.mobilecore.omni.artifact.OmniInstallEnvironment
import ai.mobilecore.omni.artifact.OmniInstallEnvironmentProbe
import ai.mobilecore.omni.artifact.OmniArtifactFailureCode
import ai.mobilecore.omni.artifact.OmniArtifactRole
import ai.mobilecore.omni.artifact.Qwen25Omni3bArtifacts
import ai.mobilecore.runtime.ArtifactHealth
import ai.mobilecore.runtime.BackendInfo
import ai.mobilecore.runtime.ChatMessage
import ai.mobilecore.runtime.ChatOptions
import ai.mobilecore.runtime.ChatResult
import ai.mobilecore.runtime.ChatToken
import ai.mobilecore.runtime.LoadOptions
import ai.mobilecore.runtime.LoadResult
import ai.mobilecore.runtime.MultimodalRuntimeBackend
import ai.mobilecore.runtime.RuntimeBackend
import ai.mobilecore.runtime.RuntimeMetrics
import ai.mobilecore.runtime.RuntimeMultimodalStatus
import ai.mobilecore.runtime.RuntimeModel
import ai.mobilecore.runtime.RuntimeProjector
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OmniLocalControllerTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("mobilecore-omni-controller").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun healthReportsExactArtifactsAndNoUnavailableModalities() {
        val health = controller().health()
        val capabilities = health.getJSONObject("capabilities")
        val artifacts = health.getJSONObject("artifacts")

        assertFalse(capabilities.getBoolean("image_input"))
        assertFalse(capabilities.getBoolean("audio_input"))
        assertFalse(capabilities.getBoolean("video_input"))
        assertFalse(capabilities.getBoolean("audio_output"))
        assertEquals(
            "4b0bd358c1e9ec55dd3055ef6d71c958c821533d85916a10cfa89c4552a86e29",
            artifacts.getJSONObject("main").getString("digest"),
        )
        assertEquals(
            1_538_031_328L,
            artifacts.getJSONObject("mmproj").getLong("expected_bytes"),
        )
        assertFalse(health.getJSONObject("install").getBoolean("pair_verified"))
        assertFalse(health.toString().contains(root.absolutePath))
    }

    @Test
    fun installRejectsMissingConsentBeforeStartingDownload() {
        val result = controller().install(
            JSONObject()
                .put("explicit_consent", false)
                .put("accepted_license_id", "qwen-research"),
        )

        assertFalse(result.accepted)
        assertEquals(
            "explicit_consent_required",
            result.body.getJSONObject("error").getString("code"),
        )
    }

    @Test
    fun loadRequiresBothVerifiedArtifacts() {
        val result = controller().load(JSONObject())

        assertFalse(result.accepted)
        assertEquals("artifact_missing", result.body.getJSONObject("error").getString("code"))
    }

    @Test
    fun projectorRuntimeFailuresRemainTyped() {
        val missing = projectorRuntimeFailure("artifact_missing")
        val incompatible = projectorRuntimeFailure("unsupported_modality")
        val rejected = projectorRuntimeFailure("model_load_failed")

        assertEquals(OmniArtifactFailureCode.ARTIFACT_MISSING, missing.code)
        assertEquals(OmniArtifactFailureCode.PROJECTOR_INCOMPATIBLE, incompatible.code)
        assertEquals(OmniArtifactFailureCode.PROJECTOR_LOAD_FAILED, rejected.code)
        assertEquals(OmniArtifactRole.MMPROJ, rejected.artifactRole)
    }

    @Test
    fun preflightReportsMemoryAndStorageWithoutClaimingInstallation() {
        val health = controller().health()
        val preflight = health.getJSONObject("preflight")

        assertTrue(preflight.getJSONObject("memory").getBoolean("ok"))
        assertTrue(preflight.getJSONObject("storage").getBoolean("ok"))
        assertFalse(health.getBoolean("model_loaded"))
    }

    @Test
    fun statusReportsCurrentResourcesBeforeAnyInstallAttempt() {
        val status = controller().status()
        val preflight = status.getJSONObject("preflight")

        assertEquals(8_000_000_000L, preflight.getLong("available_memory_bytes"))
        assertEquals(10_000_000_000L, preflight.getLong("available_storage_bytes"))
        assertTrue(preflight.getBoolean("memory_sufficient"))
        assertTrue(preflight.getBoolean("storage_sufficient"))
        assertTrue(preflight.getBoolean("resources_sufficient"))
        assertFalse(status.getBoolean("loaded"))
        assertFalse(preflight.has("passed"))
    }

    @Test
    fun healthReportsBackgroundRestrictionForClientRouting() {
        val health = controller(backgroundRestricted = true).health()

        assertTrue(health.getBoolean("background_restricted"))
        assertFalse(health.getBoolean("model_loaded"))
    }

    @Test
    fun ordinaryTextModelUsesActiveRuntimePreflightInsteadOfOmniRequirements() {
        val model = RuntimeModel(
            id = "small-q4_k_m",
            path = root.resolve("small-q4_k_m.gguf").absolutePath,
            format = "gguf",
            backend = "llama.cpp",
            quantization = "Q4_K_M",
            contextLength = 2048,
            sizeBytes = 400_000_000L,
            loaded = true,
        )
        val health = OmniLocalController(
            backend = ActiveTestRuntimeBackend(model.id, model.path),
            version = "test",
            installDirectory = root,
            environmentProbe = OmniInstallEnvironmentProbe {
                OmniInstallEnvironment(
                    availableMemoryBytes = 1_000_000_000L,
                    availableStorageBytes = 1_000_000_000L,
                    wifiConnected = false,
                )
            },
            runtimeInfo = { JSONObject().put("modelLoaded", true) },
            activeModelLookup = { model },
        ).health()

        assertEquals("llama.cpp", health.getString("runtime"))
        assertTrue(health.getJSONObject("preflight").getBoolean("ok"))
        assertEquals(
            400_000_000L + 128L * 1024L * 1024L,
            health.getJSONObject("preflight").getJSONObject("memory").getLong("required_bytes"),
        )
        assertFalse(health.getJSONObject("artifacts").getJSONObject("main").getBoolean("verified"))
        assertTrue(health.getJSONObject("artifacts").getJSONObject("main").isNull("digest"))
        assertFalse(health.getJSONObject("artifacts").getJSONObject("mmproj").getBoolean("present"))
    }

    @Test
    fun healthUsesCatalogBackedCurrentProcessDigestProofForActiveTextModel() {
        val digest = "18ea1f301079bba6391ab6d455c0c8565fd5a3214075eb2cd9daf351dedc719b"
        val model = RuntimeModel(
            id = "qwen3-0.6b-q4_k_m",
            path = root.resolve("qwen3-0.6b-q4_k_m.gguf").absolutePath,
            format = "gguf",
            backend = "llama.cpp",
            quantization = "Q4_K_M",
            contextLength = 2048,
            sizeBytes = 484_220_160L,
            loaded = true,
        )
        val health = OmniLocalController(
            backend = ActiveTestRuntimeBackend(model.id, model.path),
            version = "test",
            installDirectory = root,
            environmentProbe = OmniInstallEnvironmentProbe {
                OmniInstallEnvironment(
                    availableMemoryBytes = 1_500_000_000L,
                    availableStorageBytes = 1_000_000_000L,
                    wifiConnected = false,
                )
            },
            runtimeInfo = { JSONObject().put("modelLoaded", true) },
            activeModelLookup = { model },
            activeArtifactHealthLookup = {
                ArtifactHealth(
                    fileName = "qwen3-0.6b-q4_k_m.gguf",
                    expectedSha256 = digest,
                    expectedBytes = model.sizeBytes,
                    present = true,
                    verified = true,
                )
            },
        ).health()

        val artifact = health.getJSONObject("artifacts").getJSONObject("main")
        assertEquals(digest, artifact.getString("digest"))
        assertEquals(model.sizeBytes, artifact.getLong("expected_bytes"))
        assertTrue(artifact.getBoolean("present"))
        assertTrue(artifact.getBoolean("verified"))
    }

    @Test
    fun genericVerifiedRuntimeProbeAdvertisesImageWithoutClaimingArtifactDigest() {
        val model = RuntimeModel(
            id = "qwen3.5-0.8b-q4_k_m",
            path = root.resolve("qwen3.5-0.8b-q4_k_m.gguf").absolutePath,
            format = "gguf",
            backend = "llama.cpp",
            quantization = "Q4_K_M",
            contextLength = 2048,
            sizeBytes = 579_615_840L,
            loaded = true,
        )
        val projector = RuntimeProjector(
            id = "mmproj-qwen3.5-0.8b-bf16",
            path = root.resolve("mmproj-qwen3.5-0.8b-bf16.gguf").absolutePath,
            sizeBytes = 207_346_400L,
            modelFamily = "qwen3.5-0.8b",
        )
        val health = OmniLocalController(
            backend = ActiveMultimodalTestRuntimeBackend(
                model.id,
                model.path,
                projector.id,
                projector.path,
            ),
            version = "test",
            installDirectory = root,
            environmentProbe = OmniInstallEnvironmentProbe {
                OmniInstallEnvironment(
                    availableMemoryBytes = 1_500_000_000L,
                    availableStorageBytes = 1_000_000_000L,
                    wifiConnected = false,
                )
            },
            runtimeInfo = { JSONObject().put("modelLoaded", true) },
            activeModelLookup = { model },
            activeProjectorLookup = { projector },
        ).health()

        assertEquals("llama.cpp/libmtmd", health.getString("runtime"))
        assertTrue(health.getJSONObject("capabilities").getBoolean("image_input"))
        assertFalse(health.getJSONObject("capabilities").getBoolean("audio_input"))
        val artifact = health.getJSONObject("artifacts").getJSONObject("mmproj")
        assertEquals("mmproj-qwen3.5-0.8b-bf16.gguf", artifact.getString("file_name"))
        assertTrue(artifact.getBoolean("present"))
        assertFalse(artifact.getBoolean("verified"))
        assertTrue(artifact.isNull("digest"))
        assertEquals(
            model.sizeBytes + projector.sizeBytes + 128L * 1024L * 1024L,
            health.getJSONObject("preflight").getJSONObject("memory").getLong("required_bytes"),
        )
    }

    @Test
    fun healthRejectsSameBasenameWhenLookupDoesNotMatchExactActivePath() {
        val internal = RuntimeModel(
            id = "same-q4_k_m",
            path = root.resolve("internal/same-q4_k_m.gguf").absolutePath,
            format = "gguf",
            backend = "llama.cpp",
            quantization = "Q4_K_M",
            contextLength = 2048,
            sizeBytes = 10L,
            loaded = false,
        )
        val externalPath = root.resolve("external/same-q4_k_m.gguf").absolutePath
        val health = OmniLocalController(
            backend = ActiveTestRuntimeBackend(internal.id, externalPath),
            version = "test",
            installDirectory = root,
            environmentProbe = environmentProbe(),
            runtimeInfo = { JSONObject().put("modelLoaded", true) },
            // A basename-only lookup would return this wrong artifact. The controller must reject it.
            activeModelLookup = { internal },
        ).health()

        assertFalse(health.getBoolean("model_loaded"))
        assertTrue(health.isNull("active_model"))
        assertEquals("unknown", health.getString("quantization"))
        assertFalse(health.getJSONObject("capabilities").getBoolean("text_input"))
    }

    @Test
    fun healthRejectsSameNamedProjectorFromDifferentPath() {
        val model = RuntimeModel(
            id = "vision-q4_k_m",
            path = root.resolve("vision-q4_k_m.gguf").absolutePath,
            format = "gguf",
            backend = "llama.cpp",
            quantization = "Q4_K_M",
            contextLength = 2048,
            sizeBytes = 10L,
            loaded = true,
        )
        val internalProjector = RuntimeProjector(
            id = "mmproj-vision-bf16",
            path = root.resolve("internal/mmproj-vision-bf16.gguf").absolutePath,
            sizeBytes = 8L,
            modelFamily = "vision",
        )
        val externalProjectorPath = root.resolve("external/mmproj-vision-bf16.gguf").absolutePath
        val health = OmniLocalController(
            backend = ActiveMultimodalTestRuntimeBackend(
                model.id,
                model.path,
                internalProjector.id,
                externalProjectorPath,
            ),
            version = "test",
            installDirectory = root,
            environmentProbe = environmentProbe(),
            runtimeInfo = { JSONObject().put("modelLoaded", true) },
            activeModelLookup = { model },
            // A projector-id lookup would return this wrong, same-named artifact.
            activeProjectorLookup = { internalProjector },
        ).health()

        assertTrue(health.getBoolean("model_loaded"))
        assertEquals("llama.cpp", health.getString("runtime"))
        assertFalse(health.getJSONObject("capabilities").getBoolean("image_input"))
        assertFalse(health.getJSONObject("artifacts").getJSONObject("mmproj").getBoolean("present"))
    }

    @Test
    fun uninstallDoesNotUnloadAnotherSameNamedArtifact() {
        val main = requireNotNull(Qwen25Omni3bArtifacts.manifest.artifact(OmniArtifactRole.MAIN))
        val mmproj = requireNotNull(Qwen25Omni3bArtifacts.manifest.artifact(OmniArtifactRole.MMPROJ))
        val installedMain = root.resolve(main.fileName).apply { writeText("installed-main") }
        val installedProjector = root.resolve(mmproj.fileName).apply { writeText("installed-projector") }
        val externalSameName = root.resolve("external/${main.fileName}").apply {
            parentFile?.mkdirs()
            writeText("other-model")
        }
        val backend = MutableActiveRuntimeBackend(
            modelId = main.fileName.removeSuffix(".gguf"),
            modelPath = externalSameName.absolutePath,
        )

        val result = controllerForBackend(backend).uninstall()

        assertTrue(result.accepted)
        assertEquals(0, backend.unloadCalls)
        assertTrue(backend.isModelLoaded())
        assertFalse(installedMain.exists())
        assertFalse(installedProjector.exists())
    }

    @Test
    fun uninstallUnloadsDifferentMainWhenItUsesManagedOmniProjector() {
        val main = requireNotNull(Qwen25Omni3bArtifacts.manifest.artifact(OmniArtifactRole.MAIN))
        val mmproj = requireNotNull(Qwen25Omni3bArtifacts.manifest.artifact(OmniArtifactRole.MMPROJ))
        val installedMain = root.resolve(main.fileName).apply { writeText("installed-main") }
        val installedProjector = root.resolve(mmproj.fileName).apply { writeText("installed-projector") }
        val otherMain = root.resolve("Qwen2.5-Omni-3B-Q5_K_M.gguf").apply { writeText("other-main") }
        val backend = MutableActiveMultimodalRuntimeBackend(
            modelId = otherMain.nameWithoutExtension,
            modelPath = otherMain.absolutePath,
            projectorId = installedProjector.nameWithoutExtension,
            projectorPath = installedProjector.absolutePath,
        )

        val result = controllerForBackend(backend).uninstall()

        assertTrue(result.accepted)
        assertEquals(1, backend.unloadCalls)
        assertFalse(backend.isModelLoaded())
        assertFalse(installedMain.exists())
        assertFalse(installedProjector.exists())
        assertTrue(otherMain.exists())
    }

    @Test
    fun uninstallKeepsArtifactsWhenManagedProjectorCannotBeConfirmedUnloaded() {
        val main = requireNotNull(Qwen25Omni3bArtifacts.manifest.artifact(OmniArtifactRole.MAIN))
        val mmproj = requireNotNull(Qwen25Omni3bArtifacts.manifest.artifact(OmniArtifactRole.MMPROJ))
        val installedMain = root.resolve(main.fileName).apply { writeText("installed-main") }
        val installedProjector = root.resolve(mmproj.fileName).apply { writeText("installed-projector") }
        val otherMain = root.resolve("Qwen2.5-Omni-3B-Q5_K_M.gguf").apply { writeText("other-main") }
        val backend = MutableActiveMultimodalRuntimeBackend(
            modelId = otherMain.nameWithoutExtension,
            modelPath = otherMain.absolutePath,
            projectorId = installedProjector.nameWithoutExtension,
            projectorPath = installedProjector.absolutePath,
            clearOnUnload = false,
        )

        val result = controllerForBackend(backend).uninstall()

        assertFalse(result.accepted)
        assertEquals("model_load_failed", result.body.getJSONObject("error").getString("code"))
        assertEquals(1, backend.unloadCalls)
        assertTrue(installedMain.exists())
        assertTrue(installedProjector.exists())
    }

    @Test
    fun uninstallKeepsOmniArtifactsWhenRuntimeCannotConfirmUnload() {
        val main = requireNotNull(Qwen25Omni3bArtifacts.manifest.artifact(OmniArtifactRole.MAIN))
        val mmproj = requireNotNull(Qwen25Omni3bArtifacts.manifest.artifact(OmniArtifactRole.MMPROJ))
        val installedMain = root.resolve(main.fileName).apply { writeText("installed-main") }
        val installedProjector = root.resolve(mmproj.fileName).apply { writeText("installed-projector") }
        val backend = MutableActiveRuntimeBackend(
            modelId = main.fileName.removeSuffix(".gguf"),
            modelPath = installedMain.absolutePath,
            unloadResult = true,
            clearOnUnload = false,
        )

        val result = controllerForBackend(backend).uninstall()

        assertFalse(result.accepted)
        assertEquals("model_load_failed", result.body.getJSONObject("error").getString("code"))
        assertEquals(1, backend.unloadCalls)
        assertTrue(installedMain.exists())
        assertTrue(installedProjector.exists())
    }

    @Test
    fun uninstallFailsClosedWhenLoadedRuntimeHasNoArtifactPath() {
        val main = requireNotNull(Qwen25Omni3bArtifacts.manifest.artifact(OmniArtifactRole.MAIN))
        val installedMain = root.resolve(main.fileName).apply { writeText("installed-main") }
        val backend = MutableActiveRuntimeBackend(
            modelId = main.fileName.removeSuffix(".gguf"),
            modelPath = null,
        )

        val result = controllerForBackend(backend).uninstall()

        assertFalse(result.accepted)
        assertEquals("model_load_failed", result.body.getJSONObject("error").getString("code"))
        assertEquals(0, backend.unloadCalls)
        assertTrue(installedMain.exists())
    }

    private fun controller(backgroundRestricted: Boolean = false): OmniLocalController {
        val backend = TestRuntimeBackend()
        return OmniLocalController(
            backend = backend,
            version = "test",
            installDirectory = root,
            environmentProbe = OmniInstallEnvironmentProbe {
                OmniInstallEnvironment(
                    availableMemoryBytes = 8_000_000_000L,
                    availableStorageBytes = 10_000_000_000L,
                    wifiConnected = true,
                )
            },
            runtimeInfo = {
                JSONObject()
                    .put("modelLoaded", false)
                    .put("visionInput", false)
                    .put("audioInput", false)
            },
            backgroundRestrictedProbe = { backgroundRestricted },
        )
    }

    private fun controllerForBackend(backend: RuntimeBackend): OmniLocalController {
        return OmniLocalController(
            backend = backend,
            version = "test",
            installDirectory = root,
            environmentProbe = environmentProbe(),
            runtimeInfo = { JSONObject().put("modelLoaded", backend.isModelLoaded()) },
        )
    }

    private fun environmentProbe() = OmniInstallEnvironmentProbe {
        OmniInstallEnvironment(
            availableMemoryBytes = 8_000_000_000L,
            availableStorageBytes = 10_000_000_000L,
            wifiConnected = true,
        )
    }
}

private class TestRuntimeBackend : RuntimeBackend {
    override fun backendInfo() = BackendInfo("test", "jvm", "test", emptyList(), listOf("cpu"), "ok")
    override fun loadModel(modelPath: String, options: LoadOptions) = LoadResult(false, "none", 0, 0)
    override fun unloadModel() = true
    override fun isModelLoaded() = false
    override fun chat(messages: List<ChatMessage>, options: ChatOptions) =
        ChatResult(model = options.model, message = "not used")

    override fun streamChat(messages: List<ChatMessage>, options: ChatOptions): Sequence<ChatToken> =
        emptySequence()

    override fun metrics() = RuntimeMetrics(activeModel = null, backend = "test")
}

private class ActiveTestRuntimeBackend(
    private val modelId: String,
    private val modelPath: String,
) : RuntimeBackend {
    override fun backendInfo() = BackendInfo("test", "jvm", "test", emptyList(), listOf("cpu"), "ok")
    override fun loadModel(modelPath: String, options: LoadOptions) = LoadResult(true, modelId, 0, 0)
    override fun unloadModel() = true
    override fun isModelLoaded() = true
    override fun activeModelPath() = modelPath
    override fun chat(messages: List<ChatMessage>, options: ChatOptions) =
        ChatResult(model = options.model, message = "not used")

    override fun streamChat(messages: List<ChatMessage>, options: ChatOptions): Sequence<ChatToken> =
        emptySequence()

    override fun metrics() = RuntimeMetrics(activeModel = modelId, backend = "test")
}

private class ActiveMultimodalTestRuntimeBackend(
    private val modelId: String,
    private val modelPath: String,
    private val projectorId: String,
    private val projectorPath: String,
) : RuntimeBackend, MultimodalRuntimeBackend {
    override fun backendInfo() = BackendInfo("test", "jvm", "test", emptyList(), listOf("cpu"), "ok")
    override fun loadModel(modelPath: String, options: LoadOptions) = LoadResult(true, modelId, 0, 0)
    override fun unloadModel() = true
    override fun isModelLoaded() = true
    override fun activeModelPath() = modelPath
    override fun chat(messages: List<ChatMessage>, options: ChatOptions) =
        ChatResult(model = options.model, message = "not used")

    override fun streamChat(messages: List<ChatMessage>, options: ChatOptions): Sequence<ChatToken> =
        emptySequence()

    override fun metrics() = RuntimeMetrics(activeModel = modelId, backend = "test")
    override fun multimodalStatus() = RuntimeMultimodalStatus(
        projectorId = projectorId,
        projectorPath = projectorPath,
        imageInput = true,
    )

    override fun mediaChat(
        modelId: String,
        mediaPath: String,
        mediaType: String,
        prompt: String,
        maxTokens: Int,
    ) = ChatResult(model = modelId, message = "not used")
}

private class MutableActiveRuntimeBackend(
    private val modelId: String,
    private var modelPath: String?,
    private val unloadResult: Boolean = true,
    private val clearOnUnload: Boolean = true,
) : RuntimeBackend {
    private var loaded = true
    var unloadCalls: Int = 0
        private set

    override fun backendInfo() = BackendInfo("test", "jvm", "test", emptyList(), listOf("cpu"), "ok")
    override fun loadModel(modelPath: String, options: LoadOptions) = LoadResult(false, modelId, 0, 0)
    override fun unloadModel(): Boolean {
        unloadCalls += 1
        if (unloadResult && clearOnUnload) {
            loaded = false
            modelPath = null
        }
        return unloadResult
    }

    override fun isModelLoaded() = loaded
    override fun activeModelPath() = modelPath
    override fun chat(messages: List<ChatMessage>, options: ChatOptions) =
        ChatResult(model = options.model, message = "not used")

    override fun streamChat(messages: List<ChatMessage>, options: ChatOptions): Sequence<ChatToken> =
        emptySequence()

    override fun metrics() = RuntimeMetrics(activeModel = modelId.takeIf { loaded }, backend = "test")
}

private class MutableActiveMultimodalRuntimeBackend(
    private val modelId: String,
    private var modelPath: String?,
    private val projectorId: String,
    private var projectorPath: String?,
    private val clearOnUnload: Boolean = true,
) : RuntimeBackend, MultimodalRuntimeBackend {
    private var loaded = true
    var unloadCalls: Int = 0
        private set

    override fun backendInfo() = BackendInfo("test", "jvm", "test", emptyList(), listOf("cpu"), "ok")
    override fun loadModel(modelPath: String, options: LoadOptions) = LoadResult(false, modelId, 0, 0)
    override fun unloadModel(): Boolean {
        unloadCalls += 1
        if (clearOnUnload) {
            loaded = false
            modelPath = null
            projectorPath = null
        }
        return true
    }

    override fun isModelLoaded() = loaded
    override fun activeModelPath() = modelPath
    override fun chat(messages: List<ChatMessage>, options: ChatOptions) =
        ChatResult(model = options.model, message = "not used")

    override fun streamChat(messages: List<ChatMessage>, options: ChatOptions): Sequence<ChatToken> =
        emptySequence()

    override fun metrics() = RuntimeMetrics(modelId.takeIf { loaded }, "test")
    override fun multimodalStatus() = RuntimeMultimodalStatus(
        projectorId = projectorId.takeIf { loaded },
        projectorPath = projectorPath,
        imageInput = loaded,
    )

    override fun mediaChat(
        modelId: String,
        mediaPath: String,
        mediaType: String,
        prompt: String,
        maxTokens: Int,
    ) = ChatResult(model = modelId, message = "not used")
}
