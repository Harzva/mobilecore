package ai.mobilecore.network

import ai.mobilecore.omni.artifact.OmniInstallEnvironment
import ai.mobilecore.omni.artifact.OmniInstallEnvironmentProbe
import ai.mobilecore.runtime.BackendInfo
import ai.mobilecore.runtime.ChatMessage
import ai.mobilecore.runtime.ChatOptions
import ai.mobilecore.runtime.ChatResult
import ai.mobilecore.runtime.ChatToken
import ai.mobilecore.runtime.LoadOptions
import ai.mobilecore.runtime.LoadResult
import ai.mobilecore.runtime.RuntimeBackend
import ai.mobilecore.runtime.RuntimeMetrics
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
    fun preflightReportsMemoryAndStorageWithoutClaimingInstallation() {
        val health = controller().health()
        val preflight = health.getJSONObject("preflight")

        assertTrue(preflight.getJSONObject("memory").getBoolean("ok"))
        assertTrue(preflight.getJSONObject("storage").getBoolean("ok"))
        assertFalse(health.getBoolean("model_loaded"))
    }

    private fun controller(): OmniLocalController {
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
